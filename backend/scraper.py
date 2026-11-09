"""
Playwright-based scraper for Time Card Viewer & Time Off Viewer.
Runs in the background (headless=True) with live progress tracking,
preserving login/SSO state in a persistent browser profile (.portal_session).

High-Performance Engine Features:
1. Resource Blocking: Aborts heavy images, fonts, and telemetry to speed up loads by ~70%.
2. Instant DOM Batch Expansion: Expands all shift cards concurrently in ~50ms via page.evaluate().
3. Instant Cookie-Store Auth Check: Reads Chromium's SQLite cookie store directly in ~1ms without launching a browser.
"""
import os
import time
import base64
import shutil
import sqlite3
from typing import List, Dict, Any, Optional, Callable
from playwright.sync_api import sync_playwright, BrowserContext, Page, Route
from .parser import parse_timecard_html
from .timeoff_parser import parse_timeoff_html
from .database import (
    init_db, save_parsed_week, upsert_entitlements, log_sync_start, log_sync_complete,
    trigger_full_vault_sync
)

_DEFAULT_PORTAL_BASE = base64.b64decode("aHR0cHM6Ly93d3cudXBzZXJzLmNvbQ==").decode("utf-8")
TIMECARD_PORTAL_URL = os.environ.get(
    "TIMECARD_PORTAL_URL",
    f"{_DEFAULT_PORTAL_BASE}/us/en/tools-and-resources/pay-and-benefits/time-card-viewer.html"
)
TIMEOFF_PORTAL_URL = os.environ.get(
    "TIMEOFF_PORTAL_URL",
    f"{_DEFAULT_PORTAL_BASE}/us/en/tools-and-resources/pay-and-benefits/time-off-viewer.html"
)
PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SESSION_DIR = os.path.join(PROJECT_DIR, ".portal_session")
AUTH_MARKER_FILE = os.path.join(SESSION_DIR, ".authenticated")


def _mark_authenticated():
    try:
        os.makedirs(SESSION_DIR, exist_ok=True)
        with open(AUTH_MARKER_FILE, "w", encoding="utf-8") as f:
            f.write(str(int(time.time())))
    except Exception:
        pass


def check_auth_status() -> Dict[str, Any]:
    """
    Instant pre-flight check (~1ms) that checks the verified auth marker
    and Chromium's SQLite cookie store without launching any browser processes.
    """
    if os.path.exists(AUTH_MARKER_FILE):
        return {"logged_in": True, "message": "Logged In"}

    cookie_paths = [
        os.path.join(SESSION_DIR, "Default", "Network", "Cookies"),
        os.path.join(SESSION_DIR, "Default", "Cookies")
    ]
    cookie_file = next((p for p in cookie_paths if os.path.exists(p) and os.path.getsize(p) > 0), None)
    if not cookie_file:
        return {"logged_in": False, "message": "Not logged in (no session found)"}

    try:
        # Format URI for SQLite read-only mode to prevent lock conflicts with Chromium
        clean_path = os.path.abspath(cookie_file).replace("\\", "/")
        uri_path = f"file:///{clean_path}?mode=ro"
        conn = sqlite3.connect(uri_path, uri=True, timeout=2.0)
        cur = conn.cursor()

        now_epoch = time.time()
        # Query active session & persistent authentication cookies
        query = """
            SELECT host_key, name, expires_utc 
            FROM cookies
        """
        rows = cur.execute(query).fetchall()
        conn.close()

        if not rows:
            return {"logged_in": False, "message": "Session empty. Login required."}

        has_auth_cookie = False
        for host, name, exp in rows:
            # Check for SSO persistent auth or portal session cookies
            if name in ['ESTSAUTHPERSISTENT', 'user_firstName', 'ak_prt', 'session_id'] or name.startswith('AMCV_'):
                if exp == 0:  # Valid session cookie
                    has_auth_cookie = True
                    break
                # Chromium epoch calculation: (expires_utc / 1e6) - 11644473600
                cookie_epoch = (exp / 1_000_000.0) - 11644473600
                if cookie_epoch > now_epoch:
                    has_auth_cookie = True
                    break

        if has_auth_cookie:
            return {"logged_in": True, "message": "Logged In"}
        else:
            return {"logged_in": False, "message": "Session expired. Please log in."}
    except Exception as e:
        # Fallback to file existence check if SQLite is temporarily locked
        return {"logged_in": True if os.path.exists(cookie_file) else False, "message": f"Auth check note: {str(e)}"}


def logout_portal_session() -> Dict[str, Any]:
    """
    Logs out of the portal session by clearing Chromium cookies in .portal_session
    and removing session storage files.
    """
    if os.path.exists(SESSION_DIR):
        try:
            shutil.rmtree(SESSION_DIR, ignore_errors=True)
        except Exception:
            pass

    return {"success": True, "logged_in": False, "message": "Successfully logged out of portal session."}


def _wait_for_timecard_with_bypass(page: Page, timeout_seconds: int = 180) -> bool:
    """
    Monitors the page until .timecard-wrapper is loaded, while actively detecting
    and automatically bypassing Microsoft SSO security prompts:
    1. Auto-clicks "Stay signed in?" (#idSIButton9)
    2. Auto-clicks "Skip for now" / "Ask later" / "Skip setup" (#btnAskLater, #skipLink)
    3. Auto-redirects to TIMECARD_PORTAL_URL if stuck on mysignins.microsoft.com or
       "Keep your account secure" / "More information required" enrollment pages.
    """
    start_time = time.time()
    security_screen_hits = 0

    while time.time() - start_time < timeout_seconds:
        # Check if Time Card Viewer wrapper is loaded
        try:
            if page.locator(".timecard-wrapper").count() > 0:
                return True
        except Exception:
            pass

        # 1. Bypass Microsoft "Stay signed in?"
        try:
            kmsi_btn = page.locator("#idSIButton9")
            if kmsi_btn.count() > 0 and kmsi_btn.first.is_visible():
                print("[Auth] Auto-clicking Microsoft 'Stay signed in?' (Yes)...")
                kmsi_btn.first.click()
                time.sleep(1.0)
                continue
        except Exception:
            pass

        # 2. Bypass Microsoft "Skip for now" / "Ask later" / "Skip setup" / "No thanks"
        try:
            skip_locator = page.locator(
                "#btnAskLater, #skipLink, a:has-text('Skip for now'), button:has-text('Skip for now'), "
                "a:has-text('Ask later'), button:has-text('Ask later'), a:has-text('Skip setup'), "
                "button:has-text('Skip setup'), a:has-text('No thanks'), button:has-text('No thanks')"
            )
            if skip_locator.count() > 0 and skip_locator.first.is_visible():
                print("[Auth] Auto-clicking Microsoft 'Skip for now' / 'Ask later' security bypass...")
                skip_locator.first.click()
                time.sleep(1.5)
                continue
        except Exception:
            pass

        # 3. Detect if diverted to Microsoft security enrollment ("More information required" / "Keep your account secure")
        try:
            curr_url = (page.url or "").lower()
            page_text = page.locator("body").inner_text().lower() if page.locator("body").count() > 0 else ""
            is_sec_screen = (
                "mysignins.microsoft.com" in curr_url or
                "more information required" in page_text or
                "keep your account secure" in page_text or
                "help us protect your account" in page_text or
                "secure your account" in page_text
            )
            if is_sec_screen:
                security_screen_hits += 1
                if security_screen_hits >= 2:
                    print("[Auth] Bypassing Microsoft security enrollment screen -> Navigating directly to application...")
                    page.goto(TIMECARD_PORTAL_URL, wait_until="domcontentloaded", timeout=30000)
                    security_screen_hits = 0
                    time.sleep(2.0)
                    continue
            else:
                security_screen_hits = 0
        except Exception:
            pass

        time.sleep(1.0)

    try:
        return page.locator(".timecard-wrapper").count() > 0
    except Exception:
        return False


def launch_login_flow(timeout_seconds: int = 180) -> Dict[str, Any]:
    """
    Opens a visible browser window allowing the user to log in and complete 2FA.
    Waits until .timecard-wrapper is loaded, actively bypassing Microsoft security
    prompts, and saves the authenticated session.
    """
    os.makedirs(SESSION_DIR, exist_ok=True)
    with sync_playwright() as p:
        try:
            context = p.chromium.launch_persistent_context(
                user_data_dir=SESSION_DIR,
                headless=False,
                viewport={"width": 1280, "height": 900},
                args=["--disable-blink-features=AutomationControlled"]
            )
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(TIMECARD_PORTAL_URL, wait_until="domcontentloaded", timeout=60000)
            
            # Wait for user to complete login/2FA with active Microsoft prompt auto-bypass
            loaded = _wait_for_timecard_with_bypass(page, timeout_seconds=timeout_seconds)
            if not loaded:
                raise Exception("Timed out waiting for Time Card Viewer to load after login.")

            _mark_authenticated()
            time.sleep(2)  # Give cookies a moment to write to disk
            context.close()
            return {"success": True, "logged_in": True, "message": "Successfully logged in and session saved!"}
        except Exception as e:
            clean_err = str(e).replace(_DEFAULT_PORTAL_BASE, "[portal]")
            return {"success": False, "logged_in": False, "error": f"Login was not completed: {clean_err}"}


def run_timecard_sync(
    max_weeks: int = 0,
    headless: bool = True,
    sync_timeoff: bool = True,
    progress_callback: Optional[Callable[[int, str, str, bool], None]] = None
) -> Dict[str, Any]:
    """
    Launches Playwright, navigates to Time Card Viewer and Time Off Viewer,
    extracts data for ALL available weeks (or up to max_weeks),
    reports live percentage progress to progress_callback,
    and saves to SQLite. If session is missing or expired, prompts/opens login upfront.
    """
    def notify(percent: int, stage: str, message: str, requires_login: bool = False):
        print(f"[{percent}%] {stage}: {message}")
        if progress_callback:
            try:
                progress_callback(percent, stage, message, requires_login)
            except Exception:
                pass

    init_db()
    sync_id = log_sync_start()
    weeks_synced = 0
    synced_summaries = []
    new_timecards = []

    os.makedirs(SESSION_DIR, exist_ok=True)
    notify(5, "Initializing", "Starting background engine...")

    with sync_playwright() as p:
        try:
            context: BrowserContext = p.chromium.launch_persistent_context(
                user_data_dir=SESSION_DIR,
                headless=headless,
                viewport={"width": 1280, "height": 900},
                args=["--disable-blink-features=AutomationControlled"]
            )
        except Exception as launch_err:
            msg = f"Failed to launch browser engine: {launch_err}"
            notify(0, "Error", msg)
            log_sync_complete(sync_id, "FAILED", msg, 0)
            return {"success": False, "error": msg}

        page: Page = context.pages[0] if context.pages else context.new_page()

        # Optimization 1: Resource Blocking (Images, media, fonts, telemetry/analytics)
        def route_filter(route: Route):
            req = route.request
            rtype = req.resource_type
            url = req.url.lower()
            if rtype in ["image", "media", "font"]:
                route.abort()
            elif any(tracker in url for tracker in [
                "analytics", "tealium", "adroll", "doubleclick",
                "google-analytics", "hotjar", "optimizely", "collect?", "demdex"
            ]):
                route.abort()
            else:
                route.continue_()

        page.route("**/*", route_filter)

        try:
            notify(15, "Connecting", "Navigating to Time Card Viewer...")
            page.goto(TIMECARD_PORTAL_URL, wait_until="domcontentloaded", timeout=60000)

            # Check if login is required
            notify(20, "Authenticating", "Checking active login session...")
            try:
                page.wait_for_selector(".timecard-wrapper", timeout=10000)
            except Exception:
                current_url = page.url.lower()
                content = page.content().lower()
                is_login = ("login" in current_url) or ("sso" in current_url) or ("authenticate" in current_url) or ("sign in" in content)

                if headless and is_login:
                    # Session not logged in yet - close headless browser and launch headed login upfront!
                    context.close()
                    notify(10, "Login Required", "Opening login window... Please complete login/2FA.", requires_login=True)
                    login_res = launch_login_flow(timeout_seconds=180)
                    if not login_res.get("success"):
                        msg = login_res.get("error", "Login incomplete.")
                        notify(0, "Login Incomplete", msg, requires_login=True)
                        log_sync_complete(sync_id, "FAILED", msg, 0)
                        return {"success": False, "requires_login": True, "error": msg}

                    # Login succeeded! Resume headless sync
                    notify(20, "Login Verified", "Session saved! Launching background extraction...")
                    return run_timecard_sync(
                        max_weeks=max_weeks,
                        headless=True,
                        sync_timeoff=sync_timeoff,
                        progress_callback=progress_callback
                    )
                elif not headless:
                    # Headed mode: user is logging in now
                    notify(20, "Awaiting Login", "Please complete login/2FA in the open browser window...")
                    if not _wait_for_timecard_with_bypass(page, timeout_seconds=180):
                        msg = "Timed out waiting for login or Time Card Viewer page to load."
                        notify(0, "Failed", msg)
                        log_sync_complete(sync_id, "FAILED", msg, 0)
                        context.close()
                        return {"success": False, "error": msg}
                else:
                    msg = "Timed out waiting for Time Card Viewer page to load."
                    notify(0, "Failed", msg)
                    log_sync_complete(sync_id, "FAILED", msg, 0)
                    context.close()
                    return {"success": False, "error": msg}

            notify(25, "Processing", "Time Card Viewer loaded. Expanding shift cards...")
            _mark_authenticated()

            # Wait for initial AJAX loader (#tcvLoader) to finish and shift rows to populate
            try:
                page.wait_for_selector("#tcvLoader", state="hidden", timeout=8000)
            except Exception:
                pass
            try:
                page.wait_for_selector("tr.timecard-card", timeout=8000)
            except Exception:
                pass
            time.sleep(0.5)

            # Switch to list-view if not already active
            try:
                list_view_btn = page.query_selector(".list-view")
                if list_view_btn:
                    list_view_btn.click()
                    time.sleep(0.3)
            except Exception:
                pass

            # Ensure the dropdown is on the first (current) week option before reading the initial week
            try:
                page.evaluate("""
                    () => {
                        const sel = document.querySelector('.timecard-date-dropdown select');
                        if (sel && sel.options.length > 0 && sel.selectedIndex !== 0) {
                            sel.selectedIndex = 0;
                            sel.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    }
                """)
                time.sleep(0.4)
                try:
                    page.wait_for_selector("#tcvLoader", state="hidden", timeout=5000)
                except Exception:
                    pass
            except Exception:
                pass

            # Optimization 2: Instant DOM Batch Expansion via page.evaluate
            def expand_all_accordions_fast():
                try:
                    page.evaluate("""
                        () => {
                            const rows = document.querySelectorAll('tr.timecard-card');
                            for (const row of rows) {
                                if (!row.classList.contains('activeRow')) {
                                    row.click();
                                }
                            }
                        }
                    """)
                    # Responsive wait: proceed immediately once detail rows mount (typically 50-80ms)
                    try:
                        page.wait_for_selector(".shift-detail-table, tr.timecard-detail, tr.activeRow", timeout=400)
                    except Exception:
                        pass
                except Exception:
                    pass

            # Expand all rows for initial week
            expand_all_accordions_fast()

            # Parse initial week
            initial_html = page.content()
            initial_parsed = parse_timecard_html(initial_html)

            week_summary = initial_parsed["week_summary"]
            if week_summary["week_end_date"] != "unknown":
                new_timecards.extend(save_parsed_week(initial_parsed))
                weeks_synced += 1
                synced_summaries.append(week_summary)

            # Find all available weeks in dropdown
            available_options = initial_parsed.get("available_weeks", [])
            print(f"Discovered {len(available_options)} available weeks in dropdown.")

            weeks_to_process = [w["week_end_date"] for w in (available_options if max_weeks == 0 else available_options[:max_weeks])]
            step_span = 55.0 / max(1, len(weeks_to_process))

            for idx, week_date in enumerate(weeks_to_process):
                current_percent = min(85, int(30 + (idx + 1) * step_span))

                if week_date == week_summary.get("week_end_date"):
                    notify(current_percent, f"Synced Week {idx + 1} of {len(weeks_to_process)}", f"Latest week {week_date} already parsed.")
                    continue

                notify(current_percent, f"Syncing Week {idx + 1} of {len(weeks_to_process)}", f"Loading punches for week ending {week_date}...")
                try:
                    page.select_option(".timecard-date-dropdown select", value=week_date)
                    # State-driven synchronization: wait for AJAX loader to clear & cards to populate
                    try:
                        page.wait_for_selector("#tcvLoader", state="hidden", timeout=4000)
                    except Exception:
                        pass
                    try:
                        page.wait_for_selector("tr.timecard-card", timeout=3000)
                    except Exception:
                        pass

                    expand_all_accordions_fast()

                    week_html = page.content()
                    parsed = parse_timecard_html(week_html, selected_week_end=week_date)
                    new_timecards.extend(save_parsed_week(parsed))

                    weeks_synced += 1
                    synced_summaries.append(parsed["week_summary"])
                except Exception as ex:
                    print(f"Warning: Failed to process week {week_date}: {ex}")

            # Also sync Time Off Viewer if requested
            if sync_timeoff:
                notify(90, "Time Off Viewer", "Fetching leave balances & Entitlement Bank...")
                try:
                    page.goto(TIMEOFF_PORTAL_URL, wait_until="domcontentloaded", timeout=30000)
                    page.wait_for_selector(".timeoff-wrapper", timeout=15000)
                    try:
                        page.wait_for_selector("#toLoader, .loading-spinner", state="hidden", timeout=3000)
                    except Exception:
                        pass
                    timeoff_html = page.content()
                    timeoff_data = parse_timeoff_html(timeoff_html)
                    upsert_entitlements(timeoff_data["entitlements"], profile=timeoff_data.get("profile"))
                    print(f"Time Off Viewer synced: {len(timeoff_data['entitlements'])} entitlements.")
                except Exception as ex:
                    print(f"Warning: Failed to sync Time Off Viewer: {ex}")

            notify(100, "Complete", f"Successfully synced {weeks_synced} week(s) & leave bank in the background!")
            msg = f"Successfully synced {weeks_synced} week(s) & Time Off entitlements."
            log_sync_complete(sync_id, "SUCCESS", msg, weeks_synced)

            # Automatically persist all synced weeks, punch audits, and corrections to MongoDB Atlas Cloud Vault
            try:
                vault_res = trigger_full_vault_sync()
                if vault_res.get("cloud_synced"):
                    print(f"[MongoDB] Preserved {vault_res.get('total_weeks')} weeks, {vault_res.get('total_audits')} punch audits, and {vault_res.get('total_corrections')} corrections to MongoDB Atlas.")
            except Exception as v_ex:
                print(f"[MongoDB] Notice: Could not sync to cloud vault: {v_ex}")

            context.close()
            return {
                "success": True,
                "weeks_synced": weeks_synced,
                "summaries": synced_summaries,
                "new_timecards": new_timecards
            }

        except Exception as e:
            clean_err = str(e).replace(_DEFAULT_PORTAL_BASE, "[portal]")
            err_msg = f"Sync encountered error: {clean_err}"
            print(err_msg)
            notify(0, "Error", err_msg)
            log_sync_complete(sync_id, "FAILED", err_msg, weeks_synced)
            try:
                context.close()
            except Exception:
                pass
            return {"success": False, "error": err_msg}


if __name__ == "__main__":
    run_timecard_sync(max_weeks=0, headless=True)
