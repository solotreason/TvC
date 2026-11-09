# Project: Timecard & Time Off Tracker

## Status & Phases

### Completed Sprint: Codebase Optimization, Dead Code Removal & Efficiency Overhaul

- [x] 1. **Phase 1: Clean Up Dead Files & Asset Bloat**:
  - Deleted dead `package.json` dummy file.
  - Removed committed Gradle build report (`android/build/reports/problems/problems-report.html`).
  - Updated `.gitignore` to comprehensively ignore all Gradle build output trees (`android/**/build/`, `android/build/`).
  - Updated `sync_android_assets()` in `run.py` to strictly filter out `*.zip` archives and purged `ota_bundle.zip` from assets.
  - Removed obsolete `seed_data.js` cleanup lines in `run.py`.
  - Result: standalone APK size reduced from **0.41 MB** down to **0.23 MB** (~45% reduction).

- [x] 2. **Phase 2: Python Backend Efficiency & Dead Code Cleanup**:
  - Removed dead module variable `_last_archive_signature` in `backend/database.py`.
  - Replaced redundant N+1 SQLite queries and repeated `json.dump` disk writes on `GET /api/weeks` with a smart dirty-check that only persists newly introduced archived weeks.
  - Converted `clear_all_data()` into a single atomic `executescript` transaction.
  - Cleaned up misplaced `import json` in `backend/app.py`.
  - Updated `last_synced_at` in `backend/mongo_vault.py` to use standard UTC ISO timestamps.
  - Removed redundant cookie deletions prior to `shutil.rmtree` in `backend/scraper.py`.

- [x] 3. **Phase 3: Frontend JavaScript & CSS Optimization**:
  - Removed dead statement invocations of `isWaitingForDailyTimecard()` in `static/app.js`.
  - Removed non-existent element fallback `#syncMessage`.
  - Removed destructive desktop web `localStorage.clear()` on page load so browser users maintain persistent preferences.
  - Consolidated triple loop in `renderShiftsTable` (`static/app.js`) into a single pass computing rows, totals, and mobile cards simultaneously.
  - Fixed missing stream `networkRes.clone()` in `static/sw.js` and bypassed dynamic status endpoints (`/api/status`, `/api/auth/`, `/api/ota/`, `/api/sync`) from cache staleness.
  - Removed dead `.app-header` CSS rule in `static/styles.css`.

- [x] 4. **Phase 4: Android Java (`MainActivity.java`) Optimizations**:
  - Replaced chunked UTF-8 string decoding in OTA downloader with `ByteArrayOutputStream` to prevent multi-byte character split corruption.
  - Replaced fragile partial buffer reads across `getPortalBaseUrl()`, `loadStateJson()`, `loadArchiveJson()`, and `saveArchiveJson()` with a dedicated `readFileFully()` helper.
  - Offloaded multi-megabyte JSON parsing and disk writes in `saveArchiveJson()` to a background worker thread.
  - Updated `Notification.Builder` to channel-aware constructor and removed obsolete `setDatabaseEnabled(true)`.
  - Added `startBackgroundSync()` bridge alias.

- [x] 5. **Phase 5: Rebuild & Full Verification**:
  - Repacked OTA bundle with `python run.py --pack-ota` without asset duplication.
  - Recompiled standalone APK (`Timecard-Tracker.apk`) in 799ms (size: 0.23 MB).
  - All 19 automated unit tests passing (100% pass rate).

## Review Summary
- **App Size & Memory**: Cut APK size nearly in half (0.41 MB -> 0.23 MB).
- **Disk I/O & CPU**: Eliminated disk thrashing on every `GET /api/weeks` read call; shifted archive saving off the UI thread.
- **Reliability & Code Quality**: Fixed UTF-8 chunk decoding, removed dead/obsolete files, and verified 100% backward compatibility.
