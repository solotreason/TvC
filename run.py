"""
Runner script for Timecard Tracker & Time Off Auditor.
Usage:
  python run.py               -> Starts the web dashboard at http://localhost:8000 (clean state)
  python run.py --sync        -> Runs the Playwright sync directly from CLI (all weeks)
  python run.py --seed-sample -> Seeds database with the provided sample HTML fixtures
  python run.py --build-apk   -> Syncs web assets & compiles standalone Android APK (Timecard-Tracker.apk)
"""
import sys
import os
import shutil
import glob
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
    """Copies static/ web assets into android/app/src/main/assets/www/."""
    root_dir = os.path.dirname(os.path.abspath(__file__))
    static_dir = os.path.join(root_dir, "static")
    assets_www = os.path.join(root_dir, "android", "app", "src", "main", "assets", "www")
    os.makedirs(assets_www, exist_ok=True)

    # Remove legacy unused seed_data.js if present
    for stale in [os.path.join(static_dir, "seed_data.js"), os.path.join(assets_www, "seed_data.js")]:
        if os.path.exists(stale):
            try:
                os.remove(stale)
            except Exception:
                pass

    for fname in os.listdir(static_dir):
        src = os.path.join(static_dir, fname)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(assets_www, fname))
    print(f"Synced clean web assets to {assets_www}")


def build_android_apk():
    """Compiles the standalone Android APK and copies it to Timecard-Tracker.apk."""
    sync_android_assets()
    root_dir = os.path.dirname(os.path.abspath(__file__))
    android_dir = os.path.join(root_dir, "android")

    gradlew_bat = os.path.join(android_dir, "gradlew.bat")
    gradle_cmd = gradlew_bat if os.path.exists(gradlew_bat) else shutil.which("gradle")
    if not gradle_cmd:
        user_home = os.path.expanduser("~")
        candidates = glob.glob(os.path.join(user_home, ".gradle", "wrapper", "dists", "gradle-8.*", "*", "gradle-*", "bin", "gradle.bat"))
        if candidates:
            gradle_cmd = sorted(candidates)[-1]

    if not gradle_cmd:
        print("Error: Could not locate Gradle executable.")
        sys.exit(1)

    print(f"Building standalone Android APK using {gradle_cmd}...")
    subprocess.run([gradle_cmd, "assembleDebug", "--console=plain"], cwd=android_dir, check=True)

    built_apk = os.path.join(android_dir, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    dest_apk = os.path.join(root_dir, "Timecard-Tracker.apk")
    if os.path.exists(built_apk):
        shutil.copy2(built_apk, dest_apk)
        size_mb = os.path.getsize(dest_apk) / (1024 * 1024)
        print(f"\nSUCCESS! Standalone Android APK built at:\n  {dest_apk} ({size_mb:.2f} MB)")


def pack_ota(version: str = None, notes: str = None):
    """Packs static/ directory into static/ota_bundle.zip and updates version.json."""
    import zipfile
    import json
    import time

    root_dir = os.path.dirname(os.path.abspath(__file__))
    static_dir = os.path.join(root_dir, "static")
    v_path = os.path.join(root_dir, "version.json")

    data = {}
    if os.path.exists(v_path):
        try:
            with open(v_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            pass

    if version:
        data["version"] = version.strip()
    elif "version" not in data:
        data["version"] = "2.4.1"

    today_prefix = time.strftime("%Y%m%d")
    prev_build = str(data.get("build", ""))
    if prev_build.startswith(today_prefix):
        try:
            seq = int(prev_build[len(today_prefix):]) + 1
            data["build"] = int(f"{today_prefix}{seq:02d}")
        except Exception:
            data["build"] = int(f"{today_prefix}01")
    else:
        data["build"] = int(f"{today_prefix}01")

    if notes:
        data["release_notes"] = notes.strip()
    elif "release_notes" not in data:
        data["release_notes"] = "Performance improvements and bug fixes"

    data["github_repo"] = data.get("github_repo", "solotreason/TvC")
    data["ota_bundle_url"] = f"https://raw.githubusercontent.com/{data['github_repo']}/main/static/ota_bundle.zip"
    data["apk_url"] = f"https://github.com/{data['github_repo']}/releases/latest/download/Timecard-Tracker.apk"
    data["min_apk_version"] = data.get("min_apk_version", 1)

    # Save version.json to root
    with open(v_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

    # Also copy to static/version.json
    shutil.copy2(v_path, os.path.join(static_dir, "version.json"))

    # Sync web assets to android www
    sync_android_assets()

    # Create static/ota_bundle.zip
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

    size_kb = os.path.getsize(zip_dest) / 1024
    print(f"\n=======================================================")
    print(f"  OTA Package Generated Successfully!")
    print(f"  Version:       v{data['version']} (Build {data['build']})")
    print(f"  Release Notes: {data['release_notes']}")
    print(f"  Bundle File:   {zip_dest} ({size_kb:.1f} KB)")
    print(f"  GitHub Repo:   https://github.com/{data['github_repo']}")
    print(f"-------------------------------------------------------")
    print(f"  To push live update to all devices without APK reinstall:")
    print(f"    git add .")
    print(f"    git commit -m \"OTA update v{data['version']} (build {data['build']})\"")
    print(f"    git push origin main")
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
    parser.add_argument("--ver", type=str, default=None, help="Set version string for OTA package (e.g. 2.4.1)")
    parser.add_argument("--notes", type=str, default=None, help="Set release notes description for OTA package")
    parser.add_argument("--port", type=int, default=8000, help="Web server port (default: 8000)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Host binding (default: 0.0.0.0 for LAN access)")

    args = parser.parse_args()

    if args.pack_ota:
        pack_ota(version=args.ver, notes=args.notes)
        return

    if args.seed_sample:
        seed_sample()
        return

    if args.build_apk:
        build_android_apk()
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
