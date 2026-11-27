"""
Runner script for Timecard Tracker & Time Off Auditor.
Usage:
  python run.py                        -> Starts the web dashboard at http://localhost:8000 (clean state)
  python run.py --sync                 -> Runs the Playwright sync directly from CLI (all weeks)
  python run.py --seed-sample          -> Seeds database with the provided sample HTML fixtures
  python run.py --pack-ota             -> Packs web-only OTA update (preserves apk_build; no APK reinstall prompt)
  python run.py --pack-ota --build-apk -> Packs OTA bundle AND compiles standalone Android APK (Timecard-Tracker.apk)
  python run.py --build-apk            -> Syncs web assets & compiles standalone Android APK (Timecard-Tracker.apk)
  python run.py --purge-cdn            -> Purges jsDelivr CDN cache for version.json, ota_bundle.zip, and APK
"""
import sys
import os
import re
import json
import time
import shutil
import glob
import hashlib
import zipfile
import subprocess
import argparse
import socket
import uvicorn

from backend.database import (
    init_db, save_parsed_week, upsert_entitlements, clear_all_data, trigger_full_vault_sync
)
from backend.parser import parse_timecard_html
from backend.timeoff_parser import parse_timeoff_html
from backend.scraper import run_timecard_sync, logout_portal_session


def seed_sample():
    init_db()

    # 1. Seed Timecard
    timecard_file = os.path.join(os.path.dirname(__file__), "test_data", "sample_timecard.html")
    if os.path.exists(timecard_file):
        print("Loading sample timecard data...")
        with open(timecard_file, "r", encoding="utf-8") as f:
            html = f.read()
        parsed = parse_timecard_html(html)
        save_parsed_week(parsed)
        print(f"Successfully seeded week {parsed['week_summary']['week_end_date']}: {len(parsed['shifts'])} shifts.")

    # 2. Seed Time Off Entitlements
    timeoff_file = os.path.join(os.path.dirname(__file__), "test_data", "sample_timeoff.html")
    if os.path.exists(timeoff_file):
        print("Loading sample time off entitlement bank...")
        with open(timeoff_file, "r", encoding="utf-8") as f:
            to_html = f.read()
        parsed_to = parse_timeoff_html(to_html)
        upsert_entitlements(parsed_to["entitlements"], profile=parsed_to.get("profile"))
        print(f"Successfully seeded {len(parsed_to['entitlements'])} leave entitlements for {parsed_to.get('profile', {}).get('emp_name')}.")

    # 3. Synchronize seeded records with MongoDB Atlas Cloud Database and local archive
    try:
        emp_id = (parsed_to.get("profile") or {}).get("emp_id") if 'parsed_to' in locals() else None
        print("Syncing data to local permanent vault and MongoDB Atlas...")
        v_res = trigger_full_vault_sync(emp_id=emp_id)
        if v_res.get("cloud_synced"):
            print(f"SUCCESS: Seeded data permanently backed up to MongoDB Atlas for emp_id '{v_res.get('emp_id')}'!")
        else:
            print(f"Local vault updated. MongoDB status: {v_res.get('cloud_synced')}")
    except Exception as ex:
        print(f"Notice during vault sync: {ex}")


def sync_android_assets():
    """Copies static/ web assets into android/app/src/main/assets/www/ (excluding .zip archives)."""
    root_dir = os.path.dirname(os.path.abspath(__file__))
    static_dir = os.path.join(root_dir, "static")
    assets_www = os.path.join(root_dir, "android", "app", "src", "main", "assets", "www")
    os.makedirs(assets_www, exist_ok=True)

    # Clean any stale archives from asset directory
    for existing in os.listdir(assets_www):
        if existing.endswith(".zip"):
            try:
                os.remove(os.path.join(assets_www, existing))
            except Exception:
                pass

    for fname in os.listdir(static_dir):
        if fname.endswith(".zip"):
            continue
        src = os.path.join(static_dir, fname)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(assets_www, fname))
    print(f"Synced clean web assets to {assets_www}")


def compute_sha256(file_path: str) -> str:
    """Computes the lowercase hexadecimal SHA-256 digest of a file."""
    digest = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest().lower()


def _sync_native_apk_version_files(root_dir: str, version_str: str, apk_build: int):
    """Synchronizes APP_BASE_BUILD/APP_BASE_VERSION in MainActivity.java and versionCode/versionName in build.gradle."""
    main_java_path = os.path.join(
        root_dir, "android", "app", "src", "main", "java", "com", "timecard", "tracker", "MainActivity.java"
    )
    if os.path.exists(main_java_path):
        with open(main_java_path, "r", encoding="utf-8") as f:
            java_txt = f.read()
        java_txt = re.sub(
            r'APP_BASE_BUILD\s*=\s*[0-9]+L;',
            f'APP_BASE_BUILD = {apk_build}L;',
            java_txt
        )
        java_txt = re.sub(
            r'APP_BASE_VERSION\s*=\s*"[^"]+";',
            f'APP_BASE_VERSION = "{version_str}";',
            java_txt
        )
        with open(main_java_path, "w", encoding="utf-8") as f:
            f.write(java_txt)

    gradle_path = os.path.join(root_dir, "android", "app", "build.gradle")
    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            gradle_txt = f.read()
        gradle_txt = re.sub(r'versionCode\s+[0-9]+', f'versionCode {apk_build}', gradle_txt)
        gradle_txt = re.sub(r'versionName\s+"[^"]+"', f'versionName "{version_str}"', gradle_txt)
        with open(gradle_path, "w", encoding="utf-8") as f:
            f.write(gradle_txt)


def _write_version_json(root_dir: str, static_dir: str, data: dict):
    """Writes version.json to repo root and mirrors it to static/version.json."""
    v_path = os.path.join(root_dir, "version.json")
    with open(v_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    if os.path.isdir(static_dir):
        shutil.copy2(v_path, os.path.join(static_dir, "version.json"))


def build_android_apk(update_version_metadata: bool = False, version: str = None, notes: str = None) -> str:
    """Compiles the standalone Android APK, copies it to Timecard-Tracker.apk, and returns its lowercase SHA-256."""
    root_dir = os.path.dirname(os.path.abspath(__file__))
    static_dir = os.path.join(root_dir, "static")
    v_path = os.path.join(root_dir, "version.json")
    data = {}
    if update_version_metadata and os.path.exists(v_path):
        try:
            with open(v_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
        if version:
            data["version"] = version.strip()
        if notes:
            data["release_notes"] = notes.strip()
        if "build" in data:
            data["apk_build"] = int(data["build"])
            _sync_native_apk_version_files(root_dir, str(data.get("version", "2.5.2")), data["apk_build"])
            _write_version_json(root_dir, static_dir, data)

    sync_android_assets()
    android_dir = os.path.join(root_dir, "android")

    gradlew_bat = os.path.join(android_dir, "gradlew.bat")
    gradlew_sh = os.path.join(android_dir, "gradlew")
    if os.name == "nt" and os.path.exists(gradlew_bat):
        gradle_cmd = gradlew_bat
    elif os.name != "nt" and os.path.exists(gradlew_sh):
        gradle_cmd = gradlew_sh
    else:
        gradle_cmd = shutil.which("gradle")
    if not gradle_cmd:
        user_home = os.path.expanduser("~")
        ext = "gradle.bat" if os.name == "nt" else "gradle"
        candidates = glob.glob(os.path.join(user_home, ".gradle", "wrapper", "dists", "gradle-8.*", "*", "gradle-*", "bin", ext))
        if candidates:
            gradle_cmd = sorted(candidates)[-1]

    if not gradle_cmd:
        print("Error: Could not locate Gradle executable.")
        sys.exit(1)

    print(f"Building standalone Android APK using {gradle_cmd}...")
    subprocess.run([gradle_cmd, "assembleDebug", "--console=plain"], cwd=android_dir, check=True)

    built_apk = os.path.join(android_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    dest_apk = os.path.join(root_dir, "Timecard-Tracker.apk")
    if not os.path.exists(built_apk):
        raise FileNotFoundError(f"Expected built APK not found at {built_apk}")

    shutil.copy2(built_apk, dest_apk)
    apk_sha256 = compute_sha256(dest_apk)
    size_mb = os.path.getsize(dest_apk) / (1024 * 1024)
    print(f"\nSUCCESS! Standalone Android APK built at:\n  {dest_apk} ({size_mb:.2f} MB, sha256={apk_sha256})")

    if update_version_metadata and data:
        data["apk_sha256"] = apk_sha256
        _write_version_json(root_dir, static_dir, data)

    return apk_sha256


def purge_jsdelivr_cdn(repo: str = None):
    """Purges jsDelivr edge cache for version.json, static/ota_bundle.zip, and Timecard-Tracker.apk."""
    import urllib.request

    root_dir = os.path.dirname(os.path.abspath(__file__))
    v_path = os.path.join(root_dir, "version.json")
    ver = "2.5.2"
    if not repo and os.path.exists(v_path):
        try:
            with open(v_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
                repo = meta.get("github_repo")
                ver = meta.get("version", ver)
        except Exception:
            pass
    repo = repo or "solotreason/TvC"

    urls = [
        f"https://purge.jsdelivr.net/gh/{repo}@main/version.json",
        f"https://purge.jsdelivr.net/gh/{repo}@main/static/ota_bundle.zip",
        f"https://purge.jsdelivr.net/gh/{repo}@main/Timecard-Tracker.apk",
    ]
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": f"TvC-Packer/{ver}"})
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                print(f"  Purged CDN cache: {url} (HTTP {resp.status})")
        except Exception as e:
            print(f"  Note: Could not purge {url}: {e}")


def pack_ota(version: str = None, notes: str = None, build_apk: bool = False):
    """Packs static/ into static/ota_bundle.zip (optionally compiling Timecard-Tracker.apk) and updates version.json with SHA-256 digests."""
    root_dir = os.path.dirname(os.path.abspath(__file__))
    static_dir = os.path.join(root_dir, "static")
    v_path = os.path.join(root_dir, "version.json")

    data = {}
    if os.path.exists(v_path):
        try:
            with open(v_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}

    if version:
        data["version"] = version.strip()
    elif "version" not in data:
        data["version"] = "2.5.2"

    today_prefix = time.strftime("%Y%m%d")
    prev_build_int = 0
    try:
        prev_build_int = int(data.get("build", 0))
    except Exception:
        prev_build_int = 0

    prev_build = str(prev_build_int) if prev_build_int > 0 else ""
    if prev_build.startswith(today_prefix):
        try:
            seq = int(prev_build[len(today_prefix):]) + 1
            candidate_build = int(f"{today_prefix}{seq:02d}")
        except Exception:
            candidate_build = int(f"{today_prefix}01")
    else:
        candidate_build = int(f"{today_prefix}01")

    data["build"] = max(candidate_build, prev_build_int + 1)

    if notes:
        data["release_notes"] = notes.strip()
    elif "release_notes" not in data:
        data["release_notes"] = "Performance improvements and bug fixes"

    data["github_repo"] = data.get("github_repo", "solotreason/TvC")
    data["ota_bundle_url"] = f"https://raw.githubusercontent.com/{data['github_repo']}/main/static/ota_bundle.zip"
    data["apk_url"] = f"https://raw.githubusercontent.com/{data['github_repo']}/main/Timecard-Tracker.apk"
    data["min_apk_version"] = data.get("min_apk_version", 1)

    apk_path = os.path.join(root_dir, "Timecard-Tracker.apk")
    if build_apk:
        data["apk_build"] = data["build"]
    else:
        # Web-only OTA: preserve existing apk_build & apk_sha256 so devices do NOT get prompted to reinstall APK
        data["apk_build"] = int(data.get("apk_build", data["build"]))
        if not data.get("apk_sha256") and os.path.exists(apk_path):
            data["apk_sha256"] = compute_sha256(apk_path)

    # Synchronize version & build numbers in index.html and README.md
    index_html_path = os.path.join(static_dir, "index.html")
    if os.path.exists(index_html_path):
        with open(index_html_path, "r", encoding="utf-8") as f:
            html_txt = f.read()
        html_txt = re.sub(
            r'(<span[^>]*id="otaModalVersion"[^>]*>)v?[0-9.]+(</span>)',
            rf'\g<1>v{data["version"]}\g<2>',
            html_txt
        )
        html_txt = re.sub(
            r'(<span[^>]*id="otaModalBuild"[^>]*>)[0-9]+(</span>)',
            rf'\g<1>{data["build"]}\g<2>',
            html_txt
        )
        html_txt = re.sub(
            r'(<span[^>]*id="otaModalApkBuild"[^>]*>)[0-9]+(</span>)',
            rf'\g<1>{data["apk_build"]}\g<2>',
            html_txt
        )
        with open(index_html_path, "w", encoding="utf-8") as f:
            f.write(html_txt)

    readme_path = os.path.join(root_dir, "README.md")
    if os.path.exists(readme_path):
        with open(readme_path, "r", encoding="utf-8") as f:
            readme_txt = f.read()
        readme_txt = re.sub(
            r'\*\*Version [0-9.]+ \(Build [0-9]+\)\*\*',
            f'**Version {data["version"]} (Build {data["build"]})**',
            readme_txt
        )
        with open(readme_path, "w", encoding="utf-8") as f:
            f.write(readme_txt)

    # Only bump native APP_BASE_BUILD / versionCode and recompile APK when --build-apk is requested
    if build_apk:
        _sync_native_apk_version_files(root_dir, data["version"], data["apk_build"])
        _write_version_json(root_dir, static_dir, data)
        data["apk_sha256"] = build_android_apk(update_version_metadata=False)

    # Write version metadata prior to creating static/ota_bundle.zip
    _write_version_json(root_dir, static_dir, data)

    zip_dest = os.path.join(static_dir, "ota_bundle.zip")
    if os.path.exists(zip_dest):
        os.remove(zip_dest)

    with zipfile.ZipFile(zip_dest, "w", zipfile.ZIP_DEFLATED) as zf:
        for fname in sorted(os.listdir(static_dir)):
            if fname.endswith(".zip"):
                continue
            fpath = os.path.join(static_dir, fname)
            if os.path.isfile(fpath):
                zf.write(fpath, arcname=fname)

    # Compute SHA-256 of static/ota_bundle.zip and persist final version.json
    data["ota_sha256"] = compute_sha256(zip_dest)
    _write_version_json(root_dir, static_dir, data)

    size_kb = os.path.getsize(zip_dest) / 1024
    pkg_mode = "OTA + Native APK" if build_apk else "Web-Only OTA (Native APK preserved)"
    print(f"\n=======================================================")
    print(f"  {pkg_mode} Package Generated Successfully!")
    print(f"  Version:       v{data['version']} (Build {data['build']}, APK Build {data['apk_build']})")
    print(f"  Release Notes: {data['release_notes']}")
    print(f"  Bundle File:   {zip_dest} ({size_kb:.1f} KB)")
    print(f"  OTA SHA-256:   {data['ota_sha256']}")
    if data.get("apk_sha256"):
        print(f"  APK SHA-256:   {data['apk_sha256']}")
    print(f"  GitHub Repo:   https://github.com/{data['github_repo']}")
    print(f"-------------------------------------------------------")
    print(f"  To push live update to all devices:")
    print(f"    git add .")
    print(f"    git commit -m \"Release v{data['version']} (build {data['build']})\"")
    print(f"    git push origin main")
    print(f"    python run.py --purge-cdn")
    print(f"=======================================================\n")


def free_port(port: int):
    """Automatically stops any previous server instance occupying the target port on Windows."""
    if os.name != "nt":
        return
    try:
        out = subprocess.check_output(
            ["netstat", "-ano", "-p", "TCP"],
            text=True,
            stderr=subprocess.DEVNULL
        )
        current_pid = os.getpid()
        pids = set()
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 5 and parts[3] == "LISTENING" and parts[1].endswith(f":{port}"):
                try:
                    pid = int(parts[4])
                    if pid > 0 and pid != current_pid:
                        pids.add(pid)
                except ValueError:
                    pass
        for pid in pids:
            subprocess.run(
                ["taskkill", "/F", "/PID", str(pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )
    except Exception:
        pass


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main():
    parser = argparse.ArgumentParser(description="TvC - Timecard Viewer")
    parser.add_argument("--sync", action="store_true", help="Run Playwright sync now for all available weeks")
    parser.add_argument("--weeks", type=int, default=0, help="Max weeks to sync (0 = all available, default: 0)")
    parser.add_argument("--headless", action="store_true", help="Run browser in headless mode")
    parser.add_argument("--seed-sample", action="store_true", help="Seed database with sample data")
    parser.add_argument("--build-apk", action="store_true", help="Build standalone Android APK")
    parser.add_argument("--pack-ota", action="store_true", help="Package static/ for GitHub OTA live update (updates version.json & ota_bundle.zip)")
    parser.add_argument("--purge-cdn", action="store_true", help="Purge jsDelivr CDN cache for version.json and static/ota_bundle.zip")
    parser.add_argument("--ver", type=str, default=None, help="Set version string for OTA package (e.g. 2.4.1)")
    parser.add_argument("--notes", type=str, default=None, help="Set release notes description for OTA package")
    parser.add_argument("--port", type=int, default=8000, help="Web server port (default: 8000)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Host binding (default: 0.0.0.0 for LAN access)")

    args = parser.parse_args()

    if args.pack_ota:
        pack_ota(version=args.ver, notes=args.notes, build_apk=args.build_apk)
        if args.purge_cdn:
            purge_jsdelivr_cdn()
        return

    if args.build_apk:
        build_android_apk(update_version_metadata=True, version=args.ver, notes=args.notes)
        if args.purge_cdn:
            purge_jsdelivr_cdn()
        return

    if args.purge_cdn:
        purge_jsdelivr_cdn()
        return

    if args.seed_sample:
        seed_sample()
        return

    if args.sync:
        print("Starting Playwright sync for all available weeks & Time Off Viewer...")
        res = run_timecard_sync(max_weeks=args.weeks, headless=args.headless, sync_timeoff=True)
        if res.get("success"):
            print(f"Sync complete! {res.get('weeks_synced')} week(s) & entitlements saved.")
        else:
            print(f"Sync failed: {res.get('error')}")
        return

    init_db()
    # Always start python run.py in a completely fresh, logged-out state with 0 previous records
    clear_all_data()
    logout_portal_session()
    free_port(args.port)

    local_ip = get_local_ip()
    print(f"\n=======================================================")
    print(f"  TvC - Timecard Viewer is running!")
    print(f"  PC / Mac:      http://localhost:{args.port}")
    if local_ip != "127.0.0.1":
        print(f"  iPhone / iPad: http://{local_ip}:{args.port}")
    print(f"=======================================================\n")
    uvicorn.run("backend.app:app", host=args.host, port=args.port, reload=False)


if __name__ == "__main__":
    main()
