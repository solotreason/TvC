"""
FastAPI application for Timecard Tracker & Time Off Auditor.
Provides REST endpoints and serves the modern dashboard frontend.
"""
import os
import json
import threading
from contextlib import asynccontextmanager
from typing import Optional
from fastapi import FastAPI, BackgroundTasks, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from .database import (
    init_db, get_all_weeks, get_active_and_archived_weeks, get_week_details,
    get_all_corrections, get_latest_sync_status, get_entitlements, get_all_audit_logs,
    clear_all_data, load_archive_vault, ARCHIVE_FILE
)
from .mongo_vault import sync_cloud_archive
from .scraper import run_timecard_sync, check_auth_status, launch_login_flow, logout_portal_session


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(title="TvC - Timecard Viewer API", version="2.4.6", lifespan=lifespan)


@app.middleware("http")
async def add_no_cache_headers(request, call_next):
    response = await call_next(request)
    if request.url.path == "/" or request.url.path.startswith("/static/"):
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    return response

STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")
os.makedirs(STATIC_DIR, exist_ok=True)

sync_lock = threading.Lock()
sync_in_progress = False
sync_state = {
    "percent": 0,
    "stage": "Idle",
    "message": "",
    "requires_login": False,
    "new_timecards": []
}


class SyncRequest(BaseModel):
    max_weeks: int = 0  # 0 = All available weeks (8-10 weeks)
    headless: bool = True  # Run completely in background by default
    sync_timeoff: bool = True


@app.get("/api/weeks")
def list_weeks():
    split = get_active_and_archived_weeks(limit=8)
    return {
        "weeks": split["weeks"],
        "archived_weeks": split["archived_weeks"]
    }


class ArchiveSyncPayload(BaseModel):
    emp_id: Optional[str] = None
    archived_weeks: Optional[list] = None
    week_details: Optional[dict] = None
    audit_logs: Optional[list] = None
    corrections: Optional[list] = None


@app.post("/api/archive/sync")
def sync_archive_vault(payload: ArchiveSyncPayload, background_tasks: BackgroundTasks, blocking: bool = False):
    """
    Receives archived weeks, punch audits, and corrections from clients, merges locally in <2ms,
    and schedules non-blocking synchronization with the MongoDB Cloud Vault so the UI never waits.
    """
    current_vault = load_archive_vault()
    emp_id = payload.emp_id or current_vault.get("emp_id")
    if not emp_id:
        ent = get_entitlements()
        emp_id = (ent.get("profile") or {}).get("emp_id", "default_user")

    merged_map = {w["week_end_date"]: w for w in current_vault.get("archived_weeks", [])}
    for w in (payload.archived_weeks or []):
        if isinstance(w, dict) and w.get("week_end_date"):
            merged_map[w["week_end_date"]] = w

    for w in get_all_weeks():
        if isinstance(w, dict) and w.get("week_end_date") and w["week_end_date"] not in merged_map:
            merged_map[w["week_end_date"]] = w

    sorted_weeks = sorted(merged_map.values(), key=lambda x: x.get("week_end_date", ""), reverse=True)
    merged_details = current_vault.get("week_details", {})
    if payload.week_details:
        merged_details.update(payload.week_details)
    for wed in list(merged_map.keys()):
        if wed not in merged_details:
            det = get_week_details(wed)
            if det:
                merged_details[wed] = det

    # Gather local audit logs and corrections from SQLite as well
    local_audits = payload.audit_logs or []
    if not local_audits:
        local_audits = get_all_audit_logs()
    vault_audits = current_vault.get("audit_logs", [])
    merged_audits_dict = {}
    for a in vault_audits + local_audits:
        if isinstance(a, dict):
            k = f"{a.get('week_end_date')}|{a.get('date')}|{a.get('field_name')}|{a.get('old_value')}|{a.get('new_value')}"
            merged_audits_dict[k] = a
    all_merged_audits = list(merged_audits_dict.values())

    local_corrs = payload.corrections or []
    if not local_corrs:
        local_corrs = get_all_corrections()
    vault_corrs = current_vault.get("corrections", [])
    merged_corrs_dict = {}
    for c in vault_corrs + local_corrs:
        if isinstance(c, dict):
            k = f"{c.get('timestamp')}|{c.get('item')}|{c.get('old_val')}|{c.get('new_val')}"
            merged_corrs_dict[k] = c
    all_merged_corrs = list(merged_corrs_dict.values())

    archive_data = {
        "emp_id": emp_id,
        "archived_weeks": sorted_weeks,
        "week_details": merged_details,
        "audit_logs": all_merged_audits,
        "corrections": all_merged_corrs
    }

    try:
        os.makedirs(os.path.dirname(ARCHIVE_FILE), exist_ok=True)
        with open(ARCHIVE_FILE, "w", encoding="utf-8") as f:
            json.dump(archive_data, f, indent=2)
    except Exception:
        pass

    # Non-blocking cloud synchronization
    if blocking:
        cloud_result = sync_cloud_archive(
            emp_id=emp_id,
            local_archived_weeks=sorted_weeks,
            local_week_details=merged_details,
            local_audit_logs=all_merged_audits,
            local_corrections=all_merged_corrs
        )
        cloud_synced = cloud_result.get("success", False)
    else:
        background_tasks.add_task(
            sync_cloud_archive,
            emp_id=emp_id,
            local_archived_weeks=sorted_weeks,
            local_week_details=merged_details,
            local_audit_logs=all_merged_audits,
            local_corrections=all_merged_corrs
        )
        cloud_synced = True

    return {
        "success": True,
        "emp_id": emp_id,
        "cloud_synced": cloud_synced,
        "total_archived_weeks": len(sorted_weeks),
        "archived_weeks": sorted_weeks,
        "audit_logs": all_merged_audits,
        "corrections": all_merged_corrs
    }


@app.get("/api/archive")
def list_archived_weeks():
    split = get_active_and_archived_weeks(limit=8)
    return {"archived_weeks": split["archived_weeks"], "active_week_limit": 8}


@app.get("/api/weeks/{week_end_date}")
def get_week(week_end_date: str):
    data = get_week_details(week_end_date)
    if not data:
        raise HTTPException(status_code=404, detail="Week not found")

    total_st_hrs = 0.0
    total_ot_hrs = 0.0
    total_dt_hrs = 0.0
    total_st_amt = 0.0
    total_ot_amt = 0.0
    total_dt_amt = 0.0
    total_late_shifts = 0

    for s in data.get("shifts") or []:
        if s.get("is_late"):
            total_late_shifts += 1
        for det in s.get("details", []):
            total_st_hrs += det.get("st_hrs", 0.0)
            total_ot_hrs += det.get("ot_hrs", 0.0)
            total_dt_hrs += det.get("dt_hrs", 0.0)
            total_st_amt += det.get("st_amt", 0.0)
            total_ot_amt += det.get("ot_amt", 0.0)
            total_dt_amt += det.get("dt_amt", 0.0)

    data["totals"] = {
        "st_hrs": round(total_st_hrs, 2),
        "ot_hrs": round(total_ot_hrs, 2),
        "dt_hrs": round(total_dt_hrs, 2),
        "st_amt": round(total_st_amt, 2),
        "ot_amt": round(total_ot_amt, 2),
        "dt_amt": round(total_dt_amt, 2),
        "late_count": total_late_shifts
    }

    return data


@app.get("/api/timeoff")
def get_timeoff_data():
    return get_entitlements()


@app.get("/api/audit-history")
def get_audit_history():
    logs = get_all_audit_logs()
    # Also include archived audit logs from permanent vault
    vault = load_archive_vault()
    vault_audits = vault.get("audit_logs", [])
    if vault_audits:
        seen = {f"{l.get('week_end_date')}|{l.get('date')}|{l.get('field_name')}|{l.get('old_value')}|{l.get('new_value')}": l for l in logs}
        for a in vault_audits:
            k = f"{a.get('week_end_date')}|{a.get('date')}|{a.get('field_name')}|{a.get('old_value')}|{a.get('new_value')}"
            if k not in seen:
                seen[k] = a
        logs = list(seen.values())
        logs.sort(key=lambda x: str(x.get("detected_at") or x.get("date") or ""), reverse=True)
    return {"audit_logs": logs}


@app.get("/api/corrections")
def list_corrections():
    corrections = get_all_corrections()
    # Also include archived corrections from permanent vault
    vault = load_archive_vault()
    vault_corrs = vault.get("corrections", [])
    if vault_corrs:
        seen = {f"{c.get('timestamp')}|{c.get('item')}|{c.get('old_val')}|{c.get('new_val')}": c for c in corrections}
        for c in vault_corrs:
            k = f"{c.get('timestamp')}|{c.get('item')}|{c.get('old_val')}|{c.get('new_val')}"
            if k not in seen:
                seen[k] = c
        corrections = list(seen.values())
        corrections.sort(key=lambda x: str(x.get("timestamp") or ""), reverse=True)
    return {"corrections": corrections}


@app.get("/api/auth/status")
def auth_status():
    """Checks if active login session exists in .portal_session."""
    return check_auth_status()


def _execute_login():
    global sync_in_progress, sync_state
    with sync_lock:
        sync_in_progress = True
        update_sync_progress(10, "Awaiting Login", "Opening browser window... Please complete your portal login/2FA.", True)
        try:
            res = launch_login_flow(timeout_seconds=180)
            if res.get("success"):
                # On first login (no weeks synced yet), automatically sync all 8+ weeks
                if len(get_all_weeks()) == 0:
                    update_sync_progress(20, "Logged In — Syncing All Weeks", "Login complete! Automatically syncing all 8+ weeks...", False)
                    run_timecard_sync(
                        max_weeks=0,
                        headless=True,
                        sync_timeoff=True,
                        progress_callback=update_sync_progress
                    )
                else:
                    update_sync_progress(100, "Logged In", "Session saved! You are now authenticated.", False)
            else:
                update_sync_progress(0, "Login Incomplete", res.get("error", "Login incomplete"), True)
        finally:
            sync_in_progress = False


class LogoutRequest(BaseModel):
    clear_data: bool = True


@app.post("/api/auth/login")
def start_login_session(background_tasks: BackgroundTasks):
    """Launches visible browser window so user can authenticate upfront."""
    global sync_in_progress
    if sync_in_progress:
        return JSONResponse(status_code=409, content={"message": "A browser task is already in progress"})
    background_tasks.add_task(_execute_login)
    return {"message": "Login window launched. Complete 2FA in the browser."}


@app.post("/api/auth/logout")
def logout_session(req: Optional[LogoutRequest] = None):
    """Logs out of portal session and optionally clears synced local data."""
    global sync_in_progress
    if sync_in_progress:
        return JSONResponse(status_code=409, content={"message": "Cannot log out while a sync is in progress"})
    res = logout_portal_session()
    should_clear = True if req is None else req.clear_data
    if should_clear:
        clear_all_data()
    update_sync_progress(0, "Logged Out", "Logged out of portal session.", False)
    return res


def update_sync_progress(percent: int, stage: str, message: str, requires_login: bool = False):
    global sync_state
    sync_state["percent"] = percent
    sync_state["stage"] = stage
    sync_state["message"] = message
    sync_state["requires_login"] = requires_login


@app.get("/api/status")
def sync_status():
    latest = get_latest_sync_status()
    return {
        "sync_in_progress": sync_in_progress,
        "percent": sync_state.get("percent", 0),
        "stage": sync_state.get("stage", "Idle"),
        "message": sync_state.get("message", ""),
        "requires_login": sync_state.get("requires_login", False),
        "new_timecards": sync_state.get("new_timecards", []),
        "latest_log": latest
    }


def _execute_sync(max_weeks: int, headless: bool, sync_timeoff: bool):
    global sync_in_progress, sync_state
    with sync_lock:
        sync_in_progress = True
        sync_state["new_timecards"] = []
        update_sync_progress(5, "Starting", "Initializing background sync engine...")
        try:
            res = run_timecard_sync(
                max_weeks=max_weeks,
                headless=headless,
                sync_timeoff=sync_timeoff,
                progress_callback=update_sync_progress
            )
            if isinstance(res, dict) and res.get("new_timecards"):
                sync_state["new_timecards"] = res["new_timecards"]
        except Exception as e:
            update_sync_progress(0, "Error", str(e), False)
        finally:
            sync_in_progress = False


@app.post("/api/sync")
def trigger_sync(req: SyncRequest, background_tasks: BackgroundTasks):
    global sync_in_progress
    if sync_in_progress:
        return JSONResponse(status_code=409, content={"message": "A sync is already in progress"})

    background_tasks.add_task(_execute_sync, req.max_weeks, req.headless, req.sync_timeoff)
    return {
        "message": f"Sync started in background ({'Headless' if req.headless else 'Headed'})",
        "headless": req.headless,
        "max_weeks": req.max_weeks
    }


@app.get("/api/ota/status")
def get_ota_status():
    v_file = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "version.json")
    if os.path.exists(v_file):
        try:
            with open(v_file, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {
        "version": "2.4.5",
        "build": 2026111302,
        "github_repo": "solotreason/TvC",
        "release_notes": "OTA Live Update Active"
    }


@app.post("/api/ota/check")
def check_ota_updates():
    try:
        import urllib.request
        req = urllib.request.Request(
            "https://cdn.jsdelivr.net/gh/solotreason/TvC@main/version.json",
            headers={"User-Agent": "TvC-Web/2.4.5"}
        )
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            remote = json.loads(resp.read().decode("utf-8"))
            local_meta = get_ota_status()
            remote_build = int(remote.get("build", 0))
            local_build = int(local_meta.get("build", 0))
            if remote_build > local_build:
                return {
                    "status": "OK",
                    "update_available": True,
                    "version": remote.get("version"),
                    "build": remote_build,
                    "notes": remote.get("release_notes", "")
                }
            return {
                "status": "OK",
                "up_to_date": True,
                "version": local_meta.get("version", "2.4.5"),
                "build": local_build
            }
    except Exception:
        local_meta = get_ota_status()
        return {
            "status": "OK",
            "up_to_date": True,
            "version": local_meta.get("version", "2.4.5"),
            "build": int(local_meta.get("build", 0))
        }


@app.get("/sw.js")
def serve_service_worker():
    sw_file = os.path.join(STATIC_DIR, "sw.js")
    if os.path.exists(sw_file):
        return FileResponse(
            sw_file,
            media_type="application/javascript",
            headers={
                "Service-Worker-Allowed": "/",
                "Cache-Control": "no-cache, must-revalidate"
            }
        )
    raise HTTPException(status_code=404, detail="Service worker not found")


# Mount static assets
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/")
def serve_index():
    index_file = os.path.join(STATIC_DIR, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"message": "Timecard Tracker API is running."}
