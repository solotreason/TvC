# TvC - Timecard Viewer 🕒📋🌴
**Version 2.5.0 (Build 2026112203)**

A fast, private, personal web dashboard and standalone Android application for tracking and auditing timecards, punch modifications, audit histories, and leave entitlements. Built with Python, FastAPI, SQLite, MongoDB Atlas cloud vault, Tailwind CSS, and Playwright for automated background sync.

---

## Key Features

### 1. DOT Weekly Remaining Hours Calculator 🚛
- **Federal DOT Compliance**: Automatically tracks remaining on-duty hours (`60.00h` standard DOT limit) to prevent out-of-service violations.
- **Live Visual Status**: Dynamic color badges indicating safety status (`Safe`, `Caution`, `Warning`, or `DOT Limit Reached`).

### 2. Punch Modification Auditor 🔍
- **Targeted Monitoring**: Detects whenever a supervisor or automated system modifies:
  - **Start Time**
  - **End Time**
  - **Meal / Unpaid Deduction**
- **Minute-by-Minute Diffing**: Records the exact minute diff (e.g., `+4m later`, `-15m earlier`, `+15m meal added`) with timestamps.
- **Shift Highlighting**: Flags modified shifts with alert badges directly inside the Time Card table and the dedicated **Punch Change Audits** tab.

### 3. Clean Light Theme & Decimal Punch Format 🎨
- Clean slate and amber typography and table borders (`#1e293b` primary, `#f59e0b` accent, `#dee2e6`).
- Distraction-free interface with accurate decimal punch format (`9.28`) alongside standard clock time (`9:17 AM`).

### 4. `activeRow` Multi-Segment Shift Breakdowns 📑
- Clicking any day expands the inner segment breakdown (e.g. `ROUTE DRIVER` vs `SHUTTLE DRIVER`, `$46.98/hr` base rate, Straight Time vs Overtime hours/earnings).

### 5. Time Off Viewer (`Entitlement Bank`) 🏖️
- Displays leave entitlements parsed directly from the Time Off Viewer:
  - **Vacation (VAC)**: Available hours and next earn date
  - **Sick Leave (SIC)**
  - **Option Week (OPW)**
  - **Option Day (OPT)**
  - **Birthday & Anniversary**

### 6. Smart Shift-Aware Closed-App Background Sync & Precision Notifications 🔔
- **Closed-App Background Polling (`TimecardSyncJobService`)**: Runs automatically even when the app is completely closed:
  - **5–8m Prime Post-Shift Polling**: Checks rapidly around your typical shift end window or whenever an active clock-in punch is open.
  - **10–12m Active Daytime Checks**: Keeps checking while today's shift is unposted, then throttles to **3h** once today's timecard or Option Day is posted (and **2h** overnight) with SHA-256 DOM fingerprint short-circuiting for near-zero battery usage.
- **Precision Notifications**: Alerts strictly on:
  1. **Completed Daily Timecards** (ignores clock-in-only punches until clock-out is posted).
  2. **Supervisor Punch Modifications** (`Start Time`, `End Time`, `Meal/Unpaid`).
  3. **Option Day Usage** (detected both when an `OPT`/`OPW` pay code posts on a daily timecard row and when your Time Off Viewer Option Day balance decreases).

### 7. High-Performance Architecture ⚡
- **Resource Blocking**: Automatically drops images, fonts, and heavy tracking pixels, speeding up page navigation by ~70%.
- **Instant DOM Batch Expansion**: Expands all day accordions simultaneously in ~50ms via atomic DOM evaluation.
- **Client-Side Week Caching & Shortcuts**: Switching between weeks in the dropdown is instantaneous ($0\text{ms}$ latency). Use `←` / `→` arrow keys to jump between consecutive weeks without touching your mouse.
- **SQLite WAL Mode**: Write-Ahead Logging (`WAL`) allows concurrent reads during background sync writes with zero lock contention.

---

## How to Run

### 1. Launch the Dashboard
```powershell
python run.py
```
Open **[http://localhost:8000](http://localhost:8000)** in your browser.

### 2. Log In & Sync
- **Single Auth Toggle**: Click **"Log In"** in the header to authenticate once; once signed in, the button switches to **"Log Out"**.
- **Sync All Weeks**: Initial login automatically syncs your full multi-week history and leave bank.
- **Current Week Sync**: Click **"Sync"** in the header to quickly refresh only the current pending week.

### 3. Standalone Android App, Auto-OTA & 1-Tap Native APK Updates 📱
- **100% Standalone On-Phone Operation**: Runs natively without a background server (`com.timecard.tracker`).
- **Seamless OTA + 1-Tap Native APK Self-Updater**:
  - **Web UI & Parser Updates (`ota_bundle.zip`)**: Automatically download from GitHub and apply immediately on launch.
  - **Native Android Updates (`Timecard-Tracker.apk`)**: Whenever background sync or native Java code is updated, the app displays a **1-Tap "Install APK Update"** prompt that downloads `Timecard-Tracker.apk` directly from GitHub and launches the native Android installer inside the app.
- **Build & Package Both OTA Bundle and Standalone APK in One Command**:
  ```powershell
  python run.py --pack-ota
  ```
- **Push Live Update to GitHub & Purge CDN Cache**:
  ```powershell
  git add .
  git commit -m "Release update"
  git push origin main
  python run.py --purge-cdn
  ```

### 4. Run Automated Tests
```powershell
python -m unittest discover tests
```
