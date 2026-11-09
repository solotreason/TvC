import os
import unittest
import tests  # Ensures isolated test_timecard.db is configured
from backend.timeoff_parser import parse_timeoff_html
from backend.database import (
    init_db, upsert_shift, upsert_entitlements, get_entitlements,
    get_all_audit_logs, get_connection
)

class TestNewFeatures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sample_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "test_data", "sample_timeoff.html")
        with open(sample_path, "r", encoding="utf-8") as f:
            cls.sample_html = f.read()
        init_db()

    def test_parse_timeoff(self):
        parsed = parse_timeoff_html(self.sample_html)
        profile = parsed["profile"]
        self.assertEqual(profile["emp_id"], "1002003")
        self.assertEqual(profile["emp_name"], "JANE DOE")

        entitlements = parsed["entitlements"]
        self.assertEqual(len(entitlements), 6)

        vac = next(e for e in entitlements if e["timeoff_type"] == "VAC")
        self.assertEqual(vac["title"], "Vacation")
        self.assertAlmostEqual(vac["available_time"], 9.00)
        self.assertEqual(vac["time_basis"], "Hours")
        self.assertEqual(vac["next_earn_date"], "Thu 12/31/2026")

        # Test DB storage
        upsert_entitlements(entitlements, profile)
        stored = get_entitlements()
        self.assertIsNotNone(stored["profile"])
        self.assertEqual(stored["profile"]["emp_name"], "JANE DOE")
        self.assertEqual(len(stored["entitlements"]), 6)

    def test_punch_change_tracking_audit_log(self):
        # Clear any previous audit logs for a clean assertion
        conn = get_connection()
        with conn:
            conn.execute("DELETE FROM shift_audit_log")
        conn.close()

        # Insert parent test week
        from backend.database import upsert_week
        upsert_week({
            "week_end_date": "2026-12-12",
            "status": "Pending",
            "gross_pay": 509.73,
            "total_hours": 9.90,
            "last_updated_source": "Test"
        })

        # 1. Insert initial shift on a test week
        initial_shift = {
            "week_end_date": "2026-12-12",
            "date": "2026-12-07",
            "day_name": "Mon",
            "pay_code": "PAY ACTUAL",
            "start_time_dec": 9.28,
            "start_time_clock": "9:17 AM",
            "is_late": 0,
            "end_time_dec": 19.68,
            "end_time_clock": "7:41 PM",
            "meal_unpaid_hours": 0.50,
            "pay_rate": 46.98,
            "total_hours": 9.90,
            "gross_pay": 509.73
        }
        upsert_shift(initial_shift)

        # 2. Simulate supervisor altering Start Time, End Time, and Meal/Unpaid
        # e.g. Start pushed forward from 9.28 (9:17 AM) to 9.35 (9:21 AM) -> +4 mins
        # e.g. Meal increased from 0.50 (30m) to 0.75 (45m) -> +15 mins
        modified_shift = dict(initial_shift)
        modified_shift["start_time_dec"] = 9.35
        modified_shift["start_time_clock"] = "9:21 AM"
        modified_shift["meal_unpaid_hours"] = 0.75
        modified_shift["total_hours"] = 9.40
        modified_shift["gross_pay"] = 480.00
        upsert_shift(modified_shift)

        # 3. Check audit log: MUST strictly track Start Time, End Time, Meal/Unpaid
        logs = get_all_audit_logs()
        fields_logged = [l["field_name"] for l in logs]
        
        self.assertIn("Start Time", fields_logged)
        self.assertIn("Meal/Unpaid", fields_logged)
        # Gross pay and total hours must NOT be in audit log
        self.assertNotIn("Gross Pay ($)", fields_logged)
        self.assertNotIn("Total Hours", fields_logged)

        # Check punch diff calculation (in minutes)
        start_log = next(l for l in logs if l["field_name"] == "Start Time")
        self.assertEqual(start_log["diff_num"], 4) # 4 mins difference

        meal_log = next(l for l in logs if l["field_name"] == "Meal/Unpaid")
        self.assertEqual(meal_log["diff_num"], 15) # 15 mins difference
        print("\nPunch alteration tracking & diffing verified successfully!")

    def test_new_daily_timecard_detection(self):
        from backend.database import upsert_week
        upsert_week({
            "week_end_date": "2026-12-19",
            "status": "Pending",
            "gross_pay": 0.0,
            "total_hours": 0.0,
            "last_updated_source": "Test"
        })

        # Seed unworked day rows for the week first
        unworked_tue = {
            "week_end_date": "2026-12-19",
            "date": "2026-12-15",
            "day_name": "Tue",
            "pay_code": "NO WORK",
            "start_time_dec": None,
            "start_time_clock": None,
            "is_late": 0,
            "end_time_dec": None,
            "end_time_clock": None,
            "meal_unpaid_hours": 0.0,
            "pay_rate": 0.0,
            "total_hours": 0.0,
            "gross_pay": 0.0
        }
        self.assertFalse(upsert_shift(unworked_tue))

        # Now a daily timecard is posted for Tuesday 12/15 -> should return True!
        posted_tue = dict(unworked_tue)
        posted_tue["pay_code"] = "PAY ACTUAL"
        posted_tue["start_time_dec"] = 9.25
        posted_tue["start_time_clock"] = "9:15 AM"
        posted_tue["end_time_dec"] = 19.50
        posted_tue["end_time_clock"] = "7:30 PM"
        posted_tue["meal_unpaid_hours"] = 0.50
        posted_tue["pay_rate"] = 46.98
        posted_tue["total_hours"] = 9.75
        posted_tue["gross_pay"] = 499.16

        self.assertTrue(upsert_shift(posted_tue))
        # Subsequent sync of the same already-posted day should return False
        self.assertFalse(upsert_shift(posted_tue))

    def test_eight_week_cap_and_archive_split(self):
        from backend.database import upsert_week, get_active_and_archived_weeks
        extra_weeks = [
            "2026-08-29", "2026-09-05", "2026-09-12", "2026-09-19", "2026-09-26",
            "2026-10-03", "2026-10-10", "2026-10-17", "2026-10-24", "2026-10-31"
        ]
        try:
            for w_date in extra_weeks:
                upsert_week({
                    "week_end_date": w_date,
                    "status": "Finalized",
                    "gross_pay": 1800.0,
                    "total_hours": 40.0,
                    "last_updated_source": "ArchiveTest"
                })
            split = get_active_and_archived_weeks(limit=8)
            self.assertEqual(len(split["weeks"]), 8)
            self.assertGreaterEqual(len(split["archived_weeks"]), 3)
            # Both October and September/August weeks should be in archived_weeks for monthly selection
            archived_dates = [w["week_end_date"] for w in split["archived_weeks"]]
            self.assertIn("2026-10-31", archived_dates)
            self.assertIn("2026-09-05", archived_dates)
            self.assertIn("2026-08-29", archived_dates)
        finally:
            conn = get_connection()
            with conn:
                placeholders = ",".join("?" for _ in extra_weeks)
                conn.execute(f"DELETE FROM weeks WHERE week_end_date IN ({placeholders})", extra_weeks)
            conn.close()

    def test_archive_vault_never_deleted_on_clear(self):
        from backend.database import clear_all_data, ARCHIVE_FILE, get_connection
        # Create a mock archive file to simulate multi-month vault
        import os, json
        os.makedirs(os.path.dirname(ARCHIVE_FILE), exist_ok=True)
        test_payload = {
            "archived_weeks": [{"week_end_date": "2026-05-30", "status": "Final", "gross_pay": 1500.0, "total_hours": 35.0}],
            "week_details": {"2026-05-30": {"week_end_date": "2026-05-30", "shifts": []}}
        }
        with open(ARCHIVE_FILE, "w", encoding="utf-8") as f:
            json.dump(test_payload, f)

        # Calling clear_all_data() simulates logout
        clear_all_data()

        # Archive file MUST still exist with all past months intact!
        self.assertTrue(os.path.exists(ARCHIVE_FILE))
        with open(ARCHIVE_FILE, "r", encoding="utf-8") as f:
            persisted = json.load(f)
        self.assertEqual(len(persisted["archived_weeks"]), 1)
        self.assertEqual(persisted["archived_weeks"][0]["week_end_date"], "2026-05-30")

        # Cleanup test file
        try:
            os.remove(ARCHIVE_FILE)
        except Exception:
            pass

    @classmethod
    def tearDownClass(cls):
        conn = get_connection()
        with conn:
            conn.execute("DELETE FROM shifts WHERE week_end_date IN ('2026-12-12', '2026-12-19')")
            conn.execute("DELETE FROM weeks WHERE week_end_date IN ('2026-12-12', '2026-12-19')")
        conn.close()

if __name__ == "__main__":
    unittest.main()
