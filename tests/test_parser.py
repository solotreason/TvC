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

    def test_parse_edge_case_html_snapshots(self):
        """Tests raw HTML parsing of Option Day, VACATION - EMPLOYEE PREPAID, and clock-in-only shifts."""
        from backend.database import is_option_day_pay_code, is_excluded_alert_pay_code, is_shift_completed
        edge_html = """
        <div class="timecard-wrapper">
          <div class="timecard-date-dropdown">
            <select><option value="2026-12-26" selected>Week Ending 12/26/2026 - Pending</option></select>
          </div>
          <div class="timecard-summary">
            <span class="weekGrossPay">$751.68</span>
            <span class="weekTotalHours">16.00</span>
            <div class="timecard-wrapper__lastUpdated"><span>12/23/2026 09:30 AM</span></div>
          </div>
          <div class="timecard-list-view">
            <table><tbody>
              <tr class="timecard-card" data-date="2026-12-21">
                <td>Mon 12/21/2026</td>
                <td>OPTION DAY</td>
                <td>N/A</td>
                <td>N/A</td>
                <td>0.00</td>
                <td>$46.98</td>
                <td>8.00</td>
                <td>$375.84</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-22">
                <td>Tue 12/22/2026</td>
                <td>VACATION - EMPLOYEE PREPAID</td>
                <td>9.00</td>
                <td>17.00</td>
                <td>0.00</td>
                <td>$46.98</td>
                <td>8.00</td>
                <td>$375.84</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-23">
                <td>Wed 12/23/2026</td>
                <td>PAY ACTUAL</td>
                <td>9.25*</td>
                <td>N/A</td>
                <td>0.00</td>
                <td>$46.98</td>
                <td>0.00</td>
                <td>$0.00</td>
              </tr>
            </tbody></table>
          </div>
        </div>
        """
        res = parse_timecard_html(edge_html)
        shifts = res["shifts"]
        self.assertEqual(len(shifts), 3)

        mon_opt = next(s for s in shifts if s["date"] == "2026-12-21")
        self.assertTrue(is_option_day_pay_code(mon_opt["pay_code"], mon_opt.get("details")))
        self.assertFalse(is_excluded_alert_pay_code(mon_opt["pay_code"], mon_opt.get("details")))

        tue_vac = next(s for s in shifts if s["date"] == "2026-12-22")
        self.assertTrue(is_excluded_alert_pay_code(tue_vac["pay_code"], tue_vac.get("details")))
        self.assertFalse(is_shift_completed(tue_vac))
        self.assertFalse(is_option_day_pay_code(tue_vac["pay_code"], tue_vac.get("details")))

        wed_open = next(s for s in shifts if s["date"] == "2026-12-23")
        self.assertEqual(wed_open["is_late"], 1)
        self.assertIsNotNone(wed_open["start_time_dec"])
        self.assertIsNone(wed_open["end_time_dec"])
        self.assertFalse(is_shift_completed(wed_open))

if __name__ == "__main__":
    unittest.main()
