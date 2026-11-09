"""
MongoDB Cloud Vault Client for Timecard Tracker.
Provides seamless cloud sync for multi-user historical timecard archives,
ensuring past months and years are permanently preserved across phone switches (Android <-> iPhone).
"""
import os
import threading
from datetime import datetime, timezone
from typing import Dict, Any, List, Optional

_client = None
_client_lock = threading.Lock()

def _load_env_file():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    env_file = os.path.join(root_dir, ".env")
    if os.path.exists(env_file):
        try:
            with open(env_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip("'").strip('"')
                        if k and k not in os.environ:
                            os.environ[k] = v
        except Exception:
            pass

_load_env_file()

DEFAULT_MONGO_URI = os.environ.get(
    "MONGODB_URI",
    "mongodb+srv://solotreason_db_user:crFbSjbTw46SRJ2@cluster0.rj3o4lz.mongodb.net/?retryWrites=true&w=majority"
)
DB_NAME = os.environ.get("MONGODB_DB_NAME", "timecard_tracker")
COLLECTION_NAME = "archive_vault"


def get_mongo_client():
    global _client
    if _client is not None:
        return _client
    with _client_lock:
        if _client is not None:
            return _client
        try:
            from pymongo import MongoClient
            uri = os.environ.get("MONGODB_URI", DEFAULT_MONGO_URI)
            _client = MongoClient(
                uri,
                serverSelectionTimeoutMS=4000,
                maxPoolSize=10,
                minPoolSize=1,
                maxIdleTimeMS=30000
            )
            # Ensure O(1) unique index on emp_id
            try:
                db_name = os.environ.get("MONGODB_DB_NAME", DB_NAME)
                _client[db_name][COLLECTION_NAME].create_index("emp_id", unique=True)
            except Exception:
                pass
            return _client
        except Exception as e:
            print(f"[MongoDB] Could not initialize client: {e}")
            return None


def sync_cloud_archive(
    emp_id: str,
    local_archived_weeks: List[Dict[str, Any]],
    local_week_details: Dict[str, Any],
    local_audit_logs: Optional[List[Dict[str, Any]]] = None,
    local_corrections: Optional[List[Dict[str, Any]]] = None
) -> Dict[str, Any]:
    """
    Bi-directionally merges local archived weeks, punch audits, and corrections
    with MongoDB cloud collection for `emp_id`.
    Returns the combined, deduplicated records.
    """
    if not emp_id:
        emp_id = "default_user"

    client = get_mongo_client()
    if client is None:
        return {
            "success": False,
            "error": "MongoDB client unavailable",
            "archived_weeks": local_archived_weeks,
            "week_details": local_week_details,
            "audit_logs": local_audit_logs or [],
            "corrections": local_corrections or []
        }

    try:
        db = client[DB_NAME]
        coll = db[COLLECTION_NAME]

        # 1. Fetch existing cloud record for this employee
        doc = coll.find_one({"emp_id": emp_id}) or {}
        cloud_weeks = doc.get("archived_weeks", [])
        cloud_details = doc.get("week_details", {})
        cloud_audits = doc.get("audit_logs", [])
        cloud_corrs = doc.get("corrections", [])

        # 2. Merge weeks (deduplicate by week_end_date)
        merged_weeks_map: Dict[str, Dict[str, Any]] = {}
        for w in cloud_weeks:
            if isinstance(w, dict) and w.get("week_end_date"):
                merged_weeks_map[w["week_end_date"]] = w

        for w in local_archived_weeks:
            if isinstance(w, dict) and w.get("week_end_date"):
                merged_weeks_map[w["week_end_date"]] = w

        sorted_weeks = sorted(merged_weeks_map.values(), key=lambda x: x.get("week_end_date", ""), reverse=True)

        merged_details = dict(cloud_details) if isinstance(cloud_details, dict) else {}
        if isinstance(local_week_details, dict):
            merged_details.update(local_week_details)

        # 3. Merge punch audits (deduplicate by week_end_date + date + field_name + old_value + new_value)
        audit_keys = set()
        merged_audits: List[Dict[str, Any]] = []

        for a in (local_audit_logs or []) + cloud_audits:
            if not isinstance(a, dict):
                continue
            k = f"{a.get('week_end_date')}|{a.get('date')}|{a.get('field_name')}|{a.get('old_value')}|{a.get('new_value')}"
            if k not in audit_keys:
                audit_keys.add(k)
                merged_audits.append(a)

        merged_audits.sort(key=lambda x: str(x.get("detected_at") or x.get("date") or ""), reverse=True)

        # 4. Merge corrections (deduplicate by timestamp + item + old_val + new_val)
        corr_keys = set()
        merged_corrs: List[Dict[str, Any]] = []

        for c in (local_corrections or []) + cloud_corrs:
            if not isinstance(c, dict):
                continue
            k = f"{c.get('timestamp')}|{c.get('item')}|{c.get('old_val')}|{c.get('new_val')}"
            if k not in corr_keys:
                corr_keys.add(k)
                merged_corrs.append(c)

        merged_corrs.sort(key=lambda x: str(x.get("timestamp") or ""), reverse=True)

        # 5. Upsert back to MongoDB
        coll.update_one(
            {"emp_id": emp_id},
            {
                "$set": {
                    "emp_id": emp_id,
                    "archived_weeks": sorted_weeks,
                    "week_details": merged_details,
                    "audit_logs": merged_audits,
                    "corrections": merged_corrs,
                    "last_synced_at": datetime.now(timezone.utc).isoformat()
                }
            },
            upsert=True
        )

        return {
            "success": True,
            "emp_id": emp_id,
            "total_archived_weeks": len(sorted_weeks),
            "archived_weeks": sorted_weeks,
            "week_details": merged_details,
            "audit_logs": merged_audits,
            "corrections": merged_corrs
        }
    except Exception as e:
        print(f"[MongoDB] Cloud sync warning: {e}")
        return {
            "success": False,
            "error": str(e),
            "archived_weeks": local_archived_weeks,
            "week_details": local_week_details,
            "audit_logs": local_audit_logs or [],
            "corrections": local_corrections or []
        }


def fetch_cloud_archive(emp_id: str) -> Optional[Dict[str, Any]]:
    """Fetches cloud archive records for emp_id if available."""
    if not emp_id:
        emp_id = "default_user"
    client = get_mongo_client()
    if client is None:
        return None
    try:
        db = client[DB_NAME]
        coll = db[COLLECTION_NAME]
        doc = coll.find_one({"emp_id": emp_id})
        if doc:
            doc.pop("_id", None)
            return doc
    except Exception as e:
        print(f"[MongoDB] Fetch error: {e}")
    return None

