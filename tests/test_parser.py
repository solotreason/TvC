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
        edge_html = """<!DOCTYPE html>
        <html>
        <body>
          <div class="timecard-wrapper">
            <div class="timecard-date-dropdown">
              <select>
                <option value="2026-12-26" selected>Week Ending 12/26/2026 - Pending</option>
              </select>
            </div>
            <div class="timecard-summary">
              <span class="weekGrossPay">$1,634.90</span>
              <span class="weekTotalHours">34.80</span>
              <div class="timecard-wrapper__lastUpdated"><span>12/25/2026 08:15 PM</span></div>
            </div>
            <div class="timecard-list-view">
              <table>
                <tbody>
                  <!-- 1. Option Day Row (OPT without start/end punches) -->
                  <tr class="timecard-card activeRow" data-date="2026-12-21">
                    <td>Mon 12/21/2026</td>
                    <td>OPTION DAY</td>
                    <td>N/A</td>
                    <td>N/A</td>
                    <td>0.00</td>
                    <td>$46.98</td>
                    <td>8.00</td>
                    <td>$375.84</td>
                  </tr>
                  <tr class="acc2026-12-21">
                    <td colspan="8">
                      <table>
                        <tbody>
                          <tr>
                            <td>OPT</td>
                            <td>OPS</td>
                            <td>0100</td>
                            <td>DAY</td>
                            <td>OPTION DAY</td>
                            <td>$46.98</td>
                            <td>8.00</td>
                            <td>$375.84</td>
                            <td>0.00</td>
                            <td>$0.00</td>
                            <td>0.00</td>
                            <td>$0.00</td>
                            <td></td>
                            <td></td>
                            <td>$0.00</td>
                          </tr>
                        </tbody>
                      </table>
                    </td>
                  </tr>
                  <!-- 2. Clock-in-only open shift (no end punch, 0.00 hours) -->
                  <tr class="timecard-card" data-date="2026-12-22">
                    <td>Tue 12/22/2026</td>
                    <td>PAY ACTUAL</td>
                    <td>9.15</td>
                    <td>N/A</td>
                    <td>0.00</td>
                    <td>$46.98</td>
                    <td>0.00</td>
                    <td>$0.00</td>
                  </tr>
                  <!-- 3. Modified punch with late asterisk (*) -->
                  <tr class="timecard-card activeRow" data-date="2026-12-23">
                    <td>Wed 12/23/2026</td>
                    <td>PAY ACTUAL</td>
                    <td>9.50*</td>
                    <td>18.80*</td>
                    <td>0.50</td>
                    <td>$46.98</td>
                    <td>8.80</td>
                    <td>$432.22</td>
                  </tr>
                  <!-- 4. VACATION - EMPLOYEE PREPAID row (with hours & pay) -->
                  <tr class="timecard-card activeRow" data-date="2026-12-24">
                    <td>Thu 12/24/2026</td>
                    <td>VACATION - EMPLOYEE PREPAID</td>
                    <td>9.00</td>
                    <td>17.00</td>
                    <td>0.00</td>
                    <td>$46.98</td>
                    <td>8.00</td>
                    <td>$375.84</td>
                  </tr>
                  <tr class="acc2026-12-24">
                    <td colspan="8">
                      <table>
                        <tbody>
                          <tr>
                            <td>VAC</td>
                            <td>OPS</td>
                            <td>0100</td>
                            <td>DAY</td>
                            <td>VACATION - EMPLOYEE PREPAID</td>
                            <td>$46.98</td>
                            <td>8.00</td>
                            <td>$375.84</td>
                            <td>0.00</td>
                            <td>$0.00</td>
                            <td>0.00</td>
                            <td>$0.00</td>
                            <td></td>
                            <td></td>
                            <td>$0.00</td>
                          </tr>
                        </tbody>
                      </table>
                    </td>
                  </tr>
                  <!-- 5. Option Week (OPW) via detail row -->
                  <tr class="timecard-card activeRow" data-date="2026-12-25">
                    <td>Fri 12/25/2026</td>
                    <td>OPW</td>
                    <td>--</td>
                    <td>--</td>
                    <td>0.00</td>
                    <td>$46.98</td>
                    <td>10.00</td>
                    <td>$451.00</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <table class="corrections-table">
              <tbody class="viewcorrections-data__data">
                <tr>
                  <td>12/23/2026 02:10 PM</td>
                  <td>Timecard</td>
                  <td>Edit</td>
                  <td>Start Time</td>
                  <td>09.25</td>
                  <td>09.50*</td>
                  <td>SUPERVISOR1</td>
                  <td>MGR ADJ</td>
                </tr>
              </tbody>
            </table>
          </div>
        </body>
        </html>"""

        parsed = parse_timecard_html(edge_html)
        self.assertEqual(parsed["week_summary"]["week_end_date"], "2026-12-26")
        self.assertAlmostEqual(parsed["week_summary"]["gross_pay"], 1634.90)
        self.assertAlmostEqual(parsed["week_summary"]["total_hours"], 34.80)

        shifts = {s["date"]: s for s in parsed["shifts"]}
        self.assertEqual(len(shifts), 5)

        # Verify Option Day row
        opt_mon = shifts["2026-12-21"]
        self.assertEqual(opt_mon["pay_code"], "OPTION DAY")
        self.assertIsNone(opt_mon["start_time_dec"])
        self.assertIsNone(opt_mon["end_time_dec"])
        self.assertAlmostEqual(opt_mon["total_hours"], 8.00)
        self.assertEqual(len(opt_mon["details"]), 1)
        self.assertEqual(opt_mon["details"][0]["trans_type"], "OPT")

        # Verify Clock-in-only row
        open_tue = shifts["2026-12-22"]
        self.assertEqual(open_tue["pay_code"], "PAY ACTUAL")
        self.assertAlmostEqual(open_tue["start_time_dec"], 9.15)
        self.assertEqual(open_tue["start_time_clock"], "9:09 AM")
        self.assertIsNone(open_tue["end_time_dec"])
        self.assertIsNone(open_tue["end_time_clock"])
        self.assertAlmostEqual(open_tue["total_hours"], 0.0)

        # Verify Modified punch with asterisks
        late_wed = shifts["2026-12-23"]
        self.assertEqual(late_wed["is_late"], 1)
        self.assertAlmostEqual(late_wed["start_time_dec"], 9.50)
        self.assertEqual(late_wed["start_time_clock"], "9:30 AM")
        self.assertAlmostEqual(late_wed["end_time_dec"], 18.80)
        self.assertEqual(late_wed["end_time_clock"], "6:48 PM")

        # Verify VACATION - EMPLOYEE PREPAID row
        prepaid_thu = shifts["2026-12-24"]
        self.assertEqual(prepaid_thu["pay_code"], "VACATION - EMPLOYEE PREPAID")
        self.assertAlmostEqual(prepaid_thu["total_hours"], 8.00)
        self.assertEqual(prepaid_thu["details"][0]["activity"], "VACATION - EMPLOYEE PREPAID")

        # Verify OPW row & corrections
        opw_fri = shifts["2026-12-25"]
        self.assertEqual(opw_fri["pay_code"], "OPW")
        self.assertAlmostEqual(opw_fri["total_hours"], 10.00)
        self.assertEqual(len(parsed["corrections"]), 1)
        self.assertEqual(parsed["corrections"][0]["new_val"], "09.50*")

    def test_parse_float_and_time_utils_edge_cases(self):
        from backend.parser import parse_float
        from backend.time_utils import parse_decimal_time, decimal_hours_to_hhmm

        self.assertEqual(parse_float(None), 0.0)
        self.assertEqual(parse_float(True), 0.0)
        self.assertEqual(parse_float(False), 0.0)
        self.assertEqual(parse_float(float("nan")), 0.0)
        self.assertEqual(parse_float(float("inf")), 0.0)
        self.assertEqual(parse_float("--"), 0.0)
        self.assertAlmostEqual(parse_float("$1,234.50"), 1234.50)

        dec0, clk0, late0 = parse_decimal_time("0.00")
        self.assertEqual((dec0, clk0, late0), (0.0, "12:00 AM", False))
        dec12, clk12, late12 = parse_decimal_time("12.00*")
        self.assertEqual((dec12, clk12, late12), (12.0, "12:00 PM", True))
        self.assertEqual(parse_decimal_time("null"), (None, None, False))
        self.assertEqual(decimal_hours_to_hhmm(None), "0h 0m")
        self.assertEqual(decimal_hours_to_hhmm(9.5), "9h 30m")

        # Malformed data-date with slashes/spaces must never raise CSS selector errors
        slash_date_html = """<!DOCTYPE html><html><body>
          <div class="timecard-wrapper">
            <div class="timecard-list-view"><table><tbody>
              <tr class="timecard-card" data-date="12/21/2026">
                <td>Mon 12/21/2026</td><td>PAY ACTUAL</td><td>9.00</td><td>17.50</td><td>0.50</td><td>$46.98</td><td>8.00</td><td>$375.84</td>
              </tr>
            </tbody></table></div>
          </div>
        </body></html>"""
        parsed_slash = parse_timecard_html(slash_date_html)
        self.assertEqual(len(parsed_slash["shifts"]), 1)
        self.assertAlmostEqual(parsed_slash["shifts"][0]["total_hours"], 8.00)

if __name__ == "__main__":
    unittest.main()
