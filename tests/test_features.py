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

        # Clock-in-only punch (no end punch / 0 hours) MUST NOT fire a notification yet
        clock_in_only_tue = dict(unworked_tue)
        clock_in_only_tue["pay_code"] = "PAY ACTUAL"
        clock_in_only_tue["start_time_dec"] = 9.25
        clock_in_only_tue["start_time_clock"] = "9:15 AM"
        self.assertFalse(upsert_shift(clock_in_only_tue))

        # Now the completed daily timecard is posted for Tuesday 12/15 -> should return True!
        posted_tue = dict(clock_in_only_tue)
        posted_tue["end_time_dec"] = 19.50
        posted_tue["end_time_clock"] = "7:30 PM"
        posted_tue["meal_unpaid_hours"] = 0.50
        posted_tue["pay_rate"] = 46.98
        posted_tue["total_hours"] = 9.75
        posted_tue["gross_pay"] = 499.16

        self.assertTrue(upsert_shift(posted_tue))
        # Subsequent sync of the same already-posted day should return False
        self.assertFalse(upsert_shift(posted_tue))

        # Option Day posted on Wednesday 12/16 (without clock punches) -> should return True!
        unworked_wed = dict(unworked_tue)
        unworked_wed["date"] = "2026-12-16"
        unworked_wed["day_name"] = "Wed"
        self.assertFalse(upsert_shift(unworked_wed))

        option_day_wed = dict(unworked_wed)
        option_day_wed["pay_code"] = "OPTION DAY"
        option_day_wed["total_hours"] = 8.00
        option_day_wed["gross_pay"] = 375.84
        self.assertTrue(upsert_shift(option_day_wed))
        self.assertFalse(upsert_shift(option_day_wed))

        # "VACATION - EMPLOYEE PREPAID" on Thursday 12/17 (even with total_hours > 0 and punches) -> MUST NOT alert!
        prepaid_vac_thu = dict(unworked_tue)
        prepaid_vac_thu["date"] = "2026-12-17"
        prepaid_vac_thu["day_name"] = "Thu"
        prepaid_vac_thu["pay_code"] = "VACATION - EMPLOYEE PREPAID"
        prepaid_vac_thu["start_time_dec"] = 9.00
        prepaid_vac_thu["start_time_clock"] = "9:00 AM"
        prepaid_vac_thu["end_time_dec"] = 17.00
        prepaid_vac_thu["end_time_clock"] = "5:00 PM"
        prepaid_vac_thu["total_hours"] = 8.00
        prepaid_vac_thu["gross_pay"] = 375.84
        vac_audits = []
        self.assertFalse(upsert_shift(prepaid_vac_thu, new_audits_out=vac_audits))
        # Even if start/end times on VACATION - EMPLOYEE PREPAID change, it must NOT generate a punch audit alert
        prepaid_vac_thu_mod = dict(prepaid_vac_thu)
        prepaid_vac_thu_mod["start_time_dec"] = 9.50
        prepaid_vac_thu_mod["start_time_clock"] = "9:30 AM"
        self.assertFalse(upsert_shift(prepaid_vac_thu_mod, new_audits_out=vac_audits))
        self.assertEqual(len(vac_audits), 0)

        # Punch modification on worked Tuesday 12/15 -> MUST populate new_audits_out
        tue_audits = []
        modified_tue = dict(posted_tue)
        modified_tue["end_time_dec"] = 19.75
        modified_tue["end_time_clock"] = "7:45 PM"
        upsert_shift(modified_tue, new_audits_out=tue_audits)
        self.assertEqual(len(tue_audits), 1)
        self.assertEqual(tue_audits[0]["field_name"], "End Time")

        # Option Day balance decrease in Time Off bank -> should return Option Day usage alert
        initial_entitlements = [
            {"timeoff_type": "OPT", "title": "Option Day", "available_time": 2.0, "time_basis": "Days", "next_earn_date": "Thu 12/31/2026"}
        ]
        upsert_entitlements(initial_entitlements)
        after_use_entitlements = [
            {"timeoff_type": "OPT", "title": "Option Day", "available_time": 1.0, "time_basis": "Days", "next_earn_date": "Thu 12/31/2026"}
        ]
        usages = upsert_entitlements(after_use_entitlements)
        self.assertEqual(len(usages), 1)
        self.assertEqual(usages[0]["timeoff_type"], "OPT")
        self.assertAlmostEqual(usages[0]["old_available"], 2.0)
        self.assertAlmostEqual(usages[0]["new_available"], 1.0)
        self.assertAlmostEqual(usages[0]["used_amount"], 1.0)

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

    def test_closed_app_sync_and_disk_reload_contracts(self):
        root_dir = os.path.dirname(os.path.dirname(__file__))
        helper_path = os.path.join(root_dir, "android", "app", "src", "main", "java", "com", "timecard", "tracker", "PortalHelper.java")
        job_path = os.path.join(root_dir, "android", "app", "src", "main", "java", "com", "timecard", "tracker", "TimecardSyncJobService.java")
        bridge_path = os.path.join(root_dir, "static", "android_bridge.js")
        app_js_path = os.path.join(root_dir, "static", "app.js")
        index_html_path = os.path.join(root_dir, "static", "index.html")
        manifest_path = os.path.join(root_dir, "android", "app", "src", "main", "AndroidManifest.xml")

        with open(helper_path, "r", encoding="utf-8") as f:
            helper_src = f.read()
        with open(job_path, "r", encoding="utf-8") as f:
            job_src = f.read()
        with open(bridge_path, "r", encoding="utf-8") as f:
            bridge_src = f.read()
        with open(app_js_path, "r", encoding="utf-8") as f:
            app_js_src = f.read()
        with open(index_html_path, "r", encoding="utf-8") as f:
            html_src = f.read()
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest_src = f.read()

        # Verify shared helper + atomic file storage + closed-app full ingestion + battery efficiency
        self.assertIn("writeTextFileAtomic", helper_src)
        self.assertIn("clearBackgroundSyncPrefs", helper_src)
        self.assertIn("PREF_LAST_TIMEOFF_FINGERPRINT", helper_src)
        self.assertIn("R.drawable.ic_stat_tvc", helper_src)
        self.assertIn("getPendingJob(JOB_ID)", job_src)
        self.assertIn("FAST_JOB_ID", job_src)
        self.assertIn("shouldSkipForBatteryEfficiency", job_src)
        self.assertIn("isTodayShiftCompletedInStore", job_src)
        self.assertIn("isOptionDayShift", job_src)
        self.assertIn("notifyForOptionDayBankChanges", job_src)
        self.assertIn("isPrimePostShiftWindow", job_src)
        self.assertIn("PREF_LAST_PAGE_FINGERPRINT", job_src)
        self.assertIn("window.TimecardBridge.ingestWeekHtml", job_src)
        self.assertIn("window.TimecardBridge.ingestTimeoffHtml", job_src)
        self.assertIn("recordBackgroundSyncComplete", job_src)

        # Verify disk reload & foreground refresh bridges + adaptive icon manifest + APK self-updater
        main_activity_path = os.path.join(root_dir, "android", "app", "src", "main", "java", "com", "timecard", "tracker", "MainActivity.java")
        apk_provider_path = os.path.join(root_dir, "android", "app", "src", "main", "java", "com", "timecard", "tracker", "ApkFileProvider.java")
        with open(main_activity_path, "r", encoding="utf-8") as f:
            main_src = f.read()
        with open(apk_provider_path, "r", encoding="utf-8") as f:
            provider_src = f.read()

        self.assertIn("reloadFromDiskIfUpdated", bridge_src)
        self.assertIn("getNewTimecardsJson", bridge_src)
        self.assertIn("getNewAuditsJson", bridge_src)
        self.assertIn("getOptionDayAlertsJson", bridge_src)
        self.assertIn("isOptionDayPayCode", bridge_src)
        self.assertIn("isExcludedAlertPayCode", bridge_src)
        self.assertIn("VACATION - EMPLOYEE PREPAID", bridge_src)
        self.assertIn("onApkUpdateAvailable", bridge_src)
        self.assertIn("isOptionDayShift", app_js_src)
        self.assertIn("isExcludedAlertShift", app_js_src)
        self.assertIn("notifyPunchAuditsDetected", app_js_src)
        self.assertIn("syncFromNativeDiskIfUpdated", app_js_src)
        self.assertIn("refreshDashboardFromStore", app_js_src)
        self.assertIn("triggerNativeApkInstall", app_js_src)
        self.assertIn('id="batteryOptNotice"', html_src)
        self.assertIn('id="dismissBatteryOptBtn"', html_src)
        self.assertIn('id="apkUpdateBanner"', html_src)
        self.assertIn('id="otaModalInstallApkBtn"', html_src)
        self.assertNotIn("Permanent Vault Active", html_src)
        self.assertIn("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", manifest_src)
        self.assertIn("REQUEST_INSTALL_PACKAGES", manifest_src)
        self.assertIn("com.timecard.tracker.apkprovider", manifest_src)
        self.assertIn("@mipmap/ic_launcher", manifest_src)
        self.assertIn("downloadAndInstallApk", main_src)
        self.assertIn("cleanupStaleOtaIfFactoryNewer", main_src)
        self.assertIn("isPortalDestinationUrl", main_src)
        self.assertIn("isTimecardOrTimeoffViewerUrl", main_src)
        self.assertIn("isTimecardOrTimeoffViewerUrl", helper_src)
        self.assertIn("maskPortalPageVisuals", main_src)
        self.assertIn("PORTAL_LOADING", main_src)
        self.assertIn("hasRealTimecardDom", main_src)
        self.assertIn("armSyncWatchdog", main_src)
        # Regression guards: never cloak DOM via visibility:hidden / setAlpha(0f) or return PORTAL_LOADING without .timecard-wrapper
        self.assertNotIn("setAlpha(0f)", main_src)
        self.assertNotIn("setProperty('visibility'", main_src)
        self.assertNotIn("if (isPortalHost) return 'PORTAL_LOADING'", main_src)
        self.assertNotIn("AMCV_", helper_src)
        self.assertNotIn("brcap", helper_src)
        self.assertIn("application/vnd.android.package-archive", provider_src)
        self.assertIn("isExcludedAlertShift", job_src)
        self.assertIn("VACATION - EMPLOYEE PREPAID", job_src)
        self.assertIn("absorbBackgroundNotifiedStateFromStore", app_js_src)
        self.assertIn("getStoreSnapshot", bridge_src)

    def test_html_snapshot_stress_pipeline_all_alert_rules(self):
        from backend.parser import parse_timecard_html
        from backend.database import (
            save_parsed_week, is_excluded_alert_pay_code,
            is_option_day_pay_code, is_shift_completed
        )

        # 1. Seed initial unworked week rows first, then ingest baseline HTML with Mon NO WORK,
        # Tue Clock-In-Only, Wed Completed, and Thu VACATION - EMPLOYEE PREPAID
        unworked_seed_html = """<!DOCTYPE html>
        <html><body>
          <div class="timecard-wrapper">
            <div class="timecard-date-dropdown">
              <select><option value="2026-12-26" selected>Week Ending 12/26/2026 - Pending</option></select>
            </div>
            <div class="timecard-summary">
              <span class="weekGrossPay">$0.00</span>
              <span class="weekTotalHours">0.00</span>
              <div class="timecard-wrapper__lastUpdated"><span>12/20/2026 06:00 AM</span></div>
            </div>
            <div class="timecard-list-view"><table><tbody>
              <tr class="timecard-card" data-date="2026-12-21">
                <td>Mon 12/21/2026</td><td>NO WORK</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$0.00</td><td>0.00</td><td>$0.00</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-22">
                <td>Tue 12/22/2026</td><td>NO WORK</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$0.00</td><td>0.00</td><td>$0.00</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-23">
                <td>Wed 12/23/2026</td><td>NO WORK</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$0.00</td><td>0.00</td><td>$0.00</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-24">
                <td>Thu 12/24/2026</td><td>NO WORK</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$0.00</td><td>0.00</td><td>$0.00</td>
              </tr>
            </tbody></table></div>
          </div>
        </body></html>"""
        self.assertEqual(len(save_parsed_week(parse_timecard_html(unworked_seed_html))), 0)

        baseline_html = """<!DOCTYPE html>
        <html><body>
          <div class="timecard-wrapper">
            <div class="timecard-date-dropdown">
              <select><option value="2026-12-26" selected>Week Ending 12/26/2026 - Pending</option></select>
            </div>
            <div class="timecard-summary">
              <span class="weekGrossPay">$875.00</span>
              <span class="weekTotalHours">17.75</span>
              <div class="timecard-wrapper__lastUpdated"><span>12/23/2026 09:00 PM</span></div>
            </div>
            <div class="timecard-list-view"><table><tbody>
              <tr class="timecard-card" data-date="2026-12-21">
                <td>Mon 12/21/2026</td><td>NO WORK</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$0.00</td><td>0.00</td><td>$0.00</td>
              </tr>
              <tr class="timecard-card" data-date="2026-12-22">
                <td>Tue 12/22/2026</td><td>PAY ACTUAL</td><td>9.00</td><td>N/A</td><td>0.00</td><td>$46.98</td><td>0.00</td><td>$0.00</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-23">
                <td>Wed 12/23/2026</td><td>PAY ACTUAL</td><td>9.25</td><td>19.50</td><td>0.50</td><td>$46.98</td><td>9.75</td><td>$499.16</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-24">
                <td>Thu 12/24/2026</td><td>VACATION - EMPLOYEE PREPAID</td><td>9.00</td><td>17.00</td><td>0.00</td><td>$46.98</td><td>8.00</td><td>$375.84</td>
              </tr>
            </tbody></table></div>
          </div>
        </body></html>"""

        parsed_base = parse_timecard_html(baseline_html)
        base_audits = []
        base_new_cards = save_parsed_week(parsed_base, new_audits_out=base_audits)
        # Only Wed 12/23 (Completed worked shift) must fire; Tue 12/22 (clock-in-only) and Thu 12/24 (VACATION - EMPLOYEE PREPAID) must NOT fire!
        self.assertEqual([c["date"] for c in base_new_cards], ["2026-12-23"])
        self.assertEqual(len(base_audits), 0)

        # Verify helper classifications on parsed shifts
        shifts_by_date = {s["date"]: s for s in parsed_base["shifts"]}
        self.assertFalse(is_shift_completed(shifts_by_date["2026-12-22"]))  # Clock-in-only
        self.assertTrue(is_shift_completed(shifts_by_date["2026-12-23"]))   # Completed worked shift
        self.assertTrue(is_excluded_alert_pay_code(shifts_by_date["2026-12-24"]["pay_code"]))
        self.assertFalse(is_shift_completed(shifts_by_date["2026-12-24"]))  # Prepaid vacation never counts as completed worked alert
        self.assertFalse(is_option_day_pay_code(shifts_by_date["2026-12-24"]["pay_code"]))

        # 2. Updated Snapshot:
        # - Mon 12/21 transitions from NO WORK -> OPTION DAY (OPT) -> MUST trigger Option Day new_timecards alert
        # - Tue 12/22 transitions from Clock-In-Only -> Completed shift -> MUST trigger Completed daily timecard alert
        # - Wed 12/23 has Start Time modified to 9.50* and Meal modified to 0.75 -> MUST trigger 2 punch audits
        # - Thu 12/24 (VACATION - EMPLOYEE PREPAID) has hours/times modified -> MUST NOT trigger any alert or audit
        # - Fri 12/25 newly added as VACATION - EMPLOYEE PREPAID -> MUST NOT trigger any alert
        updated_html = """<!DOCTYPE html>
        <html><body>
          <div class="timecard-wrapper">
            <div class="timecard-date-dropdown">
              <select><option value="2026-12-26" selected>Week Ending 12/26/2026 - Pending</option></select>
            </div>
            <div class="timecard-summary">
              <span class="weekGrossPay">$2,100.00</span>
              <span class="weekTotalHours">43.00</span>
              <div class="timecard-wrapper__lastUpdated"><span>12/25/2026 09:30 PM</span></div>
            </div>
            <div class="timecard-list-view"><table><tbody>
              <tr class="timecard-card activeRow" data-date="2026-12-21">
                <td>Mon 12/21/2026</td><td>OPT</td><td>N/A</td><td>N/A</td><td>0.00</td><td>$46.98</td><td>8.00</td><td>$375.84</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-22">
                <td>Tue 12/22/2026</td><td>PAY ACTUAL</td><td>9.00</td><td>18.50</td><td>0.00</td><td>$46.98</td><td>9.50</td><td>$446.31</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-23">
                <td>Wed 12/23/2026</td><td>PAY ACTUAL</td><td>9.50*</td><td>19.50</td><td>0.75</td><td>$46.98</td><td>9.25</td><td>$465.00</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-24">
                <td>Thu 12/24/2026</td><td>VACATION - EMPLOYEE PREPAID</td><td>8.50</td><td>17.50</td><td>0.50</td><td>$46.98</td><td>8.50</td><td>$399.33</td>
              </tr>
              <tr class="timecard-card activeRow" data-date="2026-12-25">
                <td>Fri 12/25/2026</td><td>VACATION - EMPLOYEE PREPAID</td><td>9.00</td><td>17.00</td><td>0.00</td><td>$46.98</td><td>8.00</td><td>$375.84</td>
              </tr>
            </tbody></table></div>
          </div>
        </body></html>"""

        parsed_upd = parse_timecard_html(updated_html)
        upd_audits = []
        upd_new_cards = save_parsed_week(parsed_upd, new_audits_out=upd_audits)

        # Must only alert for Mon 12/21 (Option Day) and Tue 12/22 (Completed Shift) — never Thu/Fri Prepaid Vacation
        alerted_dates = {c["date"]: c for c in upd_new_cards}
        self.assertEqual(set(alerted_dates.keys()), {"2026-12-21", "2026-12-22"})
        self.assertTrue(alerted_dates["2026-12-21"]["is_option_day"])
        self.assertFalse(alerted_dates["2026-12-22"]["is_option_day"])

        # Audits must strictly be for Wed 12/23 (Start Time & Meal/Unpaid), and zero for Thu 12/24 Prepaid Vacation
        self.assertEqual(len(upd_audits), 2)
        audit_dates = {a["date"] for a in upd_audits}
        self.assertEqual(audit_dates, {"2026-12-23"})
        audit_fields = {a["field_name"] for a in upd_audits}
        self.assertEqual(audit_fields, {"Start Time", "Meal/Unpaid"})

        # 3. Stress-test Time Off Viewer Grid View fallback & Option Day / Option Week bank drop detection
        grid_timeoff_html_before = """<!DOCTYPE html>
        <html><body>
          <div class="timeoff-wrapper">
            <span class="empId">1002003</span>
            <span class="empName">JANE DOE</span>
            <div class="timeoff-grid-view">
              <div class="timeoff-card" data-timeoff-type="OPT">
                <div class="timeoff-card--header"><span>Option Day</span></div>
                <p><span>Available Time:</span><span>16.00</span></p>
                <p><span>Time Basis:</span><span>Hours</span></p>
                <span data-timeoff-date>Thu 12/31/2026</span>
              </div>
              <div class="timeoff-card" data-timeoff-type="VAC">
                <div class="timeoff-card--header"><span>Vacation</span></div>
                <p><span>Available Time:</span><span>40.00</span></p>
                <p><span>Time Basis:</span><span>Hours</span></p>
                <span data-timeoff-date>N/A</span>
              </div>
            </div>
          </div>
        </body></html>"""

        grid_timeoff_html_after = grid_timeoff_html_before.replace("<span>16.00</span>", "<span>8.00</span>").replace("<span>40.00</span>", "<span>32.00</span>")
        t_before = parse_timeoff_html(grid_timeoff_html_before)
        self.assertEqual(len(t_before["entitlements"]), 2)
        upsert_entitlements(t_before["entitlements"], t_before["profile"])

        t_after = parse_timeoff_html(grid_timeoff_html_after)
        opt_drops = upsert_entitlements(t_after["entitlements"], t_after["profile"])
        # Only OPT drop triggers Option Day alert, NOT regular VAC drop
        self.assertEqual(len(opt_drops), 1)
        self.assertEqual(opt_drops[0]["timeoff_type"], "OPT")
        self.assertAlmostEqual(opt_drops[0]["used_amount"], 8.00)

    def test_sqlite_connection_cleanup_and_leak_prevention(self):
        import sqlite3
        from unittest.mock import patch
        import backend.database as db

        open_conns = []
        real_connect = sqlite3.connect

        class _TrackedConn(sqlite3.Connection):
            def close(self_conn):
                if self_conn in open_conns:
                    open_conns.remove(self_conn)
                return super().close()

        def _tracked_connect(*args, **kwargs):
            kwargs["factory"] = _TrackedConn
            c = real_connect(*args, **kwargs)
            open_conns.append(c)
            return c

        with patch("backend.database.sqlite3.connect", side_effect=_tracked_connect):
            # 1. Normal execution paths must close every opened connection
            db.init_db()
            self.assertEqual(len(open_conns), 0)

            db.upsert_week({
                "week_end_date": "2026-12-26",
                "status": "Pending",
                "gross_pay": 100.0,
                "total_hours": 2.0,
                "last_updated_source": "ConnTest",
            })
            self.assertEqual(len(open_conns), 0)

            sid = db.log_sync_start()
            self.assertEqual(len(open_conns), 0)
            db.log_sync_complete(sid, "SUCCESS", "Conn check", 1)
            self.assertEqual(len(open_conns), 0)

            _ = db.get_all_weeks()
            _ = db.get_week_details("2026-12-26")
            _ = db.get_week_details("2099-01-01")
            _ = db.get_entitlements()
            _ = db.get_all_audit_logs()
            _ = db.get_all_corrections()
            _ = db.get_latest_sync_status()
            self.assertEqual(len(open_conns), 0)

            # 2. Exception paths (missing keys / bad parameters) must STILL close every connection
            with self.assertRaises(Exception):
                db.upsert_week({})  # Missing required named parameters
            self.assertEqual(len(open_conns), 0)

            with self.assertRaises(Exception):
                db.upsert_shift({"pay_code": "PAY ACTUAL"})  # Missing week_end_date/date
            self.assertEqual(len(open_conns), 0)

            with self.assertRaises(Exception):
                db.replace_shift_details("2026-12-21", [{}])  # Missing detail bindings
            self.assertEqual(len(open_conns), 0)

            with self.assertRaises(Exception):
                db.insert_correction({})  # Missing correction bindings
            self.assertEqual(len(open_conns), 0)

            with self.assertRaises(Exception):
                db.upsert_entitlements([{}])  # Missing entitlement bindings
            self.assertEqual(len(open_conns), 0)

    def test_option_day_via_details_and_prepaid_vacation_conversion_rules(self):
        from backend.database import (
            upsert_week, upsert_shift, replace_shift_details, is_option_day_pay_code
        )
        from backend.mongo_vault import _sanitize_error

        # Verify MongoDB credential redaction helper
        redacted = _sanitize_error("Connection failed: mongodb+srv://admin:s3cr3tPass@cluster0.example.net/db")
        self.assertNotIn("s3cr3tPass", redacted)
        self.assertIn("mongodb+srv://***@", redacted)

        # Verify Option Day detection when pay_code is generic ("OTHER") and OPT is in details
        opt_details = [{
            "trans_type": "OPT",
            "op_type": "OPS",
            "center": "0100",
            "sort": "DAY",
            "activity": "PAID LEAVE",
            "rate": 46.98,
            "st_hrs": 8.0,
            "st_amt": 375.84,
            "ot_hrs": 0.0,
            "ot_amt": 0.0,
            "dt_hrs": 0.0,
            "dt_amt": 0.0,
            "eq_type": "",
            "griev_num": "",
            "flat_amt": 0.0,
        }]
        self.assertTrue(is_option_day_pay_code("OTHER", opt_details))

        upsert_week({
            "week_end_date": "2026-12-19",
            "status": "Pending",
            "gross_pay": 0.0,
            "total_hours": 0.0,
            "last_updated_source": "EdgeTest",
        })
        seed_sun = {
            "week_end_date": "2026-12-19",
            "date": "2026-12-13",
            "day_name": "Sun",
            "pay_code": "SCHEDULED OFF",
            "start_time_dec": None,
            "start_time_clock": None,
            "is_late": 0,
            "end_time_dec": None,
            "end_time_clock": None,
            "meal_unpaid_hours": 0.0,
            "pay_rate": 0.0,
            "total_hours": 0.0,
            "gross_pay": 0.0,
        }
        upsert_shift(seed_sun)

        # First post of detail-backed Option Day on 2026-12-14 -> newly added True
        opt_via_detail_mon = {
            **seed_sun,
            "date": "2026-12-14",
            "day_name": "Mon",
            "pay_code": "OTHER",
            "total_hours": 8.0,
            "gross_pay": 375.84,
            "details": opt_details,
        }
        self.assertTrue(upsert_shift(opt_via_detail_mon))
        replace_shift_details("2026-12-14", opt_details)

        # Re-syncing the exact same detail-backed Option Day must NOT re-alert
        self.assertFalse(upsert_shift(opt_via_detail_mon))

        # Converting a VACATION - EMPLOYEE PREPAID day into a worked PAY ACTUAL shift
        # must NOT emit bogus punch-audit entries from the dummy vacation hours
        prepaid_fri = {
            **seed_sun,
            "date": "2026-12-18",
            "day_name": "Fri",
            "pay_code": "VACATION - EMPLOYEE PREPAID",
            "start_time_dec": 9.00,
            "start_time_clock": "9:00 AM",
            "end_time_dec": 17.00,
            "end_time_clock": "5:00 PM",
            "total_hours": 8.0,
            "gross_pay": 375.84,
        }
        conv_audits = []
        self.assertFalse(upsert_shift(prepaid_fri, new_audits_out=conv_audits))
        worked_fri = {
            **prepaid_fri,
            "pay_code": "PAY ACTUAL",
            "start_time_dec": 9.25,
            "start_time_clock": "9:15 AM",
            "end_time_dec": 18.25,
            "end_time_clock": "6:15 PM",
            "meal_unpaid_hours": 0.50,
            "total_hours": 8.50,
            "gross_pay": 399.33,
        }
        self.assertTrue(upsert_shift(worked_fri, new_audits_out=conv_audits))
        self.assertEqual(len(conv_audits), 0)

    def test_run_py_sha256_and_ota_packaging_contracts(self):
        import hashlib
        import inspect
        import json
        import tempfile
        from unittest.mock import patch
        import run as runner

        # 1. Verify pack_ota defaults build_apk=False so web-only OTA never forces APK rebuild
        sig = inspect.signature(runner.pack_ota)
        self.assertIn("build_apk", sig.parameters)
        self.assertIs(sig.parameters["build_apk"].default, False)

        # 2. Verify compute_sha256 produces exact lowercase 64-char SHA-256 hex digest
        with tempfile.TemporaryDirectory() as tmpdir:
            sample_bin = os.path.join(tmpdir, "sample_payload.bin")
            payload_bytes = b"TvC-OTA-Verification-Payload-2026\x00\xff" * 3000
            with open(sample_bin, "wb") as f:
                f.write(payload_bytes)
            expected_hex = hashlib.sha256(payload_bytes).hexdigest().lower()
            actual_hex = runner.compute_sha256(sample_bin)
            self.assertEqual(actual_hex, expected_hex)
            self.assertEqual(len(actual_hex), 64)

            # 3. Execute pack_ota(build_apk=False) in an isolated temp root to verify:
            #    - build increments
            #    - existing apk_build & apk_sha256 are preserved untouched
            #    - ota_bundle.zip is generated and matches version.json["ota_sha256"]
            static_dir = os.path.join(tmpdir, "static")
            os.makedirs(static_dir, exist_ok=True)
            with open(os.path.join(static_dir, "index.html"), "w", encoding="utf-8") as f:
                f.write('<span id="otaModalVersion">v2.5.0</span><span id="otaModalBuild">1</span><span id="otaModalApkBuild">1</span>')
            with open(os.path.join(static_dir, "app.js"), "w", encoding="utf-8") as f:
                f.write("// dashboard bundle\n")
            initial_apk_sha = "e" * 64
            with open(os.path.join(tmpdir, "version.json"), "w", encoding="utf-8") as f:
                json.dump({
                    "version": "2.5.2",
                    "build": 2026010101,
                    "apk_build": 2026010101,
                    "apk_sha256": initial_apk_sha,
                    "github_repo": "solotreason/TvC",
                }, f)

            fake_run_py = os.path.join(tmpdir, "run.py")
            with patch.object(runner, "__file__", fake_run_py), patch.object(runner, "sync_android_assets", lambda: None):
                runner.pack_ota(version="2.5.3", notes="Web-only OTA test", build_apk=False)

            with open(os.path.join(tmpdir, "version.json"), "r", encoding="utf-8") as f:
                out_meta = json.load(f)

            zip_path = os.path.join(static_dir, "ota_bundle.zip")
            self.assertTrue(os.path.isfile(zip_path))
            self.assertEqual(out_meta["version"], "2.5.3")
            self.assertGreater(out_meta["build"], 2026010101)
            self.assertEqual(out_meta["apk_build"], 2026010101)
            self.assertEqual(out_meta["apk_sha256"], initial_apk_sha)
            with open(zip_path, "rb") as zf_in:
                expected_zip_sha = hashlib.sha256(zf_in.read()).hexdigest().lower()
            self.assertEqual(out_meta["ota_sha256"], expected_zip_sha)
            self.assertEqual(len(out_meta["ota_sha256"]), 64)

    def test_single_consolidated_notification_behavior_in_app_js(self):
        import json
        import shutil
        import subprocess

        root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        app_js_path = os.path.join(root_dir, "static", "app.js")
        node_bin = shutil.which("node")
        self.assertIsNotNone(node_bin, "Node.js is required to execute app.js notification unit tests")

        harness = r"""
const fs = require('fs');
const vm = require('vm');

const appJsPath = process.argv[1];
const code = fs.readFileSync(appJsPath, 'utf8');

function makeElement() {
  return {
    textContent: '',
    innerHTML: '',
    value: '',
    disabled: false,
    title: '',
    style: {},
    dataset: {},
    options: [],
    className: '',
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    addEventListener() {},
    appendChild(opt) { this.options.push(opt); },
    querySelector() { return makeElement(); },
    closest() { return null; },
    click() {},
    scrollIntoView() {}
  };
}

function runScenario(hasAndroidNative) {
  let nativeCalls = [];
  let browserCalls = [];
  const elements = new Map();
  const getEl = (id) => {
    if (!elements.has(id)) elements.set(id, makeElement());
    return elements.get(id);
  };

  function FakeNotification(title, opts) {
    browserCalls.push({ title, opts });
  }
  FakeNotification.permission = 'granted';
  FakeNotification.requestPermission = () => Promise.resolve('granted');

  const statusPayload = {
    sync_in_progress: false,
    percent: 100,
    stage: 'Complete',
    message: 'Synced',
    requires_login: false,
    latest_log: { status: 'SUCCESS', message: 'Synced 1 week' },
    new_timecards: [
      {
        week_end_date: '2026-12-26',
        date: '2026-12-21',
        day_name: 'Mon',
        pay_code: 'OPTION DAY',
        is_option_day: true,
        total_hours: 8.0,
        gross_pay: 375.84
      },
      {
        week_end_date: '2026-12-26',
        date: '2026-12-23',
        day_name: 'Wed',
        pay_code: 'PAY ACTUAL',
        start_time_dec: 9.0,
        start_time_clock: '9:00 AM',
        end_time_dec: 18.5,
        end_time_clock: '6:30 PM',
        total_hours: 9.0,
        gross_pay: 446.31
      }
    ],
    new_audits: [
      {
        week_end_date: '2026-12-26',
        date: '2026-12-22',
        field_name: 'Start Time',
        old_value: '9:00 AM (9.00)',
        new_value: '9:15 AM (9.25)',
        diff_num: 15
      }
    ],
    option_day_alerts: [
      {
        is_option_day: true,
        from_entitlement_bank: true,
        timeoff_type: 'OPT',
        title: 'Option Day',
        old_available: 2.0,
        new_available: 1.0,
        used_amount: 1.0,
        time_basis: 'Days'
      }
    ]
  };

  const weekDetailPayload = {
    week_end_date: '2026-12-26',
    status: 'Pending',
    gross_pay: 822.15,
    total_hours: 17.0,
    shifts: statusPayload.new_timecards
  };

  const sandbox = {
    console,
    Intl,
    Date,
    Math,
    Number,
    String,
    Array,
    Object,
    Set,
    Map,
    JSON,
    Promise,
    setTimeout: () => 1,
    clearTimeout: () => {},
    setInterval: () => 1,
    clearInterval: () => {},
    localStorage: { getItem: () => null, setItem: () => {} },
    navigator: { onLine: true },
    Notification: FakeNotification,
    confirm: () => true,
    alert: () => {},
    document: {
      visibilityState: 'visible',
      activeElement: null,
      getElementById: getEl,
      createElement: () => makeElement(),
      querySelector: () => makeElement(),
      addEventListener: () => {}
    },
    fetch: async (url) => {
      if (url === '/api/status') return { ok: true, status: 200, json: async () => statusPayload };
      if (url === '/api/weeks') return { ok: true, status: 200, json: async () => ({ weeks: [{ week_end_date: '2026-12-26', status: 'Pending' }], archived_weeks: [] }) };
      if (String(url).startsWith('/api/weeks/')) return { ok: true, status: 200, json: async () => weekDetailPayload };
      if (url === '/api/timeoff') return { ok: true, status: 200, json: async () => ({ profile: null, entitlements: [] }) };
      if (url === '/api/audit-history') return { ok: true, status: 200, json: async () => ({ audit_logs: statusPayload.new_audits }) };
      if (url === '/api/auth/status') return { ok: true, status: 200, json: async () => ({ logged_in: true }) };
      return { ok: true, status: 200, json: async () => ({ success: true }) };
    }
  };
  sandbox.window = sandbox;
  if (hasAndroidNative) {
    sandbox.AndroidNative = {
      showTimecardNotificationForShift(title, body, weekEnd, shiftDate) {
        nativeCalls.push({ title, body, weekEnd, shiftDate });
      },
      getStoreLastModified: () => 0,
      isIgnoringBatteryOptimizations: () => true
    };
  }

  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);

  // Seed knownWorkedDaysByWeek with an earlier empty/different day so loadWeekDetails sees 12/21 & 12/23 as newly added if not already recorded!
  vm.runInContext("knownWorkedDaysByWeek.set('2026-12-26', new Set(['2026-12-20']));", sandbox);

  return (async () => {
    await sandbox.checkSyncStatus();
    const firstPass = { native: nativeCalls.length, browser: browserCalls.length };
    // Second pass with identical state must emit 0 additional notifications
    await sandbox.checkSyncStatus();
    const secondPass = { native: nativeCalls.length, browser: browserCalls.length };
    return { firstPass, secondPass };
  })();
}

(async () => {
  const androidRes = await runScenario(true);
  const webRes = await runScenario(false);
  console.log(JSON.stringify({ androidRes, webRes }));
})();
"""
        proc = subprocess.run(
            [node_bin, "-e", harness, app_js_path],
            capture_output=True,
            text=True,
            timeout=15,
            check=True,
        )
        results = json.loads(proc.stdout.strip().splitlines()[-1])

        # Android WebView: strictly 1 native notification and 0 browser Notification() calls
        self.assertEqual(results["androidRes"]["firstPass"]["native"], 1)
        self.assertEqual(results["androidRes"]["firstPass"]["browser"], 0)
        self.assertEqual(results["androidRes"]["secondPass"]["native"], 1)
        self.assertEqual(results["androidRes"]["secondPass"]["browser"], 0)

        # Web browser (no AndroidNative): strictly 1 consolidated browser Notification() call per sync cycle
        self.assertEqual(results["webRes"]["firstPass"]["browser"], 1)
        self.assertEqual(results["webRes"]["secondPass"]["browser"], 1)

    @classmethod
    def tearDownClass(cls):
        conn = get_connection()
        with conn:
            conn.execute("DELETE FROM shifts WHERE week_end_date IN ('2026-12-12', '2026-12-19', '2026-12-26')")
            conn.execute("DELETE FROM weeks WHERE week_end_date IN ('2026-12-12', '2026-12-19', '2026-12-26')")
        conn.close()

if __name__ == "__main__":
    unittest.main()
