import unittest
import tests  # Ensures isolated test_timecard.db is configured
from fastapi.testclient import TestClient
from backend.app import app

class TestAppEndpoints(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_get_weeks(self):
        response = self.client.get("/api/weeks")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("weeks", data)
        self.assertIn("archived_weeks", data)
        self.assertLessEqual(len(data["weeks"]), 8)
        self.assertGreaterEqual(len(data["weeks"]), 1)
        week_dates = [w["week_end_date"] for w in data["weeks"]]
        self.assertIn("2026-11-07", week_dates)

    def test_get_archive_endpoint(self):
        response = self.client.get("/api/archive")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("archived_weeks", data)
        self.assertEqual(data.get("active_week_limit"), 8)

    def test_archive_sync_endpoint(self):
        payload = {
            "emp_id": "TEST_DRIVER_99",
            "archived_weeks": [
                {"week_end_date": "2026-04-18", "status": "Final", "gross_pay": 2200.0, "total_hours": 42.0}
            ],
            "week_details": {
                "2026-04-18": {"week_end_date": "2026-04-18", "gross_pay": 2200.0, "shifts": []}
            },
            "audit_logs": [
                {
                    "week_end_date": "2026-04-18",
                    "date": "2026-04-15",
                    "field_name": "Start Time",
                    "old_value": "9:00 AM",
                    "new_value": "9:15 AM",
                    "diff_num": 15
                }
            ],
            "corrections": [
                {
                    "timestamp": "2026-04-16 10:00",
                    "card_type": "Timecard",
                    "action": "Edit",
                    "item": "Start",
                    "old_val": "09.00",
                    "new_val": "09.25"
                }
            ]
        }
        res = self.client.post("/api/archive/sync", json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertTrue(data.get("success"))
        archived_dates = [w["week_end_date"] for w in data["archived_weeks"]]
        self.assertIn("2026-04-18", archived_dates)
        self.assertIn("audit_logs", data)
        self.assertIn("corrections", data)
        self.assertGreaterEqual(len(data["audit_logs"]), 1)
        self.assertGreaterEqual(len(data["corrections"]), 1)

    def test_get_week_details(self):
        response = self.client.get("/api/weeks/2026-11-07")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["week_end_date"], "2026-11-07")
        self.assertAlmostEqual(data["gross_pay"], 2125.61)
        self.assertAlmostEqual(data["total_hours"], 40.83)
        self.assertEqual(len(data["shifts"]), 7)
        self.assertIn("totals", data)
        self.assertAlmostEqual(data["totals"]["st_hrs"], 32.0)
        self.assertAlmostEqual(data["totals"]["ot_hrs"], 8.83)
        # Verify effective_hourly_rate was removed from totals per requirement 4
        self.assertNotIn("effective_hourly_rate", data["totals"])

    def test_get_timeoff(self):
        response = self.client.get("/api/timeoff")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("profile", data)
        self.assertIn("entitlements", data)

    def test_get_audit_history(self):
        response = self.client.get("/api/audit-history")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("audit_logs", data)

    def test_get_status(self):
        response = self.client.get("/api/status")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("sync_in_progress", data)
        self.assertIn("percent", data)
        self.assertIn("stage", data)
        self.assertIn("requires_login", data)

    def test_auth_status(self):
        response = self.client.get("/api/auth/status")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("logged_in", data)
        self.assertIn("message", data)

    def test_auth_logout_endpoint(self):
        response = self.client.post("/api/auth/logout", json={"clear_data": False})
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data.get("success"))
        self.assertFalse(data.get("logged_in"))

    def test_sync_request_defaults(self):
        # Test that default sync payload accepts headless=True and supports current-week-only (max_weeks=1)
        from backend.app import SyncRequest
        req = SyncRequest()
        self.assertTrue(req.headless)
        self.assertEqual(req.max_weeks, 0)
        self.assertTrue(req.sync_timeoff)

        current_week_req = SyncRequest(max_weeks=1, headless=True, sync_timeoff=False)
        self.assertEqual(current_week_req.max_weeks, 1)
        self.assertFalse(current_week_req.sync_timeoff)

    def test_frontend_index(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn("TvC - Timecard Viewer", response.text)
        self.assertIn("Timecard Viewer", response.text)
        self.assertIn("Time Off Viewer", response.text)
        self.assertNotIn("Effective: $", response.text)

        # Verify DOT Remaining Hours feature elements (dropdown removed)
        self.assertIn("kpiDotRemaining", response.text)
        self.assertNotIn("dotLimitSelect", response.text)

        # Verify background sync progression bar elements
        self.assertIn("syncProgressBar", response.text)
        self.assertIn("syncStageTitle", response.text)
        self.assertIn("syncPercentBadge", response.text)

        # Verify breadcrumb text in header is removed
        self.assertNotIn("Tools & Resources", response.text)
        self.assertNotIn("Pay & Benefits", response.text)

        # Verify single auth button (toggles between Log In and Log Out)
        self.assertIn("authBtn", response.text)
        self.assertIn("authDot", response.text)
        self.assertIn("authLabel", response.text)
        self.assertNotIn("logoutBtn", response.text)

        # Verify Pull-Down Refresh & Sync (Current Week Only) elements
        self.assertIn("pullRefreshContainer", response.text)
        self.assertIn("<span>Sync</span>", response.text)
        self.assertNotIn("Sync All", response.text)
        self.assertNotIn("refreshCurrentWeekBtn", response.text)

        # Verify Monthly Historical Archive Tab & Notification Deep-Link Banner
        self.assertIn("tabArchive", response.text)
        self.assertIn("viewArchive", response.text)
        self.assertIn("archiveMonthSelect", response.text)
        self.assertIn("newTimecardToast", response.text)
        self.assertIn("loginRequiredView", response.text)
        self.assertIn("auditWeekLabel", response.text)
        self.assertNotIn("Export Archive", response.text)
        self.assertNotIn("Smart Auto-Check: Active", response.text)
        self.assertNotIn("kpiDotBadge", response.text)

    def test_service_worker_endpoint(self):
        response = self.client.get("/sw.js")
        self.assertEqual(response.status_code, 200)
        self.assertIn("application/javascript", response.headers.get("content-type", ""))
        self.assertIn("CACHE_NAME", response.text)
        self.assertIn("tvc-timecard", response.text)

    def test_ota_endpoints(self):
        # 1. Test OTA status endpoint
        res = self.client.get("/api/ota/status")
        self.assertEqual(res.status_code, 200)
        data = res.json()
        self.assertIn("version", data)
        self.assertIn("build", data)
        self.assertEqual(data.get("github_repo"), "solotreason/TvC")

        # 2. Test OTA check endpoint
        chk = self.client.post("/api/ota/check")
        self.assertEqual(chk.status_code, 200)
        self.assertEqual(chk.json().get("status"), "OK")

if __name__ == "__main__":
    unittest.main()
