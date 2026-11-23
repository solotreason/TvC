# Project: Timecard & Time Off Tracker

## Status & Phases

### Current Sprint: Complete Alerts (Timecards, Punch Audits & Option Days), Exclude Prepaid Vacation & Clean Archive Header (v2.4.9)

- [x] 1. **Alerts for Timecard Added, Punch Audits & Option Days (`backend/database.py`, `backend/scraper.py`, `backend/app.py`, `static/android_bridge.js`, `static/app.js`, `TimecardSyncJobService.java`)**:
  - Tracked newly detected punch modifications (`new_audits` for Start Time, End Time, Meal/Unpaid) alongside `new_timecards` and `option_day_alerts` in `backend/database.py`, `backend/scraper.py`, `backend/app.py`, and `static/android_bridge.js` (`getNewAuditsJson`).
  - Added `notifyPunchAuditsDetected` in `static/app.js` and wired `checkSyncStatus()` so in-app banners, native Android notifications, and browser notifications fire for all three events: (1) completed daily timecards added, (2) punch modification audits, and (3) Option Days used (daily row or Time Off bank decrease).

- [x] 2. **Suppress All Alerts for `"VACATION - EMPLOYEE PREPAID"` (`backend/database.py`, `static/android_bridge.js`, `static/app.js`, `TimecardSyncJobService.java`)**:
  - Added `is_excluded_alert_pay_code` / `isExcludedAlertPayCode` / `isExcludedAlertShift` across Python, JS, and Android Java to strictly exclude `"VACATION - EMPLOYEE PREPAID"` (and prepaid vacation detail segments) from triggering timecard, Option Day, or punch audit alerts.
  - Treated `"VACATION - EMPLOYEE PREPAID"` as a settled day in `isWaitingForDailyTimecard` and `isTodayShiftCompletedInStore` so background sync throttles to quiet intervals on vacation days.

- [x] 3. **Remove `'Permanent Vault Active'` Badge Next to `'Historical Timecard Archive'` (`static/index.html`)**:
  - Removed `#archiveVaultBadge` (`Permanent Vault Active`) next to `Historical Timecard Archive` in `static/index.html` while maintaining the permanent local & MongoDB cloud archive vault feature.

- [x] 4. **Verify, Package OTA + APK, Push & Purge CDN (v2.4.9)**:
  - Added unit test assertions in `tests/test_features.py` for `"VACATION - EMPLOYEE PREPAID"` alert suppression and punch audit / Option Day alert generation.
  - Ran `python -m unittest discover tests -v`, `python run.py --pack-ota --ver 2.4.9`, pushed to `origin/main`, and purged jsDelivr CDN cache.

### Completed Sprint: UI Polish, Android Pull-Down Refresh & Conditional APK Update Button (v2.4.8)

- [x] 1. **Remove 'Permanent Vault Active' from Footer (Keep Feature Active) (`static/index.html`)**:
  - Removed the `Permanent Vault Active` badge and extra separator from the footer while keeping the permanent vault feature and Archive tab intact.

- [x] 2. **Remove Pull-Down to Refresh (Use Header Sync Button Only) (`static/index.html`, `static/app.js`, `README.md`)**:
  - Removed `#pullRefreshContainer` from `static/index.html` and `setupPullToRefresh()` from `static/app.js` so refreshing the current week uses the header **Sync** button exclusively.

- [x] 3. **Hide Top-Left Header 'Log In' Button When 'Sign In Required' Gate is Shown (`static/index.html`, `static/app.js`)**:
  - Hid `#authBtn` whenever `#loginRequiredView` ("Sign In Required") is displayed (`!showDashboard`), and only showed `#authBtn` once the dashboard is visible (`showDashboard`).

- [x] 4. **Show 'Install Native App Update (.APK)' Strictly When Available/Necessary (`static/app.js`, `static/android_bridge.js`, `MainActivity.java`)**:
  - Removed the fallback that unhid `#otaModalInstallApkBtn` when `info.apk_update_available` was `false`; explicitly hid both `#otaModalInstallApkBtn` and `#apkUpdateBanner` whenever `apk_update_available` is `false`.
  - Verified with unit tests (20/20 passed), built `v2.4.8` OTA + APK, pushed to `origin/main`, and purged jsDelivr CDN cache.

### Completed Sprint: Seamless OTA Auto-Apply + 1-Tap In-App Native APK Self-Updater (v2.4.7)

- [x] 1. **Native In-App APK Self-Updater (`AndroidManifest.xml`, `ApkFileProvider.java`, `MainActivity.java`)**:
  - Added `android.permission.REQUEST_INSTALL_PACKAGES` and registered a zero-dependency `ApkFileProvider` (`android.content.ContentProvider`) in `AndroidManifest.xml` to serve downloaded `.apk` files via `content://com.timecard.tracker.apkprovider/...` with `FLAG_GRANT_READ_URI_PERMISSION`.
  - Exposed `getApkBuild()`, `apk_build`, and `apk_url` in `MainActivity.getCurrentOtaInfo()` and `AndroidNativeBridge`.
  - Implemented `downloadAndInstallApk(String customUrl)` in `MainActivity.java`: checks `getPackageManager().canRequestPackageInstalls()` on Android 8+ (opening `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` and resuming install automatically once granted), downloads `Timecard-Tracker.apk` with failover (`raw.githubusercontent.com` -> `cdn.jsdelivr.net` / `github.com`), validates APK magic header (`PK`), and launches the native Android package installer prompt.
  - Added `cleanupStaleOtaIfFactoryNewer()` on app launch so upgrading the APK automatically clears older OTA web bundles that would otherwise shadow newer factory assets.

- [x] 2. **OTA Auto-Apply + Native APK Update Prompt in Web UI (`static/index.html`, `static/android_bridge.js`, `static/app.js`, `backend/app.py`)**:
  - Auto-applied downloaded OTA web bundles seamlessly in `MainActivity.performOtaCheckAndDownload` when no sync is actively running (`dashboardWebView.clearCache(true); dashboardWebView.reload()`).
  - Detected when `remote.apk_build > local_apk_build` (or when a pre-v2.4.7 APK lacks `getApkBuild`) during OTA check and startup, surfacing a prominent **1-Tap "Install APK Update"** banner & modal action button (`#otaModalInstallApkBtn`, `#apkUpdateBanner`).
  - Displayed both **Web Bundle Build** and **Native APK Build** in the Updates modal (`#otaModal`).

- [x] 3. **Unified OTA + APK Packaging & Git Tracking (`.gitignore`, `run.py`, `README.md`)**:
  - Tracked `Timecard-Tracker.apk` (`~280 KB`) in git (`!Timecard-Tracker.apk` in `.gitignore`) and pointed `version.json`'s `apk_url` to `https://raw.githubusercontent.com/solotreason/TvC/main/Timecard-Tracker.apk` with `apk_build`.
  - Updated `python run.py --pack-ota` so it automatically syncs version/build numbers across `version.json`, `static/index.html`, `MainActivity.java`, `android/app/build.gradle`, and `README.md`, builds `static/ota_bundle.zip`, and compiles `Timecard-Tracker.apk` in one step.
  - Updated `README.md` to document `v2.4.7` and the unified OTA + Native APK self-update flow.

- [x] 4. **Verify, Package OTA + APK, Push & Purge CDN (v2.4.7)**:
  - Added unit test coverage in `tests/test_features.py` for `ApkFileProvider`, `REQUEST_INSTALL_PACKAGES`, and APK self-update contracts.
  - Ran `python -m unittest discover tests -v` (20/20 passed), `python run.py --pack-ota --ver 2.4.7`, committed & pushed `Timecard-Tracker.apk` + `ota_bundle.zip` + `version.json`, and ran `python run.py --purge-cdn`.

### Completed Sprint: Precision Notifications (Completed Shift, Punch Edits & Option Days) + Shift-Aware Fast Polling (v2.4.6)

- [x] 1. **Option Day & Completed-Only Shift Detection Across Backend, JS Bridge & UI (`backend/database.py`, `static/android_bridge.js`, `static/app.js`)**:
  - Added `isOptionDayPayCode` / `isOptionDayShift` detection for Option Day & Option Week pay codes (`OPT`, `OPTION DAY`, `OPT DAY`, `OPW`, `OPTION WEEK`, etc.) on daily timecard rows and details.
  - Updated `upsert_shift` (`backend/database.py`) and `upsertParsedWeek` (`static/android_bridge.js`) so `new_timecards` fires strictly when a shift becomes **completed** (both start & end punches + `total_hours > 0`, never on clock-in-only punches) OR when an **Option Day** is posted on a daily row (even without clock-in/out punches).
  - Tracked Option Day / Option Week entitlement balance decreases in `upsertParsedTimeoff` (`static/android_bridge.js`) and exposed `getOptionDayAlertsJson` so both foreground and background syncs detect Time Off Viewer Option Day usage.
  - Updated `notifyDailyTimecardAdded` and `isWaitingForDailyTimecard` in `static/app.js` to format Option Day notifications (`"Option Day Used (<date>)"`) and treat Option Day / Scheduled Off days as finalized for the day.

- [x] 2. **Closed-App Background Notification Filtering & Time Off Bank Sync (`TimecardSyncJobService.java` + `PortalHelper.java`)**:
  - Updated `TimecardSyncJobService.notifyForChanges` to notify strictly on:
    1. **Completed daily timecard posted** (never on incomplete clock-in-only punches).
    2. **Supervisor punch modifications** (`audit_logs` — Start Time, End Time, Meal/Unpaid).
    3. **Option Day used** — both when a daily timecard row posts with an Option Day / Option Week pay code AND when the Time Off Viewer Option Day / Option Week balance drops.
  - Extended `TimecardSyncJobService` to periodically/on-change inspect `PortalHelper.getTimeoffPortalUrl` (`time-off-viewer.html`) with DOM fingerprint short-circuiting (`PREF_LAST_TIMEOFF_FINGERPRINT`, `PREF_LAST_TIMEOFF_SYNC_MS`) and ingest via `window.TimecardBridge.ingestTimeoffHtml` in `bridgeWebView`.

- [x] 3. **Smart Shift-Aware Background Polling (`TimecardSyncJobService.java`)**:
  - Learned typical shift end hour (`estimateTypicalEndHourDec`) from recent completed shifts in `timecard_store_v4.json` (plus active clock-in detection).
  - Polls at peak speed (`5m` latency / `8m` deadline, `4m` min gap) during the shift-end window or when an active punch is open; polls every `10–12m` during normal daytime while today's card is unposted; throttles to `2h` overnight (`1 AM – 5 AM` when no night shift/open punch) and `3h` once today's timecard or Option Day is posted.

- [x] 4. **Verify, Package OTA, Build APK & Push (v2.4.6)**:
  - Added unit tests in `tests/test_features.py` covering completed-only shift detection (ignoring clock-in-only), Option Day pay code detection, and Option Day entitlement bank drop detection.
  - Fixed OTA update check staleness in `MainActivity.java`, `backend/app.py`, and `run.py`:
    - Prioritized cache-busted `https://raw.githubusercontent.com/solotreason/TvC/main/version.json?t=<timestamp>` and `static/ota_bundle.zip?t=<build>` ahead of `cdn.jsdelivr.net` (which caches `@main` for up to 12–24h unless purged).
    - Added `Cache-Control: no-cache` headers and `setUseCaches(false)` on OTA HTTP connections.
    - Updated `run.py --pack-ota` to auto-synchronize `static/index.html`, `MainActivity.java`, and `android/app/build.gradle` with the generated build number, and added `python run.py --purge-cdn` to purge jsDelivr's edge cache for devices still on older APKs.
  - Ran `python -m unittest discover tests -v` (20/20 passed), `python run.py --pack-ota` (`191.6 KB`, Build `2026111404`), `python run.py --build-apk` (`Timecard-Tracker.apk`, `0.28 MB`, `BUILD SUCCESSFUL`), pushed to `origin/main`, and purged jsDelivr edge cache.

### Completed Sprint: 10-Min Fast Background Check, Adaptive Launcher Icon & Transparent Notification Logo (v2.4.5)

- [x] 1. **10-Minute Deadline-Bounded Background Sync + 15-Minute Periodic Safety Net (`TimecardSyncJobService.java` + `static/app.js`)**:
  - Switched `PERIODIC_INTERVAL_MS` to 15m (`5m` flex) as a persisted safety net and added `FAST_JOB_ID = 2027` (`10m` minimum latency, `12m` override deadline) that self-chains while waiting for today's shift so checks never drift to 45 minutes.
  - Lowered `ACTIVE_MIN_GAP_MS` to 8 minutes and foreground `SMART_CHECK_INTERVAL_MS` to 10 minutes (`5m` on resume), while keeping the 3-hour post-completion slowdown and SHA-256 unchanged-DOM short-circuit.

- [x] 2. **Fix Solid-White Android Notification Bar Icon (`ic_stat_tvc.xml` + `PortalHelper.java`)**:
  - Created `res/drawable/ic_stat_tvc.xml` (`24dp` monochrome silhouette with 100% transparent background and crisp white clock/TvC paths).
  - Updated `PortalHelper.postShiftNotification` to use `R.drawable.ic_stat_tvc` and brand accent color `#F59E0B`.

- [x] 3. **Remove White Background from TvC App & Web Logos (`mipmap-anydpi-v26`, `ic_launcher`, `favicon`, `icon-192.png`, `icon-512.png`)**:
  - Added Android 8+ `<adaptive-icon>` (`ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`) and wired into `AndroidManifest.xml` so Android never wraps the launcher icon in a white plate.
  - Regenerated `static/favicon.png`, `static/icon-192.png`, `static/icon-512.png`, `static/favicon.svg`, and `static/logo.svg` with transparent RGBA backgrounds and crisp `TvC` typography.

- [x] 4. **Verify, Package OTA, Build APK & Push (v2.4.5)**:
  - Ran unit tests, `--pack-ota`, `--build-apk`, and pushed to `origin/main`.

### Completed Sprint: Battery-Smart Closed-App Background Sync (v2.4.4)

- [x] 1. **Zero-WebView Adaptive Battery Gate (`TimecardSyncJobService.java`)**:
  - Updated periodic `JobScheduler` interval to 30m (10m flex) and verified existing interval before rescheduling.
  - Added `shouldSkipForBatteryEfficiency` pre-flight gate before any WebView creation: skips when battery < 15% and unplugged, throttles to 2h in Battery Saver mode, and slows to 3h once today's completed shift is saved (staying at active ~30m checks while waiting or charging).

- [x] 2. **Unchanged-DOM Fingerprint Short-Circuit (`TimecardSyncJobService.java` + `PortalHelper.java`)**:
  - Computed SHA-256 fingerprint of weekly summary + daily rows + last-updated header inside `headlessWebView` before clicking accordions.
  - When unchanged (and no non-finalized rollover week exists), skips accordion expansion, skips `outerHTML` serialization, and skips launching `bridgeWebView` completely.
  - Cleared background sync prefs/fingerprints cleanly on logout (`PortalHelper.clearBackgroundSyncPrefs`).

- [x] 3. **Dismissible Battery Advisory Banner & Version Sync (`static/index.html` + `static/app.js`)**:
  - Added dismiss button (`#dismissBatteryOptBtn`) remembered in `localStorage`.

### Completed Sprint: True Closed-App Background Sync & Instant Disk Refresh (v2.4.3)

- [x] 1. **Shared Portal & Storage Helper (`PortalHelper.java`)**:
  - Extracted shared constants (`STORE_FILENAME`, `ARCHIVE_FILENAME`, `OTA_DIR_NAME`, `AUTH_MARKER_FILENAME`, `NOTIFICATION_CHANNEL_ID`, portal URL resolution) and synchronized atomic JSON file read/write utilities (`readFileFully`, `writeTextFileAtomic`).
  - Centralized Microsoft KMSI/security interstitial auto-bypass, background asset blocking, cookie auth verification, OTA/asset resource loading, native shift notification formatting, archive vault merging, and battery optimization helpers for both `MainActivity` and `TimecardSyncJobService`.

- [x] 2. **Upgrade Closed-App `TimecardSyncJobService.java`**:
  - Preserved existing periodic `JobScheduler` schedule (`getPendingJob`) instead of resetting its timer on every app launch.
  - Performed headless WebView portal loading with persistent cookies, KMSI/security bypass, interactive login early-exit guard, and 55s battery watchdog.
  - Expanded `.list-view` and all `tr.timecard-card` accordions, captured full week HTML (plus previous week on rollover if still non-finalized), and executed `android_bridge.js` (`TimecardBridge.ingestWeekHtml`) inside an offline local bridge WebView so `timecard_store_v4.json` and `timecard_archive_v1.json` are completely updated while the app is closed.
  - Triggered deduplicated native Android notifications for newly posted/completed shifts and punch audit changes.

- [x] 3. **Instant Foreground Resume from Disk & Battery Saver Guidance**:
  - Exposed `getStoreLastModified()`, `isIgnoringBatteryOptimizations()`, and `requestIgnoreBatteryOptimizations()` in `AndroidNativeBridge`.
  - Added `TimecardBridge.reloadFromDiskIfUpdated()` in `static/android_bridge.js` and wired it into `onAppResumedSmartCheck()` / `visibilitychange` in `static/app.js` so background updates render in 0ms on resume.
  - Added `#batteryOptNotice` Android battery optimization advisory banner in `static/index.html` + `static/app.js` when background execution is throttled, and declared `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in `AndroidManifest.xml`.

- [x] 4. **Verify, Package OTA & Build APK (v2.4.3)**:
  - Bumped version to `2.4.3` (Build `2026111002`) cleanly across config, Android Gradle/Java, FastAPI, and OTA bundle.
  - Ran `python -m unittest discover tests -v` (20/20 passed), `python run.py --pack-ota` (165.1 KB), and compiled `Timecard-Tracker.apk` (0.24 MB).
  - Documented results and prepared push to `origin main`.

### Completed Sprint: Comprehensive Senior Code Audit & Polish

- [x] 1. **Codebase Scan & Research**:
  - Scanned for dead code and unused imports across Android Java, Python backend, and Web/JS layers.
  - Removed unused import `fetch_cloud_archive` in `backend/app.py`.
  - Removed lingering legacy stub `loadCorrections` in `static/app.js`.
  - Verified corporate neutrality: zero hardcoded employee credentials, private corporate domains, or corporate logos in project code/commits.
  - Verified 100% of DOM IDs queried in JavaScript exist in `static/index.html`.
  - Verified all CSS classes in `styles.css` are active.

- [x] 2. **Security, Privacy & Robustness Audit**:
  - Validated Android WebView `@JavascriptInterface` security and origin checks.
  - Verified OTA zip-slip path traversal defense in `MainActivity.java` (`canonicalDest.startsWith(canonicalTarget)`).
  - Fixed duplicate notification spam bug in `TimecardSyncJobService.java`: newly posted shifts are now persisted to `timecard_store_v4.json` upon notification, preventing repeat alerts every 15 minutes.
  - Upgraded `backend/app.py` from deprecated `@app.on_event("startup")` to modern Starlette/FastAPI `lifespan` context manager.

- [x] 3. **Performance & Code Quality Audit**:
  - Verified responsive 4-tab 2x2 grid layout on mobile and horizontal tab bar on desktop.
  - Synchronized static fallback version placeholders in `static/index.html` to `v2.4.2` (Build `2026110908`).
  - Tested instant edge CDN OTA checks (<100ms) with fallback failover.

- [x] 4. **Verification & Delivery**:
  - All 19 automated unit tests passing (100% pass rate).
  - OTA bundle packaged at `static/ota_bundle.zip` (163.9 KB).
  - Standalone APK compiled at [`Timecard-Tracker.apk`](file:///c:/Users/ibert/Desktop/timecard/Timecard-Tracker.apk) (0.24 MB).

### Completed Sprint: Streamline Navigation (Remove Corrections Tab) & Fast Instant OTA Update Engine
- [x] 1. Removed Redundant Corrections Tab
- [x] 2. Instant OTA Update Engine & Native Bridge
- [x] 3. Verification, Packaging & Build (v2.4.2)

### Completed Sprint: Automated Closed-App Background Timecard Checking & Push Notifications
- [x] 1. Created `TimecardSyncJobService.java`
- [x] 2. Device Reboot & Boot Persistence (`BootReceiver.java`)
- [x] 3. Registered Components in `AndroidManifest.xml`
- [x] 4. Integrated Job Scheduling in `MainActivity.java`
- [x] 5. Standalone APK compiled & 19 unit tests passing

## Review Summary
- **No Foreground App Needed**: The phone will now check for new daily timecards in the background even if the app is completely closed or swiped away.
- **Immediate Alerts**: As soon as a supervisor posts a new shift punch, a native Android notification appears on the user's lock screen.
- **Reboot Resilient**: The scheduled job survives device reboots and updates automatically.
