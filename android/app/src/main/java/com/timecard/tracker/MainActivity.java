package com.timecard.tracker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.local";
    private static final String STORE_FILENAME = "timecard_store_v4.json";
    private static final String ARCHIVE_FILENAME = "timecard_archive_v1.json";
    private static final String EXTRA_WEEK_END_DATE = "EXTRA_WEEK_END_DATE";
    private static final String EXTRA_SHIFT_DATE = "EXTRA_SHIFT_DATE";

    // GitHub Over-The-Air (OTA) Live Update Configuration
    private static final String DEFAULT_GITHUB_REPO = "solotreason/TvC";
    private static final String OTA_DIR_NAME = "ota_bundle";
    private static final String OTA_VERSION_FILENAME = "ota_version.json";
    private static final String PREFS_NAME = "tvc_prefs";
    private static final String PREF_KEY_GITHUB_REPO = "github_repo";
    private static final long APP_BASE_BUILD = 2026112601L;
    private static final String APP_BASE_VERSION = "2.5.3";

    private static final int MAX_ZIP_ENTRIES = 256;
    private static final long MAX_ZIP_TOTAL_BYTES = 50L * 1024L * 1024L;
    private static final long DUPLICATE_NOTIF_WINDOW_MS = 15_000L;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private WebView dashboardWebView;
    private WebView scraperWebView;
    private LinearLayout loginOverlayContainer;
    private TextView loginOverlayTitle;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isSyncRunning = false;
    private boolean isInteractiveLoginMode = false;
    private int requestedMaxWeeks = 0; // 0 = all 8+ weeks, 1 = current week only
    private boolean requestedSyncTimeoff = true;
    private final List<String> pendingWeeksQueue = new ArrayList<>();
    private int totalWeeksToSync = 0;
    private int weeksSyncedCount = 0;
    private int wrapperLoadedPolls = 0;
    private int syncGeneration = 0;
    private Runnable syncWatchdogRunnable = null;
    private String pendingNotificationWeekEndDate = null;
    private String pendingNotificationShiftDate = null;
    private volatile boolean isApkDownloading = false;
    private boolean pendingApkInstallAfterPermission = false;
    private String pendingApkUrl = null;
    private String lastPostedNotifSignature = null;
    private long lastPostedNotifAtMs = 0L;

    private static byte[] readFileFully(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(file.length(), 65536))) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private String getTimecardPortalUrl() {
        return PortalHelper.getTimecardPortalUrl(this);
    }

    private String getTimeoffPortalUrl() {
        return PortalHelper.getTimeoffPortalUrl(this);
    }

    /**
     * Returns true ONLY when the URL path targets /time-card-viewer or /time-off-viewer (or about:blank).
     * Never matches /welcome.html or external SSO sign-in pages (e.g. microsoftonline.com).
     */
    private boolean isTimecardOrTimeoffViewerUrl(String url) {
        return PortalHelper.isTimecardOrTimeoffViewerUrl(url);
    }

    private boolean isPortalDestinationUrl(String url) {
        return PortalHelper.isPortalDestinationUrl(this, url);
    }

    private void hidePortalOverlayImmediately(WebView view) {
        if (view != null) {
            view.setAlpha(1f);
        }
        if (loginOverlayContainer != null) {
            loginOverlayContainer.setVisibility(View.GONE);
        }
        maskPortalPageVisuals(view);
    }

    private void maskPortalPageVisuals(WebView view) {
        // Safe no-op: loginOverlayContainer (View.GONE) hides scraperWebView 100% natively
        // without mutating DOM visibility/opacity or breaking AEM/jQuery timecard scripts.
    }

    private void updateOverlayVisibilityForUrl(WebView view, String url) {
        if (view != null) {
            view.setAlpha(1f);
        }
        if (loginOverlayContainer == null) return;
        String lower = url != null ? url.trim().toLowerCase() : "";
        if (isTimecardOrTimeoffViewerUrl(lower) || lower.contains("mysignins.microsoft.com")) {
            loginOverlayContainer.setVisibility(View.GONE);
        } else if (isInteractiveLoginMode && (lower.startsWith("http://") || lower.startsWith("https://"))) {
            loginOverlayContainer.setVisibility(View.VISIBLE);
        } else if (!isInteractiveLoginMode) {
            loginOverlayContainer.setVisibility(View.GONE);
        }
    }

    private void armSyncWatchdog(final int generation, long timeoutMs) {
        cancelSyncWatchdog();
        syncWatchdogRunnable = () -> {
            if (isSyncRunning && generation == syncGeneration) {
                sendFinishToDashboard(
                        false,
                        "Sync timed out waiting for portal response. Please tap Sync or Log In to retry.",
                        weeksSyncedCount,
                        !checkCookiesLoggedIn()
                );
            }
        };
        mainHandler.postDelayed(syncWatchdogRunnable, timeoutMs);
    }

    private void cancelSyncWatchdog() {
        if (syncWatchdogRunnable != null) {
            mainHandler.removeCallbacks(syncWatchdogRunnable);
            syncWatchdogRunnable = null;
        }
    }

    private static String quoteForJs(String s) {
        String quoted = JSONObject.quote(s != null ? s : "");
        return quoted.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#1E293B"));

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. Primary Dashboard WebView
        dashboardWebView = new WebView(this);
        setupWebViewDefaults(dashboardWebView);
        dashboardWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dashboardWebView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        dashboardWebView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        dashboardWebView.addJavascriptInterface(new AndroidNativeBridge(), "AndroidNative");
        dashboardWebView.setWebChromeClient(new WebChromeClient());
        dashboardWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    return PortalHelper.loadAppAssetResponse(MainActivity.this, uri.getPath());
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                dispatchPendingNotificationDeepLink();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                if (uri != null && !APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
                    String urlStr = uri.toString();
                    if ("https".equals(scheme) && urlStr.toLowerCase(Locale.ROOT).contains(".apk")) {
                        downloadAndInstallApk(urlStr);
                        return true;
                    }
                    if ("https".equals(scheme) || "http".equals(scheme)) {
                        try {
                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                            startActivity(browserIntent);
                        } catch (Exception ignored) {}
                    }
                    // Never allow dashboardWebView (which holds @JavascriptInterface) to navigate off APP_HOST
                    return true;
                }
                return false;
            }
        });

        rootLayout.addView(dashboardWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 2. Background Scraper + Slide-Up Login Sheet Container
        loginOverlayContainer = new LinearLayout(this);
        loginOverlayContainer.setOrientation(LinearLayout.VERTICAL);
        loginOverlayContainer.setBackgroundColor(Color.parseColor("#1E293B"));
        loginOverlayContainer.setVisibility(View.GONE);

        // Top Bar inside Login Sheet
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#1E293B"));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(14);
        int padV = dpToPx(10);
        topBar.setPadding(padH, padV, padH, padV);

        loginOverlayTitle = new TextView(this);
        loginOverlayTitle.setText("Portal Sign-In (Auto-closes after 2FA)");
        loginOverlayTitle.setTextColor(Color.parseColor("#F59E0B"));
        loginOverlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        loginOverlayTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        topBar.addView(loginOverlayTitle, titleParams);

        Button closeBtn = new Button(this);
        closeBtn.setText("Hide / Run in Background");
        closeBtn.setAllCaps(false);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        closeBtn.setTextColor(Color.parseColor("#1E293B"));
        closeBtn.setBackgroundColor(Color.parseColor("#F59E0B"));
        closeBtn.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        closeBtn.setOnClickListener(v -> {
            isInteractiveLoginMode = false;
            if (scraperWebView != null) scraperWebView.setAlpha(1f);
            loginOverlayContainer.setVisibility(View.GONE);
        });
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        loginOverlayContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Scraper WebView
        scraperWebView = new WebView(this);
        scraperWebView.setAlpha(1f);
        setupWebViewDefaults(scraperWebView);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(scraperWebView, true);

        scraperWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Block heavy images/fonts/trackers when overlay is hidden OR on timecard/timeoff viewer pages
                if (request != null && request.getUrl() != null) {
                    String reqUrl = request.getUrl().toString();
                    boolean onViewerPage = isTimecardOrTimeoffViewerUrl(reqUrl);
                    if ((loginOverlayContainer.getVisibility() != View.VISIBLE || onViewerPage)
                            && PortalHelper.isBlockedBackgroundAsset(reqUrl)) {
                        return PortalHelper.emptyAssetResponse();
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.isForMainFrame()) {
                    String targetUrl = (request != null && request.getUrl() != null) ? request.getUrl().toString() : "";
                    updateOverlayVisibilityForUrl(view, targetUrl);
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                updateOverlayVisibilityForUrl(view, url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                updateOverlayVisibilityForUrl(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                updateOverlayVisibilityForUrl(view, url);
                PortalHelper.injectMicrosoftSecurityBypass(MainActivity.this, view);
            }
        });

        loginOverlayContainer.addView(scraperWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        ));

        rootLayout.addView(loginOverlayContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootLayout);

        // Load main application UI (clean up any older OTA bundle if factory APK was just upgraded)
        cleanupStaleOtaIfFactoryNewer();
        setupNotificationChannel();
        TimecardSyncJobService.schedule(this);
        dashboardWebView.loadUrl("https://" + APP_HOST + "/index.html");
        mainHandler.postDelayed(() -> handleNotificationIntent(getIntent()), 900);
        mainHandler.postDelayed(() -> performOtaCheckAndDownload(false), 3500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || dashboardWebView == null) return;
        final String weekEndDate = intent.getStringExtra(EXTRA_WEEK_END_DATE);
        final String shiftDate = intent.getStringExtra(EXTRA_SHIFT_DATE);
        if ((weekEndDate != null && !weekEndDate.isEmpty()) || (shiftDate != null && !shiftDate.isEmpty())) {
            intent.removeExtra(EXTRA_WEEK_END_DATE);
            intent.removeExtra(EXTRA_SHIFT_DATE);
            pendingNotificationWeekEndDate = weekEndDate != null ? weekEndDate : "";
            pendingNotificationShiftDate = shiftDate != null ? shiftDate : "";
            dispatchPendingNotificationDeepLink();
        }
    }

    private void dispatchPendingNotificationDeepLink() {
        if (dashboardWebView == null) return;
        if ((pendingNotificationWeekEndDate == null || pendingNotificationWeekEndDate.isEmpty())
                && (pendingNotificationShiftDate == null || pendingNotificationShiftDate.isEmpty())) {
            return;
        }
        final String weekArg = JSONObject.quote(pendingNotificationWeekEndDate != null ? pendingNotificationWeekEndDate : "");
        final String shiftArg = JSONObject.quote(pendingNotificationShiftDate != null ? pendingNotificationShiftDate : "");
        final String jsCall = String.format(
                "(function(){if(window.focusShiftFromNotification){window.focusShiftFromNotification(%s,%s);return 'DISPATCHED';}return 'PENDING';})();",
                weekArg,
                shiftArg
        );
        mainHandler.postDelayed(() -> {
            if (dashboardWebView == null) return;
            dashboardWebView.evaluateJavascript(jsCall, result -> {
                if (result != null && result.contains("DISPATCHED")) {
                    pendingNotificationWeekEndDate = null;
                    pendingNotificationShiftDate = null;
                }
            });
        }, 250);
    }

    private void setupNotificationChannel() {
        PortalHelper.setupNotificationChannel(this);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void postNativeNotification(String title, String body, String weekEndDate, String shiftDate) {
        String sig = (title != null ? title.trim() : "") + "|"
                + (body != null ? body.trim() : "") + "|"
                + (weekEndDate != null ? weekEndDate.trim() : "") + "|"
                + (shiftDate != null ? shiftDate.trim() : "");
        long now = System.currentTimeMillis();
        if (sig.equals(lastPostedNotifSignature) && (now - lastPostedNotifAtMs) < DUPLICATE_NOTIF_WINDOW_MS) {
            return;
        }
        lastPostedNotifSignature = sig;
        lastPostedNotifAtMs = now;
        PortalHelper.postShiftNotification(this, title, body, weekEndDate, shiftDate);
    }

    @SuppressWarnings("deprecation")
    private void setupWebViewDefaults(WebView webView) {
        PortalHelper.setupWebViewDefaults(webView);
        WebSettings settings = webView.getSettings();
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
    }

    private static boolean isValidGitHubRepoSlug(String repo) {
        if (repo == null) return false;
        String trimmed = repo.trim();
        return !trimmed.isEmpty()
                && !trimmed.contains("..")
                && trimmed.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    }

    private String getActiveGitHubRepo() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String stored = prefs.getString(PREF_KEY_GITHUB_REPO, DEFAULT_GITHUB_REPO);
        return isValidGitHubRepoSlug(stored) ? stored.trim() : DEFAULT_GITHUB_REPO;
    }

    private static String computeFileSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hashBytes = digest.digest();
        char[] hex = new char[hashBytes.length * 2];
        for (int i = 0; i < hashBytes.length; i++) {
            int v = hashBytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    private static boolean isValidSha256Hex(String sha256) {
        if (sha256 == null || sha256.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = sha256.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean verifyFileSha256(File file, String expectedSha256) {
        if (file == null || !file.exists() || !isValidSha256Hex(expectedSha256)) {
            return false;
        }
        try {
            String actual = computeFileSha256(file);
            String expectedLower = expectedSha256.trim().toLowerCase(Locale.ROOT);
            return MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    expectedLower.getBytes(StandardCharsets.US_ASCII)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private static String[] uniqueNonEmptyUrls(String... urls) {
        Set<String> set = new LinkedHashSet<>();
        if (urls != null) {
            for (String u : urls) {
                if (u != null && !u.trim().isEmpty()) {
                    set.add(u.trim());
                }
            }
        }
        return set.toArray(new String[0]);
    }

    private void cleanupStaleOtaIfFactoryNewer() {
        try {
            File f = new File(getFilesDir(), OTA_VERSION_FILENAME);
            if (f.exists()) {
                byte[] b = readFileFully(f);
                if (b.length > 0) {
                    JSONObject otaMeta = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    long otaBuild = otaMeta.optLong("build", 0L);
                    if (APP_BASE_BUILD >= otaBuild) {
                        deleteDirRecursively(new File(getFilesDir(), OTA_DIR_NAME));
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONObject getCurrentOtaInfo() {
        String repo = getActiveGitHubRepo();
        String defaultApkUrl = "https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk";
        try {
            File f = new File(getFilesDir(), OTA_VERSION_FILENAME);
            if (f.exists()) {
                byte[] b = readFileFully(f);
                if (b.length > 0) {
                    JSONObject info = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    info.put("apk_build", APP_BASE_BUILD);
                    info.put("apk_version", APP_BASE_VERSION);
                    long remoteApkBuild = info.optLong("remote_apk_build", 0L);
                    info.put("apk_update_available", remoteApkBuild > APP_BASE_BUILD);
                    if (!info.has("apk_url")) {
                        info.put("apk_url", defaultApkUrl);
                    }
                    return info;
                }
            }
        } catch (Exception ignored) {}
        JSONObject def = new JSONObject();
        try {
            def.put("version", APP_BASE_VERSION);
            def.put("build", APP_BASE_BUILD);
            def.put("apk_build", APP_BASE_BUILD);
            def.put("apk_version", APP_BASE_VERSION);
            def.put("apk_update_available", false);
            def.put("apk_url", defaultApkUrl);
            def.put("is_factory", true);
            def.put("github_repo", repo);
        } catch (Exception ignored) {}
        return def;
    }

    private void saveOtaVersionInfo(JSONObject info) {
        try {
            File f = new File(getFilesDir(), OTA_VERSION_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(info.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static void deleteDirRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        dir.delete();
    }

    private static void unzipBundle(File zipFile, File targetDir) throws IOException {
        if (!targetDir.exists()) targetDir.mkdirs();
        String canonicalTarget = targetDir.getCanonicalPath();

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            int entryCount = 0;
            long totalExtractedBytes = 0L;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new SecurityException("Zip entry count limit exceeded");
                }
                String entryName = entry.getName();
                if (entryName.startsWith("__MACOSX/") || entryName.endsWith(".DS_Store")) {
                    zis.closeEntry();
                    continue;
                }
                File dest = new File(targetDir, entryName);
                String canonicalDest = dest.getCanonicalPath();
                if (!canonicalDest.startsWith(canonicalTarget + File.separator) && !canonicalDest.equals(canonicalTarget)) {
                    throw new SecurityException("Zip traversal violation: " + entryName);
                }
                if (entry.isDirectory()) {
                    dest.mkdirs();
                } else {
                    File p = dest.getParentFile();
                    if (p != null && !p.exists()) p.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            totalExtractedBytes += len;
                            if (totalExtractedBytes > MAX_ZIP_TOTAL_BYTES) {
                                throw new SecurityException("Zip uncompressed size limit exceeded");
                            }
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private String fetchJsonWithFailover(String[] urls, int timeoutMs) {
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs + 1000);
                conn.setRequestProperty("User-Agent", "TvC-Android/" + APP_BASE_VERSION);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            baos.write(buf, 0, r);
                        }
                        return baos.toString("UTF-8");
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private boolean downloadBundleWithFailover(String[] urls, File destinationFile, int timeoutMs, String expectedSha256) {
        boolean enforceSha = isValidSha256Hex(expectedSha256);
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setUseCaches(false);
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs + 8000);
                conn.setRequestProperty("User-Agent", "TvC-Android/" + APP_BASE_VERSION);
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (InputStream zin = conn.getInputStream();
                         FileOutputStream zout = new FileOutputStream(destinationFile, false)) {
                        byte[] zbuf = new byte[8192];
                        int zr;
                        while ((zr = zin.read(zbuf)) > 0) {
                            zout.write(zbuf, 0, zr);
                        }
                    }
                    if (destinationFile.length() > 500) {
                        if (enforceSha && !verifyFileSha256(destinationFile, expectedSha256)) {
                            destinationFile.delete();
                            continue;
                        }
                        return true;
                    } else {
                        destinationFile.delete();
                    }
                }
            } catch (Exception ignored) {
                if (destinationFile.exists()) {
                    destinationFile.delete();
                }
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }
        return false;
    }

    private void performOtaCheckAndDownload(final boolean manual) {
        new Thread(() -> {
            try {
                String repo = getActiveGitHubRepo();
                long nowTs = System.currentTimeMillis();
                String[] candidateVersionUrls = uniqueNonEmptyUrls(
                        "https://raw.githubusercontent.com/" + repo + "/main/version.json?t=" + nowTs,
                        "https://github.com/" + repo + "/releases/latest/download/version.json",
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/version.json?t=" + nowTs
                );

                String jsonStr = fetchJsonWithFailover(candidateVersionUrls, 3500);
                if (jsonStr == null) {
                    if (manual) {
                        mainHandler.post(() -> {
                            if (dashboardWebView != null) {
                                dashboardWebView.evaluateJavascript(
                                        "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Could not reach update server. Check connection.'});}",
                                        null
                                );
                            }
                        });
                    }
                    return;
                }

                JSONObject remote = new JSONObject(jsonStr);
                String remoteVersion = remote.optString("version", APP_BASE_VERSION);
                long remoteBuild = remote.optLong("build", 0L);
                long remoteApkBuild = remote.optLong("apk_build", 0L);
                String releaseNotes = remote.optString("release_notes", "Performance improvements.");
                String bundleUrl = remote.optString("ota_bundle_url", "https://raw.githubusercontent.com/" + repo + "/main/static/ota_bundle.zip");
                String apkUrl = remote.optString("apk_url", "https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk");
                String expectedOtaSha256 = remote.optString("ota_sha256", "").trim().toLowerCase(Locale.ROOT);
                String remoteApkSha256 = remote.optString("apk_sha256", "").trim().toLowerCase(Locale.ROOT);
                final boolean apkUpdateAvailable = remoteApkBuild > APP_BASE_BUILD;

                JSONObject current = getCurrentOtaInfo();
                long currentBuild = current.optLong("build", APP_BASE_BUILD);

                if (remoteBuild <= currentBuild) {
                    if (apkUpdateAvailable || !remoteApkSha256.isEmpty()) {
                        if (apkUpdateAvailable) {
                            current.put("remote_apk_build", remoteApkBuild);
                            current.put("apk_url", apkUrl);
                        }
                        if (!remoteApkSha256.isEmpty()) {
                            current.put("apk_sha256", remoteApkSha256);
                        }
                        saveOtaVersionInfo(current);
                    }
                    mainHandler.post(() -> {
                        if (dashboardWebView == null) return;
                        if (manual) {
                            // Single update path inside open OTA modal (showOtaCheckResult also reveals APK banner if needed)
                            String js = String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({up_to_date:%b,apk_update_available:%b,version:%s,build:%d,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    !apkUpdateAvailable, apkUpdateAvailable, JSONObject.quote(remoteVersion), remoteBuild, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                            dashboardWebView.evaluateJavascript(js, null);
                        } else if (apkUpdateAvailable) {
                            String apkJs = String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onApkUpdateAvailable){window.TimecardBridge.onApkUpdateAvailable({version:%s,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    JSONObject.quote(remoteVersion), remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                            dashboardWebView.evaluateJavascript(apkJs, null);
                        }
                    });
                    return;
                }

                if (manual) {
                    mainHandler.post(() -> {
                        if (dashboardWebView != null) {
                            String js = String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({downloading:true,version:%s,build:%d});}",
                                    JSONObject.quote(remoteVersion), remoteBuild
                            );
                            dashboardWebView.evaluateJavascript(js, null);
                        }
                    });
                }

                File zipFile = new File(getFilesDir(), "ota_temp.zip");
                String cacheBustedBundleUrl = bundleUrl + (bundleUrl.contains("?") ? "&t=" : "?t=") + remoteBuild;
                String[] candidateBundleUrls = uniqueNonEmptyUrls(
                        cacheBustedBundleUrl,
                        "https://github.com/" + repo + "/releases/latest/download/ota_bundle.zip",
                        "https://raw.githubusercontent.com/" + repo + "/main/static/ota_bundle.zip?t=" + remoteBuild,
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/static/ota_bundle.zip?t=" + remoteBuild
                );

                boolean downloaded = downloadBundleWithFailover(candidateBundleUrls, zipFile, 8000, expectedOtaSha256);
                if (!downloaded || !zipFile.exists()) {
                    if (zipFile.exists()) zipFile.delete();
                    if (manual) {
                        mainHandler.post(() -> {
                            if (dashboardWebView != null) {
                                dashboardWebView.evaluateJavascript(
                                        "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Failed downloading verified update package.'});}",
                                        null
                                );
                            }
                        });
                    }
                    return;
                }

                // Mandatory SHA-256 cryptographic verification on ota_temp.zip before extraction
                if (!verifyFileSha256(zipFile, expectedOtaSha256)) {
                    zipFile.delete();
                    if (manual) {
                        mainHandler.post(() -> {
                            if (dashboardWebView != null) {
                                dashboardWebView.evaluateJavascript(
                                        "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'OTA SHA-256 verification failed. Tampered or corrupted package deleted.'});}",
                                        null
                                );
                            }
                        });
                    }
                    return;
                }

                File stagingDir = new File(getFilesDir(), "ota_staging");
                deleteDirRecursively(stagingDir);
                try {
                    unzipBundle(zipFile, stagingDir);
                } finally {
                    if (zipFile.exists()) {
                        zipFile.delete();
                    }
                }

                // Validate extracted bundle
                File validRoot = stagingDir;
                if (!new File(validRoot, "index.html").exists() && new File(stagingDir, "static").isDirectory()) {
                    validRoot = new File(stagingDir, "static");
                }

                if (!new File(validRoot, "index.html").exists() && !new File(validRoot, "app.js").exists()) {
                    deleteDirRecursively(stagingDir);
                    if (manual) {
                        mainHandler.post(() -> {
                            if (dashboardWebView != null) {
                                dashboardWebView.evaluateJavascript(
                                        "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Invalid update package format.'});}",
                                        null
                                );
                            }
                        });
                    }
                    return;
                }

                File otaDir = new File(getFilesDir(), OTA_DIR_NAME);
                File otaOld = new File(getFilesDir(), "ota_bundle_old");
                deleteDirRecursively(otaOld);

                if (otaDir.exists()) {
                    otaDir.renameTo(otaOld);
                }

                boolean renamed = validRoot.renameTo(otaDir);
                if (!renamed) {
                    otaDir.mkdirs();
                    File[] list = validRoot.listFiles();
                    if (list != null) {
                        for (File sf : list) {
                            sf.renameTo(new File(otaDir, sf.getName()));
                        }
                    }
                }
                if (!new File(otaDir, "index.html").exists() && otaOld.exists()) {
                    deleteDirRecursively(otaDir);
                    otaOld.renameTo(otaDir);
                }
                deleteDirRecursively(stagingDir);
                deleteDirRecursively(otaOld);

                JSONObject newMeta = new JSONObject();
                newMeta.put("version", remoteVersion);
                newMeta.put("build", remoteBuild);
                newMeta.put("remote_apk_build", remoteApkBuild);
                newMeta.put("apk_url", apkUrl);
                if (!expectedOtaSha256.isEmpty()) {
                    newMeta.put("ota_sha256", expectedOtaSha256);
                }
                if (!remoteApkSha256.isEmpty()) {
                    newMeta.put("apk_sha256", remoteApkSha256);
                }
                newMeta.put("release_notes", releaseNotes);
                newMeta.put("github_repo", repo);
                newMeta.put("installed_at", System.currentTimeMillis());
                saveOtaVersionInfo(newMeta);

                mainHandler.post(() -> {
                    if (!isSyncRunning && dashboardWebView != null) {
                        // Show OTA Toast ONLY when no Native APK update banner will appear after reload
                        if (!apkUpdateAvailable) {
                            Toast.makeText(this, "Updated to v" + remoteVersion + " (Build " + remoteBuild + ")!", Toast.LENGTH_SHORT).show();
                        }
                        dashboardWebView.clearCache(true);
                        dashboardWebView.reload();
                    } else if (dashboardWebView != null) {
                        // Show a single non-overlapping banner (APK banner takes precedence when native APK is also newer)
                        String js;
                        if (apkUpdateAvailable) {
                            js = String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onApkUpdateAvailable){window.TimecardBridge.onApkUpdateAvailable({version:%s,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    JSONObject.quote(remoteVersion), remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                        } else {
                            js = String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaUpdateReady){window.TimecardBridge.onOtaUpdateReady({version:%s,build:%d,apk_update_available:false,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    JSONObject.quote(remoteVersion), remoteBuild, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                        }
                        if (manual) {
                            js += String.format(
                                    Locale.US,
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({update_installed:true,apk_update_available:%b,version:%s,build:%d,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    apkUpdateAvailable, JSONObject.quote(remoteVersion), remoteBuild, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                        }
                        dashboardWebView.evaluateJavascript(js, null);
                    }
                });

            } catch (Exception e) {
                if (manual) {
                    mainHandler.post(() -> {
                        if (dashboardWebView != null) {
                            String js = String.format(
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:%s});}",
                                    JSONObject.quote("Update check failed: " + (e.getMessage() != null ? e.getMessage() : "Network error"))
                            );
                            dashboardWebView.evaluateJavascript(js, null);
                        }
                    });
                }
            }
        }).start();
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()
        ));
    }

    private void setAuthenticatedFlag(boolean auth) {
        try {
            PortalHelper.setAuthenticatedFlag(this, auth);
            if (auth) {
                TimecardSyncJobService.schedule(this);
            } else {
                TimecardSyncJobService.cancel(this);
            }
        } catch (Exception ignored) {}
    }

    private boolean checkCookiesLoggedIn() {
        return PortalHelper.checkCookiesLoggedIn(this);
    }

    private void sendProgressToDashboard(int pct, String stage, String msg, boolean reqLogin) {
        mainHandler.post(() -> {
            String js = String.format(
                    "if(window.TimecardBridge){window.TimecardBridge.updateProgress(%d,%s,%s,%b);}",
                    pct, JSONObject.quote(stage), JSONObject.quote(msg), reqLogin
            );
            dashboardWebView.evaluateJavascript(js, null);
        });
    }

    private void sendFinishToDashboard(boolean success, String msg, int weeksSynced, boolean reqLogin) {
        syncGeneration++;
        isSyncRunning = false;
        isInteractiveLoginMode = false;
        wrapperLoadedPolls = 0;
        cancelSyncWatchdog();
        mainHandler.post(() -> {
            hidePortalOverlayImmediately(scraperWebView);
            String js = String.format(
                    "if(window.TimecardBridge){window.TimecardBridge.finishSync(%b,%s,%d,%b);}",
                    success, quoteForJs(msg), weeksSynced, reqLogin
            );
            dashboardWebView.evaluateJavascript(js, null);

            // Reclaim resources: suspend scraperWebView background execution when idle
            try {
                if (scraperWebView != null && (loginOverlayContainer == null || loginOverlayContainer.getVisibility() != View.VISIBLE)) {
                    scraperWebView.stopLoading();
                    scraperWebView.loadUrl("about:blank");
                    scraperWebView.onPause();
                }
            } catch (Exception ignored) {}
        });
    }

    // Monitor scraperWebView until .timecard-wrapper genuinely loads or login/SSO is detected
    private void pollForTimecardWrapper(final int attempt, final int generation) {
        if (!isSyncRunning || generation != syncGeneration) return;

        if (!isInteractiveLoginMode && attempt > 40) {
            sendFinishToDashboard(
                    false,
                    "Timed out connecting to Time Card Viewer. Please tap Sync to retry or Log In if your session expired.",
                    weeksSyncedCount,
                    true
            );
            return;
        }

        if (attempt > 220) {
            sendFinishToDashboard(false, "Timed out waiting for Time Card Viewer.", weeksSyncedCount, true);
            return;
        }

        String checkJs = "(function(){" +
                "function isDomShown(el) {" +
                "  if (!el) return false;" +
                "  var cur = el;" +
                "  while (cur && cur.nodeType === 1) {" +
                "    var st = window.getComputedStyle(cur);" +
                "    if (!st || st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;" +
                "    cur = cur.parentElement;" +
                "  }" +
                "  return true;" +
                "}" +
                "var href = (window.location.href || '').toLowerCase();" +
                "var pathOnly = href.split('?')[0].split('#')[0];" +
                "var isViewerPath = pathOnly.indexOf('/time-card-viewer') !== -1 || pathOnly.indexOf('/time-off-viewer') !== -1;" +
                "var w = document.querySelector('.timecard-wrapper');" +
                "var hasDropdown = !!document.querySelector('.timecard-date-dropdown');" +
                "var hasRealTimecardDom = !!(w && (hasDropdown || document.querySelector('#tcvLoader') || document.querySelector('.list-view') || document.querySelector('tr.timecard-card')));" +
                "if (hasRealTimecardDom) {" +
                "  var lv = document.querySelector('.list-view');" +
                "  if (lv && !lv.classList.contains('active')) {" +
                "    try { lv.click(); } catch(e) {}" +
                "  }" +
                "  var loader = document.querySelector('#tcvLoader');" +
                "  var loaderVisible = !!(loader && window.getComputedStyle(loader).display !== 'none' && isDomShown(loader));" +
                "  var rows = document.querySelectorAll('tr.timecard-card');" +
                "  var opts = document.querySelectorAll('.timecard-date-dropdown select option');" +
                "  if (!loaderVisible && (rows.length > 0 || opts.length > 0)) return 'READY';" +
                "  if (hasDropdown && " + wrapperLoadedPolls + " >= 6) return 'READY';" +
                "  return 'PORTAL_LOADING';" +
                "}" +
                "var body = document.body ? (document.body.innerText || '').toLowerCase() : '';" +
                "var kmsiBtn = document.querySelector('#idSIButton9');" +
                "var isKmsi = document.querySelector('#KmsiDescription') || document.querySelector('#idBtn_Back') || body.indexOf('stay signed in?') !== -1;" +
                "if (kmsiBtn && isKmsi && isDomShown(kmsiBtn)) {" +
                "  kmsiBtn.click(); return 'BYPASSING_KMSI';" +
                "}" +
                "var skipCandidates = Array.from(document.querySelectorAll('a, button, input[type=\"button\"], input[type=\"submit\"]'));" +
                "for (var i = 0; i < skipCandidates.length; i++) {" +
                "  if (!isDomShown(skipCandidates[i])) continue;" +
                "  var txt = (skipCandidates[i].innerText || skipCandidates[i].value || '').trim().toLowerCase();" +
                "  var id = (skipCandidates[i].id || '').toLowerCase();" +
                "  if (txt.indexOf('skip for now') !== -1 || txt.indexOf('ask later') !== -1 || txt.indexOf('skip setup') !== -1 || txt.indexOf('no thanks') !== -1 || id === 'btnasklater' || id === 'skiplink' || id.indexOf('asklater') !== -1) {" +
                "    skipCandidates[i].click(); return 'BYPASSING_SECURITY';" +
                "  }" +
                "}" +
                "var isSecurityScreen = href.indexOf('mysignins.microsoft.com') !== -1 ||" +
                "                       body.indexOf('more information required') !== -1 ||" +
                "                       body.indexOf('keep your account secure') !== -1 ||" +
                "                       body.indexOf('help us protect your account') !== -1 ||" +
                "                       body.indexOf('secure your account') !== -1;" +
                "if (isSecurityScreen) return 'FORCE_REDIRECT_PORTAL';" +
                "if (pathOnly.indexOf('/welcome.html') !== -1 || pathOnly.indexOf('/welcome') !== -1 || pathOnly.indexOf('/login') !== -1 || pathOnly.indexOf('/signin') !== -1) {" +
                "  return 'LOGIN';" +
                "}" +
                "var loginInputSelectors = 'input[name=\"loginfmt\"], #i0116, #i0118, input[name=\"passwd\"], input[type=\"password\"], input[type=\"email\"], input[name*=\"user\" i], #userID, input[id*=\"user\" i], input[name=\"pf.username\"], input[name=\"pf.pass\"], #idRichContext_DisplaySign, #idTxtBx_SAOTCC_OTC, input[name=\"otc\"], #idDiv_SAOTCAS_Description, #idSignInAnotherWay, .table[role=\"listbox\"]';" +
                "var inputs = document.querySelectorAll(loginInputSelectors);" +
                "for (var j = 0; j < inputs.length; j++) {" +
                "  var inp = inputs[j];" +
                "  if (inp && (inp.tagName !== 'INPUT' || (inp.type || '').toLowerCase() !== 'hidden') && isDomShown(inp)) {" +
                "    return 'LOGIN';" +
                "  }" +
                "}" +
                "if (!isViewerPath || document.readyState === 'complete') {" +
                "  var loginLinks = document.querySelectorAll('a[href*=\"login\" i], button[class*=\"login\" i]');" +
                "  for (var k = 0; k < loginLinks.length; k++) {" +
                "    if (isDomShown(loginLinks[k])) return 'LOGIN';" +
                "  }" +
                "}" +
                "return 'WAITING';" +
                "})();";

        scraperWebView.evaluateJavascript(checkJs, value -> {
            if (!isSyncRunning || generation != syncGeneration) return;
            String status = value != null ? value.replace("\"", "") : "WAITING";
            if ("READY".equals(status)) {
                wrapperLoadedPolls = 0;
                isInteractiveLoginMode = false;
                setAuthenticatedFlag(true);
                CookieManager.getInstance().flush();
                hidePortalOverlayImmediately(scraperWebView);
                sendProgressToDashboard(25, "Processing", "Time Card Viewer loaded. Expanding shift cards...", false);
                mainHandler.postDelayed(() -> {
                    if (isSyncRunning && generation == syncGeneration) {
                        extractInitialWeekAndQueueDropdown(generation);
                    }
                }, 400);
            } else if ("PORTAL_LOADING".equals(status)) {
                // Only reached when .timecard-wrapper genuinely exists in the DOM!
                wrapperLoadedPolls++;
                isInteractiveLoginMode = false;
                setAuthenticatedFlag(true);
                CookieManager.getInstance().flush();
                hidePortalOverlayImmediately(scraperWebView);
                sendProgressToDashboard(20, "Authenticated", "Signed in! Loading your timecards in background...", false);
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 400);
            } else if ("FORCE_REDIRECT_PORTAL".equals(status)) {
                hidePortalOverlayImmediately(scraperWebView);
                scraperWebView.loadUrl(getTimecardPortalUrl());
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 1000);
            } else if ("BYPASSING_SECURITY".equals(status) || "BYPASSING_KMSI".equals(status)) {
                hidePortalOverlayImmediately(scraperWebView);
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 500);
            } else if ("LOGIN".equals(status)) {
                wrapperLoadedPolls = 0;
                if (isInteractiveLoginMode) {
                    if (scraperWebView != null) scraperWebView.setAlpha(1f);
                    loginOverlayContainer.setVisibility(View.VISIBLE);
                    sendProgressToDashboard(15, "Login Required", "Please sign in and complete 2FA in the window...", false);
                    mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 800);
                } else {
                    // Non-interactive background/Sync button: allow ~4s (6 polls) for silent cookie SSO redirects, then fail fast
                    hidePortalOverlayImmediately(scraperWebView);
                    if (attempt < 6) {
                        mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 650);
                    } else {
                        setAuthenticatedFlag(false);
                        sendFinishToDashboard(false, "Session expired. Please tap Log In to sign in.", weeksSyncedCount, true);
                    }
                }
            } else {
                String currentUrl = scraperWebView != null ? scraperWebView.getUrl() : "";
                updateOverlayVisibilityForUrl(scraperWebView, currentUrl);
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1, generation), 500);
            }
        });
    }

    // Batch-expand all shift accordions and extract initial week + dropdown options
    private void extractInitialWeekAndQueueDropdown(final int generation) {
        if (!isSyncRunning || generation != syncGeneration) return;
        String batchExpandJs = "(function(){" +
                "try {" +
                "  var lv = document.querySelector('.list-view');" +
                "  if (lv && !lv.classList.contains('active')) lv.click();" +
                "  document.querySelectorAll('tr.timecard-card').forEach(function(r){" +
                "    if(!r.classList.contains('activeRow')) r.click();" +
                "  });" +
                "} catch(e) {}" +
                "return 'EXPANDED';" +
                "})();";

        scraperWebView.evaluateJavascript(batchExpandJs, v -> {
            mainHandler.postDelayed(() -> {
                if (!isSyncRunning || generation != syncGeneration) return;
                scraperWebView.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''", htmlJson -> {
                    if (!isSyncRunning || generation != syncGeneration) return;
                    String rawHtml = unquoteJsString(htmlJson);
                    if (rawHtml == null || rawHtml.trim().isEmpty() || !rawHtml.contains("timecard-wrapper")) {
                        sendFinishToDashboard(false, "Could not read Time Card Viewer page. Please tap Sync to retry.", weeksSyncedCount, false);
                        return;
                    }
                    String bridgeJs = "(function(){try{return (window.TimecardBridge && window.TimecardBridge.ingestWeekHtml) ? window.TimecardBridge.ingestWeekHtml(" + quoteForJs(rawHtml) + ", null) : '[]';}catch(e){return '[]';}})()";
                    dashboardWebView.evaluateJavascript(bridgeJs, availWeeksJson -> {
                        if (!isSyncRunning || generation != syncGeneration) return;
                        weeksSyncedCount = 1;
                        pendingWeeksQueue.clear();

                        // If refreshing ONLY the current week, skip finalized weeks
                        if (requestedMaxWeeks == 1) {
                            if (requestedSyncTimeoff) {
                                syncTimeOffBank(generation);
                            } else {
                                CookieManager.getInstance().flush();
                                sendFinishToDashboard(
                                        true,
                                        "Current week refreshed!",
                                        1,
                                        false
                                );
                            }
                            return;
                        }

                        try {
                            String cleanJson = unquoteJsString(availWeeksJson);
                            JSONArray arr = new JSONArray(cleanJson);
                            int limit = requestedMaxWeeks > 0 ? Math.min(arr.length(), requestedMaxWeeks) : arr.length();
                            for (int i = 1; i < limit; i++) {
                                String wk = arr.optString(i, "").trim();
                                if (!wk.isEmpty()) {
                                    pendingWeeksQueue.add(wk);
                                }
                            }
                        } catch (Exception ignored) {}

                        totalWeeksToSync = pendingWeeksQueue.size() + 1;
                        processNextWeekInQueue(1, generation);
                    });
                });
            }, 400);
        });
    }

    private void processNextWeekInQueue(final int currentWeekIndex, final int generation) {
        if (!isSyncRunning || generation != syncGeneration) return;
        if (pendingWeeksQueue.isEmpty()) {
            if (requestedSyncTimeoff) {
                syncTimeOffBank(generation);
            } else {
                CookieManager.getInstance().flush();
                sendFinishToDashboard(
                        true,
                        "Successfully synced " + weeksSyncedCount + " week(s)!",
                        weeksSyncedCount,
                        false
                );
            }
            return;
        }

        final String nextWeekDate = pendingWeeksQueue.remove(0);
        int pct = Math.min(85, 30 + (int) (((float) currentWeekIndex / Math.max(1, totalWeeksToSync)) * 55));
        sendProgressToDashboard(
                pct,
                "Syncing Week " + (currentWeekIndex + 1) + " of " + totalWeeksToSync,
                "Loading punches for week ending " + nextWeekDate + "...",
                false
        );

        String selectWeekJs = "(function(target){" +
                "var sel = document.querySelector('.timecard-date-dropdown select');" +
                "if(!sel) return 'NO_SELECT';" +
                "sel.value = target;" +
                "sel.dispatchEvent(new Event('change', { bubbles: true }));" +
                "return 'SELECTED';" +
                "})(" + quoteForJs(nextWeekDate) + ");";

        scraperWebView.evaluateJavascript(selectWeekJs, val -> {
            if (!isSyncRunning || generation != syncGeneration) return;
            String selRes = val != null ? val.replace("\"", "") : "";
            if ("NO_SELECT".equals(selRes)) {
                pendingWeeksQueue.clear();
                processNextWeekInQueue(currentWeekIndex + 1, generation);
                return;
            }
            waitForSelectedWeekAndIngest(currentWeekIndex, nextWeekDate, 0, generation);
        });
    }

    private void waitForSelectedWeekAndIngest(
            final int currentWeekIndex,
            final String nextWeekDate,
            final int waitAttempt,
            final int generation
    ) {
        mainHandler.postDelayed(() -> {
            if (!isSyncRunning || generation != syncGeneration) return;
            String readyCheckJs = "(function(){" +
                    "var loader = document.querySelector('#tcvLoader');" +
                    "var loaderVisible = !!(loader && window.getComputedStyle(loader).display !== 'none');" +
                    "var rows = document.querySelectorAll('tr.timecard-card');" +
                    "return (!loaderVisible && rows.length > 0) ? 'READY' : 'LOADING';" +
                    "})();";
            scraperWebView.evaluateJavascript(readyCheckJs, readyVal -> {
                if (!isSyncRunning || generation != syncGeneration) return;
                String state = readyVal != null ? readyVal.replace("\"", "") : "LOADING";
                if (!"READY".equals(state) && waitAttempt < 8) {
                    waitForSelectedWeekAndIngest(currentWeekIndex, nextWeekDate, waitAttempt + 1, generation);
                    return;
                }
                String expandJs = "(function(){" +
                        "try {" +
                        "  var lv = document.querySelector('.list-view');" +
                        "  if (lv && !lv.classList.contains('active')) lv.click();" +
                        "  document.querySelectorAll('tr.timecard-card').forEach(function(r){" +
                        "    if(!r.classList.contains('activeRow')) r.click();" +
                        "  });" +
                        "} catch(e) {}" +
                        "return 'OK';" +
                        "})();";
                scraperWebView.evaluateJavascript(expandJs, v2 -> {
                    mainHandler.postDelayed(() -> {
                        if (!isSyncRunning || generation != syncGeneration) return;
                        scraperWebView.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''", htmlJson -> {
                            if (!isSyncRunning || generation != syncGeneration) return;
                            String rawHtml = unquoteJsString(htmlJson);
                            if (rawHtml != null && rawHtml.contains("timecard-wrapper")) {
                                String bridgeJs = "(function(){try{return (window.TimecardBridge && window.TimecardBridge.ingestWeekHtml) ? window.TimecardBridge.ingestWeekHtml("
                                        + quoteForJs(rawHtml) + ", "
                                        + quoteForJs(nextWeekDate) + ") : '[]';}catch(e){return '[]';}})()";
                                dashboardWebView.evaluateJavascript(bridgeJs, r -> {
                                    if (!isSyncRunning || generation != syncGeneration) return;
                                    weeksSyncedCount++;
                                    processNextWeekInQueue(currentWeekIndex + 1, generation);
                                });
                            } else {
                                processNextWeekInQueue(currentWeekIndex + 1, generation);
                            }
                        });
                    }, 300);
                });
            });
        }, waitAttempt == 0 ? 450 : 300);
    }

    private void syncTimeOffBank(final int generation) {
        if (!isSyncRunning || generation != syncGeneration) return;
        sendProgressToDashboard(90, "Time Off Viewer", "Fetching leave balances & Entitlement Bank...", false);
        hidePortalOverlayImmediately(scraperWebView);
        scraperWebView.loadUrl(getTimeoffPortalUrl());

        mainHandler.postDelayed(new Runnable() {
            int retries = 0;
            @Override
            public void run() {
                if (!isSyncRunning || generation != syncGeneration) return;
                scraperWebView.evaluateJavascript(
                        "(function(){var w = document.querySelector('.timeoff-wrapper'); var l = document.querySelector('#tovLoader, #toLoader'); var lv = !!(l && window.getComputedStyle(l).display !== 'none'); return (w && !lv) ? document.documentElement.outerHTML : '';})()",
                        htmlJson -> {
                            if (!isSyncRunning || generation != syncGeneration) return;
                            String rawHtml = unquoteJsString(htmlJson);
                            if (rawHtml != null && rawHtml.contains("timeoff-wrapper")) {
                                String bridgeJs = "(function(){try{return (window.TimecardBridge && window.TimecardBridge.ingestTimeoffHtml) ? window.TimecardBridge.ingestTimeoffHtml(" + quoteForJs(rawHtml) + ") : 'ERR';}catch(e){return 'ERR';}})()";
                                dashboardWebView.evaluateJavascript(bridgeJs, r -> {
                                    if (!isSyncRunning || generation != syncGeneration) return;
                                    CookieManager.getInstance().flush();
                                    sendFinishToDashboard(
                                            true,
                                            "Successfully synced " + weeksSyncedCount + " week(s) & Time Off bank!",
                                            weeksSyncedCount,
                                            false
                                    );
                                });
                            } else if (retries < 15) {
                                retries++;
                                mainHandler.postDelayed(this, 600);
                            } else {
                                CookieManager.getInstance().flush();
                                sendFinishToDashboard(
                                        true,
                                        "Synced " + weeksSyncedCount + " week(s).",
                                        weeksSyncedCount,
                                        false
                                );
                            }
                        }
                );
            }
        }, 1000);
    }

    private String unquoteJsString(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        try {
            return (String) new org.json.JSONTokener(raw).nextValue();
        } catch (Exception e) {
            return raw;
        }
    }

    private void downloadAndInstallApk(final String customApkUrl) {
        if (isApkDownloading) {
            Toast.makeText(this, "APK download already in progress...", Toast.LENGTH_SHORT).show();
            return;
        }
        String sanitizedCustomUrl = (customApkUrl != null && customApkUrl.trim().toLowerCase(Locale.ROOT).startsWith("https://"))
                ? customApkUrl.trim()
                : null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                pendingApkInstallAfterPermission = true;
                pendingApkUrl = sanitizedCustomUrl;
                Toast.makeText(this, "Enable 'Allow from this source' and press Back to install", Toast.LENGTH_LONG).show();
                try {
                    Intent permIntent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(permIntent);
                    return;
                } catch (Exception ignored) {}
            }
        }

        isApkDownloading = true;
        if (dashboardWebView != null) {
            dashboardWebView.evaluateJavascript(
                    "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({downloading:true,message:'Downloading & verifying Timecard-Tracker.apk...'});}",
                    null
            );
        }

        new Thread(() -> {
            File apkFile = ApkFileProvider.getApkUpdateFile(MainActivity.this);
            try {
                String repo = getActiveGitHubRepo();
                long ts = System.currentTimeMillis();

                // Resolve expected apk_sha256 from live version.json first, falling back to stored ota_version.json
                String expectedApkSha256 = "";
                String remoteApkUrl = "";
                String[] versionUrls = uniqueNonEmptyUrls(
                        "https://raw.githubusercontent.com/" + repo + "/main/version.json?t=" + ts,
                        "https://github.com/" + repo + "/releases/latest/download/version.json",
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/version.json?t=" + ts
                );
                String liveVersionJson = fetchJsonWithFailover(versionUrls, 3500);
                if (liveVersionJson != null) {
                    try {
                        JSONObject remoteMeta = new JSONObject(liveVersionJson);
                        expectedApkSha256 = remoteMeta.optString("apk_sha256", "").trim().toLowerCase(Locale.ROOT);
                        remoteApkUrl = remoteMeta.optString("apk_url", "").trim();
                    } catch (Exception ignored) {}
                }
                if (expectedApkSha256.isEmpty()) {
                    expectedApkSha256 = getCurrentOtaInfo().optString("apk_sha256", "").trim().toLowerCase(Locale.ROOT);
                }

                String primaryUrl = sanitizedCustomUrl != null
                        ? sanitizedCustomUrl
                        : (!remoteApkUrl.isEmpty() && remoteApkUrl.toLowerCase(Locale.ROOT).startsWith("https://")
                            ? remoteApkUrl
                            : ("https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk"));
                String primaryCacheBusted = primaryUrl + (primaryUrl.contains("?") ? "&t=" : "?t=") + ts;
                String[] candidateApkUrls = uniqueNonEmptyUrls(
                        primaryCacheBusted,
                        "https://github.com/" + repo + "/releases/latest/download/Timecard-Tracker.apk",
                        "https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk?t=" + ts,
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/Timecard-Tracker.apk?t=" + ts
                );

                boolean ok = downloadBundleWithFailover(candidateApkUrls, apkFile, 12000, expectedApkSha256);
                boolean validApk = false;
                if (ok && apkFile.exists() && apkFile.length() > 50000) {
                    try (FileInputStream fis = new FileInputStream(apkFile)) {
                        int b0 = fis.read();
                        int b1 = fis.read();
                        validApk = (b0 == 'P' && b1 == 'K');
                    } catch (Exception ignored) {}
                }

                // Mandatory SHA-256 verification on Timecard-Tracker-update.apk before launching PackageInstaller
                boolean shaVerified = validApk && verifyFileSha256(apkFile, expectedApkSha256);
                if (!validApk || !shaVerified) {
                    if (apkFile.exists()) apkFile.delete();
                    isApkDownloading = false;
                    final String errMsg = (validApk && !shaVerified)
                            ? "APK SHA-256 verification failed. Deleted tampered/corrupted file."
                            : "Could not download verified APK from GitHub.";
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, errMsg, Toast.LENGTH_LONG).show();
                        if (dashboardWebView != null) {
                            String js = String.format(
                                    "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({error:true,message:%s});}",
                                    JSONObject.quote(errMsg)
                            );
                            dashboardWebView.evaluateJavascript(js, null);
                        }
                    });
                    return;
                }

                isApkDownloading = false;
                mainHandler.post(() -> {
                    if (dashboardWebView != null) {
                        dashboardWebView.evaluateJavascript(
                                "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({installing:true,message:'SHA-256 verified! Launching Android installer...'});}",
                                null
                        );
                    }
                    try {
                        Uri apkUri = ApkFileProvider.getApkContentUri();
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(installIntent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Installer error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                if (apkFile.exists()) apkFile.delete();
                isApkDownloading = false;
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "APK update failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimecardSyncJobService.schedule(this);
        if (pendingApkInstallAfterPermission) {
            pendingApkInstallAfterPermission = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                final String urlToInstall = pendingApkUrl;
                pendingApkUrl = null;
                mainHandler.postDelayed(() -> downloadAndInstallApk(urlToInstall), 350);
            }
        }
        if (dashboardWebView != null) {
            handleNotificationIntent(getIntent());
            dashboardWebView.evaluateJavascript(
                    "if(window.onAppResumedSmartCheck){window.onAppResumedSmartCheck();}",
                    null
            );
        }
    }

    @Override
    protected void onDestroy() {
        cancelSyncWatchdog();
        mainHandler.removeCallbacksAndMessages(null);
        try {
            if (scraperWebView != null) {
                scraperWebView.stopLoading();
                scraperWebView.destroy();
                scraperWebView = null;
            }
            if (dashboardWebView != null) {
                dashboardWebView.stopLoading();
                dashboardWebView.destroy();
                dashboardWebView = null;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (loginOverlayContainer != null && loginOverlayContainer.getVisibility() == View.VISIBLE) {
            isInteractiveLoginMode = false;
            if (scraperWebView != null) scraperWebView.setAlpha(1f);
            loginOverlayContainer.setVisibility(View.GONE);
            if (!checkCookiesLoggedIn()) {
                sendFinishToDashboard(false, "Sign-in cancelled. Tap Log In when ready.", weeksSyncedCount, true);
            }
            return;
        }
        super.onBackPressed();
    }

    public class AndroidNativeBridge {

        @JavascriptInterface
        public String loadStateJson() {
            return PortalHelper.readFileString(MainActivity.this, STORE_FILENAME);
        }

        @JavascriptInterface
        public void saveStateJson(String json) {
            PortalHelper.writeTextFileAtomic(MainActivity.this, STORE_FILENAME, json);
        }

        @JavascriptInterface
        public long getStoreLastModified() {
            return PortalHelper.getStoreLastModified(MainActivity.this);
        }

        @JavascriptInterface
        public boolean isIgnoringBatteryOptimizations() {
            return PortalHelper.isIgnoringBatteryOptimizations(MainActivity.this);
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            mainHandler.post(() -> PortalHelper.requestIgnoreBatteryOptimizations(MainActivity.this));
        }

        @JavascriptInterface
        public void saveArchiveJson(final String jsonInput) {
            if (jsonInput == null || jsonInput.trim().isEmpty()) return;
            new Thread(() -> PortalHelper.mergeAndSaveArchiveJsonSync(MainActivity.this, jsonInput)).start();
        }

        @JavascriptInterface
        public String loadArchiveJson() {
            return PortalHelper.readFileString(MainActivity.this, ARCHIVE_FILENAME);
        }

        @JavascriptInterface
        public void startBackgroundSync() {
            startBackgroundSyncWithOptions(0, true);
        }

        @JavascriptInterface
        public boolean isLoggedIn() {
            return checkCookiesLoggedIn();
        }

        @JavascriptInterface
        public void showTimecardNotificationForShift(String title, String message, String weekEndDate, String shiftDate) {
            mainHandler.post(() -> postNativeNotification(title, message, weekEndDate, shiftDate));
        }

        @JavascriptInterface
        public void logoutSession() {
            syncGeneration++;
            isSyncRunning = false;
            isInteractiveLoginMode = false;
            wrapperLoadedPolls = 0;
            cancelSyncWatchdog();
            setAuthenticatedFlag(false);
            PortalHelper.clearBackgroundSyncPrefs(MainActivity.this);
            try {
                File f = new File(getFilesDir(), STORE_FILENAME);
                if (f.exists()) {
                    f.delete();
                }
                // NOTE: ARCHIVE_FILENAME is a permanent vault and is NEVER deleted on logout!
            } catch (Exception ignored) {}
            mainHandler.post(() -> {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.flush();
                if (scraperWebView != null) {
                    scraperWebView.stopLoading();
                    scraperWebView.clearCache(true);
                    scraperWebView.clearHistory();
                    scraperWebView.setAlpha(1f);
                    scraperWebView.loadUrl("about:blank");
                }
                if (loginOverlayContainer != null) {
                    loginOverlayContainer.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface
        public void openLoginSheet() {
            mainHandler.post(() -> {
                TimecardSyncJobService.schedule(MainActivity.this);
                // On first login, automatically sync all 8+ weeks as soon as login completes.
                // Keep loginOverlayContainer hidden until scraperWebView lands on a sign-in/SSO page
                // so the portal's own timecard background never flashes.
                final int gen = ++syncGeneration;
                isSyncRunning = true;
                isInteractiveLoginMode = true;
                requestedMaxWeeks = 0;
                requestedSyncTimeoff = true;
                weeksSyncedCount = 0;
                wrapperLoadedPolls = 0;
                armSyncWatchdog(gen, 210_000L);
                if (scraperWebView != null) {
                    scraperWebView.onResume();
                    scraperWebView.stopLoading();
                    scraperWebView.setAlpha(1f);
                }
                hidePortalOverlayImmediately(scraperWebView);
                sendProgressToDashboard(12, "Connecting to Sign-In", "Opening secure SSO sign-in...", false);
                scraperWebView.loadUrl(getTimecardPortalUrl());
                mainHandler.postDelayed(() -> pollForTimecardWrapper(0, gen), 400);
            });
        }

        @JavascriptInterface
        public void startBackgroundSyncWithOptions(int maxWeeks, boolean syncTimeoff) {
            mainHandler.post(() -> {
                final int gen = ++syncGeneration;
                isSyncRunning = true;
                isInteractiveLoginMode = false;
                requestedMaxWeeks = maxWeeks;
                requestedSyncTimeoff = syncTimeoff;
                weeksSyncedCount = 0;
                wrapperLoadedPolls = 0;
                armSyncWatchdog(gen, maxWeeks == 1 ? 45_000L : 90_000L);
                if (scraperWebView != null) {
                    scraperWebView.onResume();
                    scraperWebView.stopLoading();
                    scraperWebView.setAlpha(1f);
                }
                // Keep scraper sheet strictly hidden during background sync
                hidePortalOverlayImmediately(scraperWebView);
                String msg = (maxWeeks == 1)
                        ? "Refreshing current pending week in background..."
                        : "Navigating to Time Card Viewer in background...";
                sendProgressToDashboard(15, maxWeeks == 1 ? "Refreshing Current Week" : "Connecting", msg, false);
                scraperWebView.loadUrl("about:blank");
                mainHandler.postDelayed(() -> {
                    if (!isSyncRunning || gen != syncGeneration) return;
                    hidePortalOverlayImmediately(scraperWebView);
                    scraperWebView.loadUrl(getTimecardPortalUrl());
                    mainHandler.postDelayed(() -> pollForTimecardWrapper(0, gen), 900);
                }, 120);
            });
        }

        @JavascriptInterface
        public void checkForUpdates(boolean manual) {
            mainHandler.post(() -> performOtaCheckAndDownload(manual));
        }

        @JavascriptInterface
        public void reloadApp() {
            mainHandler.post(() -> {
                if (dashboardWebView != null) {
                    dashboardWebView.clearCache(true);
                    dashboardWebView.reload();
                }
            });
        }

        @JavascriptInterface
        public String getOtaStatusJson() {
            return getCurrentOtaInfo().toString();
        }

        @JavascriptInterface
        public long getApkBuild() {
            return APP_BASE_BUILD;
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String customUrl) {
            mainHandler.post(() -> MainActivity.this.downloadAndInstallApk(customUrl));
        }

        @JavascriptInterface
        public void resetOtaToFactory() {
            mainHandler.post(() -> {
                File otaDir = new File(getFilesDir(), OTA_DIR_NAME);
                deleteDirRecursively(otaDir);
                new File(getFilesDir(), OTA_VERSION_FILENAME).delete();
                Toast.makeText(MainActivity.this, "Reverted to factory APK assets", Toast.LENGTH_SHORT).show();
                if (dashboardWebView != null) {
                    dashboardWebView.clearCache(true);
                    dashboardWebView.reload();
                }
            });
        }

        @JavascriptInterface
        public void setGitHubRepo(String repo) {
            if (isValidGitHubRepoSlug(repo)) {
                final String cleanRepo = repo.trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_KEY_GITHUB_REPO, cleanRepo)
                        .apply();
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "GitHub repository set to " + cleanRepo, Toast.LENGTH_SHORT).show());
            }
        }
    }
}

