import os
import atexit
import backend.database as db
from backend.parser import parse_timecard_html
from backend.timeoff_parser import parse_timeoff_html

# Point tests to an isolated test database so production timecard.db is never overwritten
TEST_DB_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "test_timecard.db")
os.environ["TIMECARD_DB_FILE"] = TEST_DB_PATH
db.DB_FILE = TEST_DB_PATH

def _cleanup_test_db():
    for suffix in ("", "-wal", "-shm"):
        p = TEST_DB_PATH + suffix
        if os.path.exists(p):
            try:
                os.remove(p)
            except Exception:
                pass

_cleanup_test_db()
atexit.register(_cleanup_test_db)

db.init_db()

# Seed the isolated test database with sample fixtures
_sample_tc = os.path.join(os.path.dirname(os.path.dirname(__file__)), "test_data", "sample_timecard.html")
if os.path.exists(_sample_tc):
    with open(_sample_tc, "r", encoding="utf-8") as f:
        parsed = parse_timecard_html(f.read())
    db.upsert_week(parsed["week_summary"])
    for s in parsed["shifts"]:
        db.upsert_shift(s)
        if s["details"]:
            db.replace_shift_details(s["date"], s["details"])

_sample_to = os.path.join(os.path.dirname(os.path.dirname(__file__)), "test_data", "sample_timeoff.html")
if os.path.exists(_sample_to):
    with open(_sample_to, "r", encoding="utf-8") as f:
        parsed_to = parse_timeoff_html(f.read())
    db.upsert_entitlements(parsed_to["entitlements"], parsed_to["profile"])
