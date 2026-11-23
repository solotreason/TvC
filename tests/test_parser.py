import os
import unittest
import tests  # Ensures isolated test_timecard.db is configured
from backend.parser import parse_timecard_html
from backend.database import (
    init_db, upsert_week, upsert_shift, replace_shift_details,
    get_week_details
)

class TestTimecardParser(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sample_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "test_data", "sample_timecard.html")
        with open(sample_path, "r", encoding="utf-8") as f:
            cls.sample_html = f.read()
        init_db()

    def test_parse_sample_with_active_row(self):
        result = parse_timecard_html(self.sample_html)
        
        # Test available weeks
        weeks = result["available_weeks"]
        self.assertGreaterEqual(len(weeks), 9)
        self.assertEqual(weeks[0]["week_end_date"], "2026-11-07")

        # Test week summary
        summary = result["week_summary"]
        self.assertAlmostEqual(summary["gross_pay"], 2125.61)
        self.assertAlmostEqual(summary["total_hours"], 40.83)

        # Test shifts
        shifts = result["shifts"]
        self.assertEqual(len(shifts), 7)

        # Mon 11/02
        mon = next(s for s in shifts if s["date"] == "2026-11-02")
        self.assertEqual(mon["day_name"], "Mon")
        self.assertEqual(mon["pay_rate"], 46.98) # Extracted from active row details
        self.assertEqual(mon["start_time_clock"], "9:17 AM")
        self.assertEqual(mon["end_time_clock"], "7:41 PM")
        self.assertAlmostEqual(mon["meal_unpaid_hours"], 0.50)
        self.assertAlmostEqual(mon["total_hours"], 9.90)
        self.assertAlmostEqual(mon["gross_pay"], 509.73)
        self.assertEqual(len(mon["details"]), 1)
        self.assertEqual(mon["details"][0]["activity"], "ROUTE DRIVER")
        self.assertAlmostEqual(mon["details"][0]["rate"], 46.98)

        # Wed 11/04 - Multi-segment active row test!
        wed = next(s for s in shifts if s["date"] == "2026-11-04")
        self.assertEqual(wed["day_name"], "Wed")
        self.assertEqual(wed["pay_rate"], 46.98)
        self.assertEqual(len(wed["details"]), 6) # Exactly 6 split activity segments, excluding dailyTotalRow & viewCorrectionRow

        # Verify segments
        activities = [d["activity"] for d in wed["details"]]
        self.assertIn("SHUTTLE DRIVER", activities)
        self.assertIn("ROUTE DRIVER", activities)
        
        sum_st = sum(d["st_hrs"] for d in wed["details"])
        sum_ot = sum(d["ot_hrs"] for d in wed["details"])
        self.assertAlmostEqual(sum_st, 8.00)
        self.assertAlmostEqual(sum_ot, 2.39)
        self.assertAlmostEqual(sum_st + sum_ot, wed["total_hours"])

        # Database integration test
        upsert_week(summary)
        for s in shifts:
            upsert_shift(s)
            if s["details"]:
                replace_shift_details(s["date"], s["details"])

        stored_week = get_week_details("2026-11-07")
        self.assertIsNotNone(stored_week)
        stored_wed = next(s for s in stored_week["shifts"] if s["date"] == "2026-11-04")
        self.assertEqual(len(stored_wed["details"]), 6)
        print("\nAll activeRow multi-segment tests passed!")

if __name__ == "__main__":
    unittest.main()
