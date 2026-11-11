# Project: Timecard & Time Off Tracker

## Status & Phases

### Current Sprint: True Closed-App Background Sync & Instant Disk Refresh (v2.4.3)

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
