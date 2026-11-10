# Project: Timecard & Time Off Tracker

## Status & Phases

### Completed Sprint: Automated Closed-App Background Timecard Checking & Push Notifications

- [x] 1. **Created `TimecardSyncJobService.java`**:
  - Implemented Android `JobService` scheduled periodically with `JobScheduler` (`JOB_ID = 2026`).
  - Checks persistent cookie session (`portal_auth_v4.flag`); exits immediately if logged out.
  - Spawns a lightweight headless `WebView` on the main Looper.
  - Loads Time Card Viewer portal with cookie jar attached.
  - Polls for `.timecard-wrapper` and active rows without opening any UI window.
  - Compares extracted shifts against `timecard_store_v4.json`.
  - When a newly posted shift date is detected, fires a high-priority lock-screen push notification via `NotificationManager` (`daily_timecard_channel`) with deep-link `PendingIntent` to `MainActivity`.
  - Includes a 45-second watchdog timer to guarantee resource cleanup and eliminate battery drain.

- [x] 2. **Device Reboot & Boot Persistence (`BootReceiver.java`)**:
  - Implemented `BroadcastReceiver` listening for `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`.
  - Automatically reschedules periodic closed-app checks whenever the phone reboots or the app updates.

- [x] 3. **Registered Components in `AndroidManifest.xml`**:
  - Added `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`.
  - Declared `<service android:name=".TimecardSyncJobService" android:permission="android.permission.BIND_JOB_SERVICE" android:exported="true" />`.
  - Declared `<receiver android:name=".BootReceiver" android:exported="true">`.

- [x] 4. **Integrated Job Scheduling in `MainActivity.java`**:
  - Automatically schedules closed-app background checking in `onCreate()`.
  - Automatically schedules upon successful authentication (`setAuthenticatedFlag(true)`).
  - Cancels closed-app checks when user logs out (`logoutSession()`).

- [x] 5. **Testing & Verification**:
  - Standalone APK compiled cleanly at [`Timecard-Tracker.apk`](file:///c:/Users/ibert/Desktop/timecard/Timecard-Tracker.apk) (0.24 MB).
  - All 19 automated unit tests passing (100% pass rate).

## Review Summary
- **No Foreground App Needed**: The phone will now check for new daily timecards in the background even if the app is completely closed or swiped away.
- **Immediate Alerts**: As soon as a supervisor posts a new shift punch, a native Android notification appears on the user's lock screen.
- **Reboot Resilient**: The scheduled job survives device reboots and updates automatically.
