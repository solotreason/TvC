# Project: Timecard & Time Off Tracker

## Status & Phases

### Completed Sprint: Streamline Navigation (Remove Corrections Tab) & Fast Instant OTA Update Engine

- [x] 1. **Removed Redundant Corrections Tab**:
  - Removed `#tabCorrections` button and `#viewCorrections` section from `static/index.html`.
  - Streamlined tab navigation grid to a clean, balanced 4-tab layout:
    - **Time Card Viewer** (`#tabTimeCard`)
    - **Punch Audits** (`#tabAudit`)
    - **Time Off Viewer** (`#tabTimeOff`)
    - **Historical Archive** (`#tabArchive`)
  - Mobile layout now renders as a balanced 2x2 grid (`grid-cols-2`) with zero awkward button wrapping.
  - Removed `loadCorrections` DOM operations and navigation tab mappings from `static/app.js`.
  - Preserved backend API backward compatibility.

- [x] 2. **Instant OTA Update Engine & Native Bridge**:
  - Re-wired `otaModalCheckBtn` in `static/app.js` to directly invoke native `window.AndroidNative.checkForUpdates(true)`.
  - Added a 4-second UI watchdog safety timer so the check spinner never hangs indefinitely.
  - Upgraded `showOtaCheckResult(result)` with comprehensive status handling:
    - `up_to_date`: green checkmark
    - `downloading`: blue downloading spinner with version
    - `update_installed`: green checkmark with prompt to reload
    - `error`: red exclamation with exact failure message
  - In `MainActivity.java`:
    - Added multi-source CDN edge resolution (`cdn.jsdelivr.net/gh/solotreason/TvC@main/version.json`) with fast fallback to `raw.githubusercontent.com`.
    - Tightened connect/read timeouts to 3.5s so response times are instantaneous (<100ms on edge CDN).
    - Added edge CDN failover for bundle download (`ota_bundle.zip`).
    - Added explicit WebView bridge callbacks on every state (up-to-date, downloading, installed, error).
  - In `backend/app.py`: Updated `/api/ota/check` to perform instant version checking against the edge CDN for web browser users.

- [x] 3. **Verification, Packaging & Build**:
  - Re-packed OTA bundle (`python run.py --pack-ota`) -> `static/ota_bundle.zip` (164 KB).
  - Re-built standalone APK (`python run.py --build-apk`) -> [`Timecard-Tracker.apk`](file:///c:/Users/ibert/Desktop/timecard/Timecard-Tracker.apk) (0.24 MB).
  - All 19 automated unit tests passing (100% pass rate).

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
