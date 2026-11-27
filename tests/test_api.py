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

        # Verify Sync (Current Week Only) button and removal of pull-to-refresh
        self.assertNotIn("pullRefreshContainer", response.text)
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

    def test_security_headers_and_cache_control(self):
        res_index = self.client.get("/")
        self.assertEqual(res_index.status_code, 200)
        self.assertEqual(res_index.headers.get("x-content-type-options"), "nosniff")
        self.assertEqual(res_index.headers.get("x-frame-options"), "DENY")
        self.assertEqual(res_index.headers.get("referrer-policy"), "no-referrer")
        self.assertIn("geolocation=()", res_index.headers.get("permissions-policy", ""))
        self.assertIn("no-store", res_index.headers.get("cache-control", ""))

        res_api = self.client.get("/api/weeks")
        self.assertEqual(res_api.status_code, 200)
        self.assertEqual(res_api.headers.get("x-content-type-options"), "nosniff")
        self.assertEqual(res_api.headers.get("x-frame-options"), "DENY")

    def test_input_validation_and_sanitization(self):
        # Invalid week_end_date formats must return 400
        bad_week = self.client.get("/api/weeks/not-a-date")
        self.assertEqual(bad_week.status_code, 400)
        sqli_week = self.client.get("/api/weeks/2026-11-07'OR'1'='1")
        self.assertEqual(sqli_week.status_code, 400)

        # Valid date format that doesn't exist must return 404
        missing_week = self.client.get("/api/weeks/2099-12-31")
        self.assertEqual(missing_week.status_code, 404)

        # Negative or excessive max_weeks in /api/sync must return 422
        neg_sync = self.client.post("/api/sync", json={"max_weeks": -1, "headless": True})
        self.assertEqual(neg_sync.status_code, 422)
        huge_sync = self.client.post("/api/sync", json={"max_weeks": 500, "headless": True})
        self.assertEqual(huge_sync.status_code, 422)

        # Control chars in emp_id and malformed week_details keys are sanitized cleanly
        res_arch = self.client.post(
            "/api/archive/sync",
            json={
                "emp_id": "  EMP_CLEAN_01\x00\n ",
                "archived_weeks": [
                    {"week_end_date": "../bad", "status": "Final"},
                    {"week_end_date": "2026-03-28", "status": "Final", "gross_pay": 1000.0, "total_hours": 25.0},
                ],
                "week_details": {
                    "../bad": {"shifts": []},
                    "2026-03-28": {"week_end_date": "2026-03-28", "shifts": []},
                },
            },
        )
        self.assertEqual(res_arch.status_code, 200)
        body = res_arch.json()
        self.assertEqual(body["emp_id"], "EMP_CLEAN_01")
        archived_dates = [w["week_end_date"] for w in body["archived_weeks"]]
        self.assertIn("2026-03-28", archived_dates)
        self.assertNotIn("../bad", archived_dates)

    def test_check_ota_updates_accuracy_and_zero_side_effects(self):
        import json
        import os
        import urllib.error
        from pathlib import Path
        from unittest.mock import patch

        root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        v_path = Path(root_dir) / "version.json"
        before_bytes = v_path.read_bytes() if v_path.exists() else None

        class _FakeResp:
            def __init__(self, payload: dict):
                self._raw = json.dumps(payload).encode("utf-8")

            def read(self, _n: int = -1) -> bytes:
                return self._raw

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        # 1. Newer remote build + SHA-256 metadata + SSRF repo sanitization check
        requested_urls = []

        def _fake_urlopen_newer(req, timeout=4.0):
            requested_urls.append(req.full_url)
            return _FakeResp({
                "version": "9.9.9",
                "build": 2099123199,
                "apk_build": 2099123199,
                "release_notes": "Security release",
                "ota_sha256": "a" * 64,
                "apk_sha256": "b" * 64,
            })

        with patch("backend.app.get_ota_status", return_value={
            "version": "2.5.2",
            "build": 2026112502,
            "apk_build": 2026112502,
            "github_repo": "https://evil.example.com/inject",
        }), patch("urllib.request.urlopen", side_effect=_fake_urlopen_newer):
            resp = self.client.post("/api/ota/check")
            self.assertEqual(resp.status_code, 200)
            payload = resp.json()
            self.assertTrue(payload["checked_remote"])
            self.assertTrue(payload["update_available"])
            self.assertFalse(payload["up_to_date"])
            self.assertTrue(payload["apk_update_available"])
            self.assertEqual(payload["version"], "9.9.9")
            self.assertEqual(payload["build"], 2099123199)
            self.assertEqual(payload["ota_sha256"], "a" * 64)
            self.assertEqual(payload["apk_sha256"], "b" * 64)
            # Malformed github_repo must fall back to solotreason/TvC
            self.assertTrue(requested_urls[0].startswith("https://raw.githubusercontent.com/solotreason/TvC/"))

        # 2. Equal remote build -> up_to_date=True, update_available=False
        with patch("backend.app.get_ota_status", return_value={
            "version": "2.5.2",
            "build": 2026112502,
            "apk_build": 2026112502,
            "github_repo": "solotreason/TvC",
        }), patch("urllib.request.urlopen", return_value=_FakeResp({
            "version": "2.5.2",
            "build": 2026112502,
            "apk_build": 2026112502,
        })):
            resp_eq = self.client.post("/api/ota/check")
            self.assertEqual(resp_eq.status_code, 200)
            p_eq = resp_eq.json()
            self.assertTrue(p_eq["checked_remote"])
            self.assertFalse(p_eq["update_available"])
            self.assertTrue(p_eq["up_to_date"])
            self.assertFalse(p_eq["apk_update_available"])

        # 3. Offline / network error -> checked_remote=False, zero crash
        with patch("urllib.request.urlopen", side_effect=urllib.error.URLError("offline")):
            resp_off = self.client.post("/api/ota/check")
            self.assertEqual(resp_off.status_code, 200)
            p_off = resp_off.json()
            self.assertEqual(p_off["status"], "OK")
            self.assertFalse(p_off["checked_remote"])
            self.assertFalse(p_off["update_available"])

        # Verify version.json was never modified by /api/ota/check
        after_bytes = v_path.read_bytes() if v_path.exists() else None
        self.assertEqual(before_bytes, after_bytes)

if __name__ == "__main__":
    unittest.main()
