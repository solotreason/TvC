# Lessons & Learned Patterns

### 1. Web Scraper UI Isolation in Hybrid Mobile Apps
- **Issue**: Checking broad URL patterns (e.g. `sso`, `login`) or text content (e.g. `body.indexOf('sign in')`) causes momentary false positives during silent OAuth/SAML token exchanges and intermediate redirects. If bound directly to a visible sheet/container, this causes the webview to briefly flash over the native dashboard before disappearing when `.timecard-wrapper` loads.
- **Rule**:
  1. Never trigger a UI modal or overlay based on intermediate URL parameters or page text. Always verify that an active, visible credential input (`input[type="password"]`, `#i0116`, `#idRichContext_DisplaySign`) is present and rendered (`offsetParent !== null`).
  2. Background sync tasks must NEVER force-display a webview overlay. If authentication is genuinely required, notify the parent application/dashboard cleanly (`requiresLogin: true`) and allow the user to open the login window explicitly.

### 2. Over-The-Air (OTA) Asset Loading & Rollback Safety in Hybrid Apps
- **Pattern**: When dynamically loading web bundles over-the-air in Android `WebViewClient.shouldInterceptRequest`:
  1. Always verify the downloaded bundle in an isolated staging directory (`ota_staging`) with canonical path checks to prevent zip-slip vulnerabilities before atomically swapping it into the active bundle directory.
  2. Always provide a seamless fallback to the APK's read-only bundled assets (`getAssets().open(...)`) if any file is missing, unreadable, or corrupted.
  3. Provide a user-accessible "Revert to Factory" mechanism so corrupted or breaking client-side updates can be rolled back instantly without an APK reinstall.

### 3. Read Endpoint Purity & Disk I/O Bottlenecks
- **Pattern**: Never perform disk writes (`json.dump`) or repetitive N+1 database queries inside read handlers (e.g. `GET /api/weeks`). Keep read paths pure and cached; restrict disk writes to write/sync mutations only.
- **Buffer Safety**: In Java/Android, never assume `InputStream.read(byte[])` reads an entire file in a single invocation, and never convert chunked byte buffers to UTF-8 strings mid-stream (which splits multi-byte code points). Always read completely into a `ByteArrayOutputStream` or use character stream readers.
