# TvC - Timecard Viewer 🕒📋🌴
**Version 2.4.2 (Build 2026110908)**

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

### 6. Smart Sync Architecture 🔄
- **First-Login Full Sync (8+ Weeks)**: Automatically syncs all historical and pending weeks available in the portal dropdown on initial login or when clicking **"Sync All (8+ Weeks)"**.
- **Pull-Down Current-Week Refresh**: Pulling down from the top of the screen (or tapping **"Refresh Current Week"**) refreshes **only the active week** (`max_weeks = 1`), skipping finalized historical weeks.

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
- **Sync All Weeks**: Click **"Sync All (8+ Weeks)"** to sync your full multi-week history and leave bank.
- **Pull-Down Refresh**: Pull down from the top of the page (or click **"Refresh Current Week"**) to quickly update only the current week.

### 3. Standalone Android App & GitHub Over-The-Air (OTA) Live Updates 📱
- **100% Standalone On-Phone Operation**: Runs natively without a background server (`com.timecard.tracker`).
- **Live Over-The-Air (OTA) Updates**: Update the user interface, styling, and scraper parsers on your phone **without reinstalling the APK**!
  - Generate an update bundle:
    ```powershell
    python run.py --pack-ota
    ```
  - Push the generated files to GitHub:
    ```powershell
    git add .
    git commit -m "OTA update"
    git push origin main
    ```
  - Devices running the app will automatically detect and download the update in the background. Tap **"Reload to Apply"** to activate the new version immediately.
- **Rebuild the Standalone APK Anytime**:
  ```powershell
  python run.py --build-apk
  ```

### 4. Run Automated Tests
```powershell
python -m unittest discover tests
```
