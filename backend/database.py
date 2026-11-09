"""
Database layer for Timecard Tracker using SQLite.
Stores weeks, shifts, shift breakdowns, corrections, time off entitlements,
and audit logs for tracking mid-week punch and pay changes.
"""
import sqlite3
import json
import os
from typing import Dict, Any, List, Optional

ACTIVE_WEEK_LIMIT = 8
DB_FILE = os.environ.get(
    "TIMECARD_DB_FILE",
    os.path.join(os.path.dirname(os.path.dirname(__file__)), "timecard.db")
)
ARCHIVE_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), "archive", "timecard_archive.json")


def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_FILE, timeout=15.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON;")
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    conn.execute("PRAGMA cache_size = -8000;")  # 8MB memory page cache for fast lookups
    conn.execute("PRAGMA temp_store = MEMORY;")
    return conn


def init_db():
    conn = get_connection()
    with conn:
        conn.executescript("""
        CREATE TABLE IF NOT EXISTS weeks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            week_end_date TEXT UNIQUE NOT NULL,
            status TEXT DEFAULT 'Final',
            gross_pay REAL DEFAULT 0.0,
            total_hours REAL DEFAULT 0.0,
            last_updated_source TEXT,
            synced_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS shifts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            week_end_date TEXT NOT NULL,
            date TEXT NOT NULL,
            day_name TEXT,
            pay_code TEXT,
            start_time_dec REAL,
            start_time_clock TEXT,
            is_late INTEGER DEFAULT 0,
            end_time_dec REAL,
            end_time_clock TEXT,
            meal_unpaid_hours REAL DEFAULT 0.0,
            pay_rate REAL DEFAULT 0.0,
            total_hours REAL DEFAULT 0.0,
            gross_pay REAL DEFAULT 0.0,
            UNIQUE(week_end_date, date),
            FOREIGN KEY (week_end_date) REFERENCES weeks(week_end_date) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS shift_details (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            shift_date TEXT NOT NULL,
            trans_type TEXT,
            op_type TEXT,
            center TEXT,
            sort TEXT,
            activity TEXT,
            rate REAL DEFAULT 0.0,
            st_hrs REAL DEFAULT 0.0,
            st_amt REAL DEFAULT 0.0,
            ot_hrs REAL DEFAULT 0.0,
            ot_amt REAL DEFAULT 0.0,
            dt_hrs REAL DEFAULT 0.0,
            dt_amt REAL DEFAULT 0.0,
            eq_type TEXT,
            griev_num TEXT,
            flat_amt REAL DEFAULT 0.0
        );

        CREATE TABLE IF NOT EXISTS corrections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT,
            card_type TEXT,
            action TEXT,
            item TEXT,
            old_val TEXT,
            new_val TEXT,
            edited_by TEXT,
            reason_code TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(timestamp, item, old_val, new_val, edited_by)
        );

        CREATE TABLE IF NOT EXISTS sync_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            finished_at TIMESTAMP,
            status TEXT NOT NULL,
            message TEXT,
            weeks_synced INTEGER DEFAULT 0
        );

        -- Audit log for detecting retroactive/mid-week punch or pay modifications
        CREATE TABLE IF NOT EXISTS shift_audit_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            week_end_date TEXT NOT NULL,
            date TEXT NOT NULL,
            field_name TEXT NOT NULL,
            old_value TEXT,
            new_value TEXT,
            diff_num REAL DEFAULT 0.0,
            detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        -- Entitlements Bank (Vacation, Sick, Option Week, Option Day, etc.)
        CREATE TABLE IF NOT EXISTS entitlements (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timeoff_type TEXT UNIQUE NOT NULL,
            title TEXT NOT NULL,
            available_time REAL DEFAULT 0.0,
            time_basis TEXT,
            next_earn_date TEXT,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS employee_profile (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            emp_id TEXT,
            emp_name TEXT,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        -- Performance Indexes
        CREATE INDEX IF NOT EXISTS idx_shifts_week ON shifts(week_end_date);
        CREATE INDEX IF NOT EXISTS idx_shift_details_date ON shift_details(shift_date);
        CREATE INDEX IF NOT EXISTS idx_shift_audit_date ON shift_audit_log(date);
        CREATE INDEX IF NOT EXISTS idx_shift_audit_week ON shift_audit_log(week_end_date);
        CREATE INDEX IF NOT EXISTS idx_corrections_time ON corrections(timestamp);
        """)
    conn.close()


def upsert_week(week_data: Dict[str, Any]):
    """Insert or update week record."""
    conn = get_connection()
    with conn:
        conn.execute("""
            INSERT INTO weeks (week_end_date, status, gross_pay, total_hours, last_updated_source, synced_at)
            VALUES (:week_end_date, :status, :gross_pay, :total_hours, :last_updated_source, CURRENT_TIMESTAMP)
            ON CONFLICT(week_end_date) DO UPDATE SET
                status = excluded.status,
                gross_pay = excluded.gross_pay,
                total_hours = excluded.total_hours,
                last_updated_source = excluded.last_updated_source,
                synced_at = CURRENT_TIMESTAMP
        """, week_data)
    conn.close()


def upsert_shift(shift_data: Dict[str, Any]) -> bool:
    """
    Insert or update daily shift record.
    Audits ONLY punch changes: Start Time, End Time, and Meal/Unpaid.
    Returns True if this shift represents a newly added daily timecard
    for a week that already had shift records in the database.
    """
    conn = get_connection()
    is_newly_added_timecard = False
    with conn:
        week_shift_count = conn.execute(
            "SELECT COUNT(*) FROM shifts WHERE week_end_date = ?",
            (shift_data["week_end_date"],)
        ).fetchone()[0]

        existing = conn.execute(
            "SELECT * FROM shifts WHERE week_end_date = ? AND date = ?",
            (shift_data["week_end_date"], shift_data["date"])
        ).fetchone()

        was_empty = (existing is None) or (
            existing["start_time_dec"] is None and (existing["total_hours"] or 0.0) <= 0.005
        )
        has_timecard_now = (shift_data.get("start_time_dec") is not None) or (
            (shift_data.get("total_hours") or 0.0) > 0.005
        )
        if week_shift_count > 0 and was_empty and has_timecard_now:
            is_newly_added_timecard = True

        if existing:
            # 1. Start Time check
            old_start_dec = existing["start_time_dec"]
            new_start_dec = shift_data.get("start_time_dec")
            if old_start_dec is not None and new_start_dec is not None:
                if abs(old_start_dec - new_start_dec) > 0.005:
                    old_clock = existing["start_time_clock"] or f"{old_start_dec:.2f}"
                    new_clock = shift_data.get("start_time_clock") or f"{new_start_dec:.2f}"
                    diff_mins = round((new_start_dec - old_start_dec) * 60)
                    conn.execute("""
                        INSERT INTO shift_audit_log (week_end_date, date, field_name, old_value, new_value, diff_num)
                        VALUES (?, ?, 'Start Time', ?, ?, ?)
                    """, (
                        shift_data["week_end_date"],
                        shift_data["date"],
                        f"{old_clock} ({old_start_dec:.2f})",
                        f"{new_clock} ({new_start_dec:.2f})",
                        diff_mins
                    ))

            # 2. End Time check
            old_end_dec = existing["end_time_dec"]
            new_end_dec = shift_data.get("end_time_dec")
            if old_end_dec is not None and new_end_dec is not None:
                if abs(old_end_dec - new_end_dec) > 0.005:
                    old_clock = existing["end_time_clock"] or f"{old_end_dec:.2f}"
                    new_clock = shift_data.get("end_time_clock") or f"{new_end_dec:.2f}"
                    diff_mins = round((new_end_dec - old_end_dec) * 60)
                    conn.execute("""
                        INSERT INTO shift_audit_log (week_end_date, date, field_name, old_value, new_value, diff_num)
                        VALUES (?, ?, 'End Time', ?, ?, ?)
                    """, (
                        shift_data["week_end_date"],
                        shift_data["date"],
                        f"{old_clock} ({old_end_dec:.2f})",
                        f"{new_clock} ({new_end_dec:.2f})",
                        diff_mins
                    ))

            # 3. Meal / Unpaid check (only when both old and new records are active punch cards)
            old_meal = existing["meal_unpaid_hours"] or 0.0
            new_meal = shift_data.get("meal_unpaid_hours") or 0.0
            if old_start_dec is not None and new_start_dec is not None and abs(old_meal - new_meal) > 0.005:
                diff_mins = round((new_meal - old_meal) * 60)
                conn.execute("""
                    INSERT INTO shift_audit_log (week_end_date, date, field_name, old_value, new_value, diff_num)
                    VALUES (?, ?, 'Meal/Unpaid', ?, ?, ?)
                """, (
                    shift_data["week_end_date"],
                    shift_data["date"],
                    f"{old_meal:.2f}h ({round(old_meal*60)}m)",
                    f"{new_meal:.2f}h ({round(new_meal*60)}m)",
                    diff_mins
                ))

        conn.execute("""
            INSERT INTO shifts (
                week_end_date, date, day_name, pay_code,
                start_time_dec, start_time_clock, is_late,
                end_time_dec, end_time_clock, meal_unpaid_hours,
                pay_rate, total_hours, gross_pay
            ) VALUES (
                :week_end_date, :date, :day_name, :pay_code,
                :start_time_dec, :start_time_clock, :is_late,
                :end_time_dec, :end_time_clock, :meal_unpaid_hours,
                :pay_rate, :total_hours, :gross_pay
            )
            ON CONFLICT(week_end_date, date) DO UPDATE SET
                day_name = excluded.day_name,
                pay_code = excluded.pay_code,
                start_time_dec = excluded.start_time_dec,
                start_time_clock = excluded.start_time_clock,
                is_late = excluded.is_late,
                end_time_dec = excluded.end_time_dec,
                end_time_clock = excluded.end_time_clock,
                meal_unpaid_hours = excluded.meal_unpaid_hours,
                pay_rate = excluded.pay_rate,
                total_hours = excluded.total_hours,
                gross_pay = excluded.gross_pay
        """, shift_data)
    conn.close()
    return is_newly_added_timecard


def replace_shift_details(shift_date: str, details_list: List[Dict[str, Any]]):
    """Replaces shift breakdowns for a specific date using a single batch insert."""
    conn = get_connection()
    with conn:
        conn.execute("DELETE FROM shift_details WHERE shift_date = ?", (shift_date,))
        if details_list:
            rows = [{**item, "shift_date": shift_date} for item in details_list]
            conn.executemany("""
                INSERT INTO shift_details (
                    shift_date, trans_type, op_type, center, sort,
                    activity, rate, st_hrs, st_amt, ot_hrs, ot_amt,
                    dt_hrs, dt_amt, eq_type, griev_num, flat_amt
                ) VALUES (
                    :shift_date, :trans_type, :op_type, :center, :sort,
                    :activity, :rate, :st_hrs, :st_amt, :ot_hrs, :ot_amt,
                    :dt_hrs, :dt_amt, :eq_type, :griev_num, :flat_amt
                )
            """, rows)
    conn.close()


def save_parsed_week(parsed: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Persists a parsed week summary, its shifts, shift details, and corrections.
    Returns any newly detected daily timecard shifts for notifications.
    """
    new_timecards: List[Dict[str, Any]] = []
    week_summary = parsed.get("week_summary") or {}
    week_end_date = week_summary.get("week_end_date", "unknown")
    if week_end_date == "unknown":
        return new_timecards

    upsert_week(week_summary)
    for shift in parsed.get("shifts", []):
        if upsert_shift(shift):
            new_timecards.append({
                "week_end_date": shift.get("week_end_date") or week_end_date,
                "date": shift.get("date"),
                "day_name": shift.get("day_name"),
                "start_time_clock": shift.get("start_time_clock"),
                "end_time_clock": shift.get("end_time_clock"),
                "total_hours": shift.get("total_hours", 0.0),
                "gross_pay": shift.get("gross_pay", 0.0)
            })
        if shift.get("details"):
            replace_shift_details(shift["date"], shift["details"])

    for corr in parsed.get("corrections", []):
        insert_correction(corr)

    return new_timecards


def insert_correction(corr_data: Dict[str, Any]):
    """Inserts a correction log if not already present."""
    conn = get_connection()
    with conn:
        conn.execute("""
            INSERT OR IGNORE INTO corrections (
                timestamp, card_type, action, item, old_val, new_val, edited_by, reason_code
            ) VALUES (
                :timestamp, :card_type, :action, :item, :old_val, :new_val, :edited_by, :reason_code
            )
        """, corr_data)
    conn.close()


def upsert_entitlements(entitlements_list: List[Dict[str, Any]], profile: Optional[Dict[str, Any]] = None):
    """Inserts or updates employee leave entitlements bank."""
    conn = get_connection()
    with conn:
        if profile and profile.get("emp_id"):
            conn.execute("""
                INSERT INTO employee_profile (id, emp_id, emp_name, updated_at)
                VALUES (1, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET
                    emp_id = excluded.emp_id,
                    emp_name = excluded.emp_name,
                    updated_at = CURRENT_TIMESTAMP
            """, (profile.get("emp_id"), profile.get("emp_name")))

        for item in entitlements_list:
            conn.execute("""
                INSERT INTO entitlements (timeoff_type, title, available_time, time_basis, next_earn_date, updated_at)
                VALUES (:timeoff_type, :title, :available_time, :time_basis, :next_earn_date, CURRENT_TIMESTAMP)
                ON CONFLICT(timeoff_type) DO UPDATE SET
                    title = excluded.title,
                    available_time = excluded.available_time,
                    time_basis = excluded.time_basis,
                    next_earn_date = excluded.next_earn_date,
                    updated_at = CURRENT_TIMESTAMP
            """, item)
    conn.close()


def get_entitlements() -> Dict[str, Any]:
    conn = get_connection()
    prof_cur = conn.execute("SELECT emp_id, emp_name, updated_at FROM employee_profile WHERE id = 1")
    prof = prof_cur.fetchone()

    ent_cur = conn.execute("SELECT * FROM entitlements ORDER BY id ASC")
    entitlements = [dict(r) for r in ent_cur.fetchall()]
    conn.close()

    return {
        "profile": dict(prof) if prof else None,
        "entitlements": entitlements
    }


def get_all_audit_logs() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.execute("SELECT * FROM shift_audit_log ORDER BY id DESC LIMIT 100")
    rows = [dict(r) for r in cursor.fetchall()]
    conn.close()
    return rows


def log_sync_start() -> int:
    conn = get_connection()
    with conn:
        cursor = conn.execute("INSERT INTO sync_logs (status, message) VALUES ('IN_PROGRESS', 'Sync started')")
        sync_id = cursor.lastrowid
    conn.close()
    return sync_id


def log_sync_complete(sync_id: int, status: str, message: str, weeks_synced: int = 0):
    conn = get_connection()
    with conn:
        conn.execute("""
            UPDATE sync_logs
            SET finished_at = CURRENT_TIMESTAMP, status = ?, message = ?, weeks_synced = ?
            WHERE id = ?
        """, (status, message, weeks_synced, sync_id))
    conn.close()


def get_all_weeks() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.execute("SELECT * FROM weeks ORDER BY week_end_date DESC")
    rows = [dict(r) for r in cursor.fetchall()]
    conn.close()
    return rows


def get_active_and_archived_weeks(limit: int = ACTIVE_WEEK_LIMIT) -> Dict[str, List[Dict[str, Any]]]:
    """
    Splits all stored weeks into the newest `limit` (8) active weeks
    and monthly historical archive weeks (all weeks from previous months,
    finalized weeks, and weeks beyond the 8-week active cap), persisting
    them cumulatively to archive/timecard_archive.json so past months are never lost.
    """
    all_weeks = get_all_weeks()
    active_weeks = all_weeks[:limit]
    newest_month = all_weeks[0]["week_end_date"][:7] if all_weeks else ""
    current_archived = [
        w for idx, w in enumerate(all_weeks)
        if idx >= limit
        or (w.get("week_end_date", "")[:7] < newest_month)
        or (w.get("status") != "Pending" and len(all_weeks) > 1)
    ]

    # Cumulatively merge with persistent archive file so past months (even if purged from DB) are preserved
    saved_archive = load_archive_vault()
    merged_archived_map = {w["week_end_date"]: w for w in saved_archive.get("archived_weeks", [])}
    for w in current_archived:
        merged_archived_map[w["week_end_date"]] = w

    sorted_archived = sorted(merged_archived_map.values(), key=lambda x: x["week_end_date"], reverse=True)

    # Persist newly archived weeks without thrashing disk or re-querying on every read
    saved_details = saved_archive.get("week_details", {})
    new_archived_weeks = [w for w in current_archived if w["week_end_date"] not in saved_details]
    if new_archived_weeks and "test_timecard.db" not in DB_FILE:
        try:
            os.makedirs(os.path.dirname(ARCHIVE_FILE), exist_ok=True)
            for w in new_archived_weeks:
                wed = w["week_end_date"]
                det = get_week_details(wed)
                if det:
                    saved_details[wed] = det
            archive_payload = {
                "archived_weeks": sorted_archived,
                "week_details": saved_details
            }
            with open(ARCHIVE_FILE, "w", encoding="utf-8") as f:
                json.dump(archive_payload, f, indent=2)
        except Exception:
            pass

    return {
        "weeks": active_weeks,
        "archived_weeks": sorted_archived
    }


def load_archive_vault() -> Dict[str, Any]:
    """
    Reads the permanent archive vault file. If not present or running on a new machine,
    checks MongoDB cloud vault to restore history automatically.
    """
    if os.path.exists(ARCHIVE_FILE):
        try:
            with open(ARCHIVE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    return data
        except Exception:
            pass

    if "test_timecard.db" not in DB_FILE:
        try:
            from .mongo_vault import fetch_cloud_archive
            ent = get_entitlements()
            emp_id = (ent.get("profile") or {}).get("emp_id")
            if emp_id:
                cloud_doc = fetch_cloud_archive(emp_id)
                if cloud_doc and cloud_doc.get("archived_weeks"):
                    os.makedirs(os.path.dirname(ARCHIVE_FILE), exist_ok=True)
                    with open(ARCHIVE_FILE, "w", encoding="utf-8") as f:
                        json.dump(cloud_doc, f, indent=2)
                    return cloud_doc
        except Exception:
            pass

    return {"archived_weeks": [], "week_details": {}, "audit_logs": [], "corrections": []}


def trigger_full_vault_sync(emp_id: Optional[str] = None) -> Dict[str, Any]:
    """
    Cumulatively gathers all weeks, week details, punch audits, and corrections
    from SQLite and the local archive vault, writes them to archive/timecard_archive.json,
    and bi-directionally synchronizes with MongoDB Atlas Cloud Database for emp_id.
    """
    current_vault = load_archive_vault()

    if not emp_id:
        emp_id = current_vault.get("emp_id")
    if not emp_id:
        try:
            ent = get_entitlements()
            emp_id = (ent.get("profile") or {}).get("emp_id")
        except Exception:
            pass
    if not emp_id:
        emp_id = "default_user"

    # Merge weeks (SQLite + Vault)
    all_sqlite_weeks = get_all_weeks()
    weeks_map: Dict[str, Dict[str, Any]] = {}
    for w in current_vault.get("archived_weeks", []):
        if isinstance(w, dict) and w.get("week_end_date"):
            weeks_map[w["week_end_date"]] = w
    for w in all_sqlite_weeks:
        if isinstance(w, dict) and w.get("week_end_date"):
            weeks_map[w["week_end_date"]] = w

    sorted_weeks = sorted(weeks_map.values(), key=lambda x: str(x.get("week_end_date", "")), reverse=True)

    # Merge week details
    saved_details = dict(current_vault.get("week_details", {}))
    for wed in list(weeks_map.keys()):
        if wed not in saved_details:
            det = get_week_details(wed)
            if det:
                saved_details[wed] = det

    # Merge punch audits
    sqlite_audits = get_all_audit_logs()
    vault_audits = current_vault.get("audit_logs", [])
    audits_map: Dict[str, Dict[str, Any]] = {}
    for a in vault_audits + sqlite_audits:
        if isinstance(a, dict):
            k = f"{a.get('week_end_date')}|{a.get('date')}|{a.get('field_name')}|{a.get('old_value')}|{a.get('new_value')}"
            audits_map[k] = a
    all_merged_audits = list(audits_map.values())
    all_merged_audits.sort(key=lambda x: str(x.get("detected_at") or x.get("date") or ""), reverse=True)

    # Merge corrections
    sqlite_corrs = get_all_corrections()
    vault_corrs = current_vault.get("corrections", [])
    corrs_map: Dict[str, Dict[str, Any]] = {}
    for c in vault_corrs + sqlite_corrs:
        if isinstance(c, dict):
            k = f"{c.get('timestamp')}|{c.get('item')}|{c.get('old_val')}|{c.get('new_val')}"
            corrs_map[k] = c
    all_merged_corrs = list(corrs_map.values())
    all_merged_corrs.sort(key=lambda x: str(x.get("timestamp") or ""), reverse=True)

    # Save to local archive vault file
    archive_payload = {
        "emp_id": emp_id,
        "archived_weeks": sorted_weeks,
        "week_details": saved_details,
        "audit_logs": all_merged_audits,
        "corrections": all_merged_corrs
    }
    try:
        os.makedirs(os.path.dirname(ARCHIVE_FILE), exist_ok=True)
        with open(ARCHIVE_FILE, "w", encoding="utf-8") as f:
            json.dump(archive_payload, f, indent=2)
    except Exception as ex:
        print(f"[Archive] Warning writing local vault: {ex}")

    # Synchronize with MongoDB Atlas Cloud Vault
    cloud_result = {"success": False}
    if "test_timecard.db" not in DB_FILE:
        try:
            from .mongo_vault import sync_cloud_archive
            cloud_result = sync_cloud_archive(
                emp_id=emp_id,
                local_archived_weeks=sorted_weeks,
                local_week_details=saved_details,
                local_audit_logs=all_merged_audits,
                local_corrections=all_merged_corrs
            )
            if cloud_result.get("success"):
                print(f"[MongoDB] Preserved {len(sorted_weeks)} weeks, {len(all_merged_audits)} punch audits, {len(all_merged_corrs)} corrections in MongoDB Atlas for emp_id: {emp_id}")
            else:
                print(f"[MongoDB] Cloud sync notice: {cloud_result.get('error')}")
        except Exception as ex:
            print(f"[MongoDB] Cloud sync failed: {ex}")
            cloud_result = {"success": False, "error": str(ex)}

    return {
        "success": True,
        "emp_id": emp_id,
        "cloud_synced": cloud_result.get("success", False),
        "total_weeks": len(sorted_weeks),
        "total_audits": len(all_merged_audits),
        "total_corrections": len(all_merged_corrs)
    }


def get_week_details(week_end_date: str) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    week_row = conn.execute("SELECT * FROM weeks WHERE week_end_date = ?", (week_end_date,)).fetchone()
    if not week_row:
        conn.close()
        # Fallback to permanent archive vault if week aged out of DB
        vault = load_archive_vault()
        return vault.get("week_details", {}).get(week_end_date)

    shifts = [
        dict(s) for s in conn.execute(
            "SELECT * FROM shifts WHERE week_end_date = ? ORDER BY date ASC",
            (week_end_date,)
        ).fetchall()
    ]

    if shifts:
        shift_dates = [s["date"] for s in shifts]
        placeholders = ",".join("?" for _ in shift_dates)

        details_by_date: Dict[str, List[Dict[str, Any]]] = {d: [] for d in shift_dates}
        for d_row in conn.execute(
            f"SELECT * FROM shift_details WHERE shift_date IN ({placeholders}) ORDER BY id ASC",
            shift_dates
        ).fetchall():
            details_by_date[d_row["shift_date"]].append(dict(d_row))

        audits_by_date: Dict[str, List[Dict[str, Any]]] = {d: [] for d in shift_dates}
        for a_row in conn.execute(
            f"SELECT * FROM shift_audit_log WHERE date IN ({placeholders}) ORDER BY id DESC",
            shift_dates
        ).fetchall():
            audits_by_date[a_row["date"]].append(dict(a_row))

        for s in shifts:
            s["details"] = details_by_date.get(s["date"], [])
            s["audit_history"] = audits_by_date.get(s["date"], [])

    conn.close()
    result = dict(week_row)
    result["shifts"] = shifts
    return result


def get_all_corrections() -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.execute("SELECT * FROM corrections ORDER BY timestamp DESC, id DESC")
    rows = [dict(r) for r in cursor.fetchall()]
    conn.close()
    return rows


def get_latest_sync_status() -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.execute("SELECT * FROM sync_logs ORDER BY id DESC LIMIT 1")
    row = cursor.fetchone()
    conn.close()
    return dict(row) if row else None


def clear_all_data():
    """Clears all active session timecard, time-off, and profile records, but preserves the permanent archive vault."""
    conn = get_connection()
    with conn:
        conn.executescript("""
            DELETE FROM weeks;
            DELETE FROM shifts;
            DELETE FROM shift_details;
            DELETE FROM shift_audit_log;
            DELETE FROM corrections;
            DELETE FROM entitlements;
            DELETE FROM employee_profile;
            DELETE FROM sync_logs;
        """)
    conn.close()
