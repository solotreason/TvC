# Project: Timecard & Time Off Tracker

## Status & Phases

### Completed Sprint: GitHub-Powered In-App Over-The-Air (OTA) & Self-Updater (Target: `solotreason/TvC`)

- [x] 1. **OTA Asset Resolution in `MainActivity.java`**:
  - Implemented prioritized local disk asset lookup in `shouldInterceptRequest()`:
    - Inspects `getFilesDir()/ota_bundle/{relPath}`.
    - If present and valid, streams directly from local storage with correct MIME type (`text/html`, `application/javascript`, `text/css`, `image/svg+xml`, `image/png`, etc.).
    - If absent, seamlessly falls back to APK assets (`getAssets().open("www/{relPath}")`).
  - Added safety rollback mechanism: if corrupted, `resetOtaToFactory()` clears `ota_bundle/` and reloads factory APK assets.

- [x] 2. **Native OTA Download & Verification Engine in `MainActivity.java`**:
  - Implemented `performOtaCheckAndDownload(manual)`:
    - Fetches `https://raw.githubusercontent.com/solotreason/TvC/main/version.json` with cache-busting timestamp.
    - Compares remote build timestamp (`remoteBuild > currentBuild`).
    - Streams `ota_bundle.zip` into `getFilesDir()/ota_temp.zip`.
    - Unzips safely with Zip-traversal protection into `ota_staging/`.
    - Validates file structure (`index.html`, `app.js`), then performs atomic replacement of `ota_bundle/`.
    - Writes `ota_version.json` metadata.
    - Emits `window.TimecardBridge.onOtaUpdateReady(...)` to notify the dashboard UI.
  - Exposed JavascriptInterface bridge methods: `checkForUpdates(manual)`, `reloadApp()`, `resetOtaToFactory()`, `getOtaStatusJson()`, `setGitHubRepo(repo)`.

- [x] 3. **Frontend In-App Update Interface (`static/app.js`, `static/index.html`, `static/android_bridge.js`)**:
  - Added top gradient notification banner (`#otaUpdateBanner`) with **"Reload to Apply"** and dismiss buttons.
  - Added discrete **"Check for Updates"** button and live build badges in the app footer.
  - Created modal dialog (`#otaModal`) showing installed version, build timestamp, configured GitHub repo (`solotreason/TvC`), manual update trigger, and factory rollback option.
  - Added bridge and fetch routes (`/api/ota/status`, `/api/ota/check`, `/api/ota/reload`, `/api/ota/rollback`).

- [x] 4. **Release Automation Tooling (`run.py` & `version.json`)**:
  - Created `version.json` configuring version, build number, release notes, and GitHub paths for `solotreason/TvC`.
  - Added `python run.py --pack-ota [--ver <version>] [--notes <notes>]`:
    - Automatically updates `version.json` with a sequential build number.
    - Packs `static/` files into `static/ota_bundle.zip`.
    - Syncs web assets to `android/app/src/main/assets/www/`.

- [x] 5. **Testing & Verification**:
  - Verified `python run.py --pack-ota` generates clean zip bundle and JSON.
  - Recompiled standalone APK (`Timecard-Tracker.apk`) with 0 errors.
  - Ran all 19 automated unit tests with 100% pass rate.

## Review Summary
- **No APK Reinstalls Needed**: Pushing an update to `https://github.com/solotreason/TvC` allows all installed Android devices to update HTML, CSS, JavaScript, and scraper logic in the background over-the-air.
- **Fail-Safe Operation**: If an update cannot be reached, the app continues running offline. If an update is ever corrupted, users can tap "Revert to Factory APK Bundle" in Settings to instantly restore the factory APK state.
