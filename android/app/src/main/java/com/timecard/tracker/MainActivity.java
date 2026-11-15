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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final String APP_HOST = "appassets.local";
    private static final String NOTIFICATION_CHANNEL_ID = "daily_timecard_channel";
    private static final String STORE_FILENAME = "timecard_store_v4.json";
    private static final String ARCHIVE_FILENAME = "timecard_archive_v1.json";
    private static final String AUTH_MARKER_FILENAME = "portal_auth_v4.flag";
    private static final String EXTRA_WEEK_END_DATE = "EXTRA_WEEK_END_DATE";
    private static final String EXTRA_SHIFT_DATE = "EXTRA_SHIFT_DATE";
    private static final String PORTAL_CFG_FILENAME = "portal_config.json";
    private static final String DEFAULT_TIMECARD_PATH = "/us/en/tools-and-resources/pay-and-benefits/time-card-viewer.html";
    private static final String DEFAULT_TIMEOFF_PATH = "/us/en/tools-and-resources/pay-and-benefits/time-off-viewer.html";

    // GitHub Over-The-Air (OTA) Live Update Configuration
    private static final String DEFAULT_GITHUB_REPO = "solotreason/TvC";
    private static final String OTA_DIR_NAME = "ota_bundle";
    private static final String OTA_VERSION_FILENAME = "ota_version.json";
    private static final String PREFS_NAME = "tvc_prefs";
    private static final String PREF_KEY_GITHUB_REPO = "github_repo";
    private static final long APP_BASE_BUILD = 2026111409L;
    private static final String APP_BASE_VERSION = "2.4.8";

    private WebView dashboardWebView;
    private WebView scraperWebView;
    private LinearLayout loginOverlayContainer;
    private TextView loginOverlayTitle;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isSyncRunning = false;
    private int requestedMaxWeeks = 0; // 0 = all 8+ weeks, 1 = current week only
    private boolean requestedSyncTimeoff = true;
    private final List<String> pendingWeeksQueue = new ArrayList<>();
    private int totalWeeksToSync = 0;
    private int weeksSyncedCount = 0;
    private volatile boolean isApkDownloading = false;
    private boolean pendingApkInstallAfterPermission = false;
    private String pendingApkUrl = null;

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

    private String getPortalBaseUrl() {
        try {
            File f = new File(getFilesDir(), PORTAL_CFG_FILENAME);
            if (f.exists()) {
                byte[] data = readFileFully(f);
                if (data.length > 0) {
                    JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    String base = obj.optString("base_url", "");
                    if (!base.isEmpty()) return base;
                }
            }
        } catch (Exception ignored) {}
        return new String(
                android.util.Base64.decode("aHR0cHM6Ly93d3cudXBzZXJzLmNvbQ==", android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8
        );
    }

    private String getTimecardPortalUrl() {
        return getPortalBaseUrl() + DEFAULT_TIMECARD_PATH;
    }

    private String getTimeoffPortalUrl() {
        return getPortalBaseUrl() + DEFAULT_TIMEOFF_PATH;
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                if (uri != null && !APP_HOST.equalsIgnoreCase(uri.getHost())) {
                    String urlStr = uri.toString();
                    if (urlStr.toLowerCase().contains(".apk")) {
                        downloadAndInstallApk(urlStr);
                        return true;
                    }
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(browserIntent);
                        return true;
                    } catch (Exception ignored) {}
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
        loginOverlayContainer.setBackgroundColor(Color.WHITE);
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
        closeBtn.setOnClickListener(v -> loginOverlayContainer.setVisibility(View.GONE));
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
        setupWebViewDefaults(scraperWebView);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(scraperWebView, true);

        scraperWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Resource blocking during background sync (aborts images, fonts, and telemetry)
                if (loginOverlayContainer.getVisibility() != View.VISIBLE && request != null && request.getUrl() != null
                        && PortalHelper.isBlockedBackgroundAsset(request.getUrl().toString())) {
                    return PortalHelper.emptyAssetResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
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
            final String jsCall = String.format(
                    "if(window.focusShiftFromNotification){window.focusShiftFromNotification(%s,%s);}",
                    JSONObject.quote(weekEndDate != null ? weekEndDate : ""),
                    JSONObject.quote(shiftDate != null ? shiftDate : "")
            );
            mainHandler.postDelayed(() -> dashboardWebView.evaluateJavascript(jsCall, null), 250);
        }
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

    private void postNativeNotification(String title, String body, String weekEndDate, String shiftDate) {
        PortalHelper.postShiftNotification(this, title, body, weekEndDate, shiftDate);
    }

    private void setupWebViewDefaults(WebView webView) {
        PortalHelper.setupWebViewDefaults(webView);
    }

    private String getActiveGitHubRepo() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(PREF_KEY_GITHUB_REPO, DEFAULT_GITHUB_REPO);
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
            while ((entry = zis.getNextEntry()) != null) {
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

    private boolean downloadBundleWithFailover(String[] urls, File destinationFile, int timeoutMs) {
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
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
                        return true;
                    }
                }
            } catch (Exception ignored) {
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
                String[] candidateVersionUrls = new String[] {
                        "https://raw.githubusercontent.com/" + repo + "/main/version.json?t=" + System.currentTimeMillis(),
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/version.json?t=" + System.currentTimeMillis()
                };

                String jsonStr = fetchJsonWithFailover(candidateVersionUrls, 3500);
                if (jsonStr == null) {
                    if (manual) {
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Could not reach update server", Toast.LENGTH_SHORT).show();
                            dashboardWebView.evaluateJavascript(
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Could not reach update server. Check connection.'});}",
                                    null
                            );
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
                final boolean apkUpdateAvailable = remoteApkBuild > APP_BASE_BUILD;

                JSONObject current = getCurrentOtaInfo();
                long currentBuild = current.optLong("build", APP_BASE_BUILD);

                if (remoteBuild <= currentBuild) {
                    if (apkUpdateAvailable) {
                        current.put("remote_apk_build", remoteApkBuild);
                        current.put("apk_url", apkUrl);
                        saveOtaVersionInfo(current);
                    }
                    mainHandler.post(() -> {
                        if (apkUpdateAvailable) {
                            String apkJs = String.format(
                                    "if(window.TimecardBridge && window.TimecardBridge.onApkUpdateAvailable){window.TimecardBridge.onApkUpdateAvailable({version:%s,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}",
                                    JSONObject.quote(remoteVersion), remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes)
                            );
                            dashboardWebView.evaluateJavascript(apkJs, null);
                        }
                        if (manual) {
                            String toastMsg = apkUpdateAvailable
                                    ? "Native APK update available (v" + remoteVersion + ")"
                                    : "TvC is up to date (v" + remoteVersion + ")";
                            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
                            String js = String.format(
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({up_to_date:%b,apk_update_available:%b,version:%s,build:%d,apk_build:%d,local_apk_build:%d,apk_url:%s});}",
                                    !apkUpdateAvailable, apkUpdateAvailable, JSONObject.quote(remoteVersion), remoteBuild, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl)
                            );
                            dashboardWebView.evaluateJavascript(js, null);
                        }
                    });
                    return;
                }

                if (manual) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Downloading update v" + remoteVersion + "...", Toast.LENGTH_SHORT).show();
                        String js = String.format(
                                "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({downloading:true,version:%s,build:%d});}",
                                JSONObject.quote(remoteVersion), remoteBuild
                        );
                        dashboardWebView.evaluateJavascript(js, null);
                    });
                }

                File zipFile = new File(getFilesDir(), "ota_temp.zip");
                String cacheBustedBundleUrl = bundleUrl + (bundleUrl.contains("?") ? "&t=" : "?t=") + remoteBuild;
                String[] candidateBundleUrls = new String[] {
                        cacheBustedBundleUrl,
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/static/ota_bundle.zip?t=" + remoteBuild
                };

                boolean downloaded = downloadBundleWithFailover(candidateBundleUrls, zipFile, 8000);
                if (!downloaded) {
                    if (manual) {
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Failed downloading update package", Toast.LENGTH_SHORT).show();
                            dashboardWebView.evaluateJavascript(
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Failed downloading update package.'});}",
                                    null
                            );
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
                            Toast.makeText(this, "Invalid update package", Toast.LENGTH_SHORT).show();
                            dashboardWebView.evaluateJavascript(
                                    "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:'Invalid update package format.'});}",
                                    null
                            );
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
                deleteDirRecursively(stagingDir);
                deleteDirRecursively(otaOld);

                JSONObject newMeta = new JSONObject();
                newMeta.put("version", remoteVersion);
                newMeta.put("build", remoteBuild);
                newMeta.put("remote_apk_build", remoteApkBuild);
                newMeta.put("apk_url", apkUrl);
                newMeta.put("release_notes", releaseNotes);
                newMeta.put("github_repo", repo);
                newMeta.put("installed_at", System.currentTimeMillis());
                saveOtaVersionInfo(newMeta);

                mainHandler.post(() -> {
                    if (!isSyncRunning && dashboardWebView != null) {
                        Toast.makeText(this, "Updated to v" + remoteVersion + " (Build " + remoteBuild + ")!", Toast.LENGTH_SHORT).show();
                        dashboardWebView.clearCache(true);
                        dashboardWebView.reload();
                    } else {
                        String js = String.format(
                                "if(window.TimecardBridge && window.TimecardBridge.onOtaUpdateReady){window.TimecardBridge.onOtaUpdateReady({version:%s,build:%d,apk_update_available:%b,apk_build:%d,local_apk_build:%d,apk_url:%s,notes:%s});}" +
                                "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({update_installed:true,apk_update_available:%b,version:%s,build:%d,apk_build:%d,local_apk_build:%d,apk_url:%s});}",
                                JSONObject.quote(remoteVersion), remoteBuild, apkUpdateAvailable, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl), JSONObject.quote(releaseNotes),
                                apkUpdateAvailable, JSONObject.quote(remoteVersion), remoteBuild, remoteApkBuild, APP_BASE_BUILD, JSONObject.quote(apkUrl)
                        );
                        dashboardWebView.evaluateJavascript(js, null);
                        Toast.makeText(this, "Update v" + remoteVersion + " ready! Tap reload to apply.", Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                if (manual) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Update check failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        String js = String.format(
                                "if(window.TimecardBridge && window.TimecardBridge.onOtaCheckResult){window.TimecardBridge.onOtaCheckResult({error:true,message:%s});}",
                                JSONObject.quote("Update check failed: " + (e.getMessage() != null ? e.getMessage() : "Network error"))
                        );
                        dashboardWebView.evaluateJavascript(js, null);
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
            File f = new File(getFilesDir(), AUTH_MARKER_FILENAME);
            if (auth) {
                try (FileOutputStream fos = new FileOutputStream(f, false)) {
                    fos.write("1".getBytes(StandardCharsets.UTF_8));
                }
                TimecardSyncJobService.schedule(this);
            } else {
                if (f.exists()) f.delete();
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
        isSyncRunning = false;
        mainHandler.post(() -> {
            String js = String.format(
                    "if(window.TimecardBridge){window.TimecardBridge.finishSync(%b,%s,%d,%b);}",
                    success, JSONObject.quote(msg), weeksSynced, reqLogin
            );
            dashboardWebView.evaluateJavascript(js, null);

            // Reclaim resources: suspend scraperWebView background execution when idle
            try {
                if (scraperWebView != null && (loginOverlayContainer == null || loginOverlayContainer.getVisibility() != View.VISIBLE)) {
                    scraperWebView.loadUrl("about:blank");
                    scraperWebView.onPause();
                }
            } catch (Exception ignored) {}
        });
    }

    // Monitor scraperWebView until .timecard-wrapper appears
    private void pollForTimecardWrapper(final int attempt) {
        if (!isSyncRunning) return;

        if (attempt > 180) {
            sendFinishToDashboard(false, "Timed out waiting for Time Card Viewer.", weeksSyncedCount, true);
            return;
        }

        String checkJs = "(function(){" +
                "var w = document.querySelector('.timecard-wrapper');" +
                "if (w) {" +
                "  var loader = document.querySelector('#tcvLoader');" +
                "  var loaderVisible = loader && loader.offsetParent !== null && window.getComputedStyle(loader).display !== 'none';" +
                "  var rows = document.querySelectorAll('tr.timecard-card');" +
                "  var opts = document.querySelectorAll('.timecard-date-dropdown select option');" +
                "  if (!loaderVisible && (rows.length > 0 || opts.length > 0)) return 'READY';" +
                "  return 'WAITING';" +
                "}" +
                "var kmsiBtn = document.querySelector('#idSIButton9');" +
                "var isKmsi = document.querySelector('#KmsiDescription') || (document.body && document.body.innerText.toLowerCase().indexOf('stay signed in?') !== -1);" +
                "if (kmsiBtn && isKmsi && kmsiBtn.offsetParent !== null) {" +
                "  kmsiBtn.click(); return 'BYPASSING_KMSI';" +
                "}" +
                "var skipCandidates = Array.from(document.querySelectorAll('a, button, input[type=\"button\"], input[type=\"submit\"]'));" +
                "for (var i = 0; i < skipCandidates.length; i++) {" +
                "  var txt = (skipCandidates[i].innerText || skipCandidates[i].value || '').trim().toLowerCase();" +
                "  var id = (skipCandidates[i].id || '').toLowerCase();" +
                "  if (txt.indexOf('skip for now') !== -1 || txt.indexOf('ask later') !== -1 || txt.indexOf('skip setup') !== -1 || txt.indexOf('no thanks') !== -1 || id === 'btnasklater' || id === 'skiplink' || id.indexOf('asklater') !== -1) {" +
                "    skipCandidates[i].click(); return 'BYPASSING_SECURITY';" +
                "  }" +
                "}" +
                "var href = (window.location.href || '').toLowerCase();" +
                "var body = document.body ? (document.body.innerText || '').toLowerCase() : '';" +
                "var isSecurityScreen = href.indexOf('mysignins.microsoft.com') !== -1 ||" +
                "                       body.indexOf('more information required') !== -1 ||" +
                "                       body.indexOf('keep your account secure') !== -1 ||" +
                "                       body.indexOf('help us protect your account') !== -1 ||" +
                "                       body.indexOf('secure your account') !== -1;" +
                "if (isSecurityScreen) return 'FORCE_REDIRECT_PORTAL';" +
                "var loginSelectors = 'input[name=\"loginfmt\"], #i0116, #i0118, input[name=\"passwd\"], input[type=\"password\"], input[name=\"pf.username\"], input[name=\"pf.pass\"], #idRichContext_DisplaySign, #idTxtBx_SAOTCC_OTC, input[name=\"otc\"]';" +
                "var inputs = document.querySelectorAll(loginSelectors);" +
                "for (var j = 0; j < inputs.length; j++) {" +
                "  var inp = inputs[j];" +
                "  if (inp && inp.offsetParent !== null && window.getComputedStyle(inp).display !== 'none' && window.getComputedStyle(inp).visibility !== 'hidden') {" +
                "    return 'LOGIN';" +
                "  }" +
                "}" +
                "return 'WAITING';" +
                "})();";

        scraperWebView.evaluateJavascript(checkJs, value -> {
            String status = value != null ? value.replace("\"", "") : "WAITING";
            if ("READY".equals(status)) {
                setAuthenticatedFlag(true);
                CookieManager.getInstance().flush();
                loginOverlayContainer.setVisibility(View.GONE);
                sendProgressToDashboard(25, "Processing", "Time Card Viewer loaded. Expanding shift cards...", false);
                mainHandler.postDelayed(this::extractInitialWeekAndQueueDropdown, 700);
            } else if ("FORCE_REDIRECT_PORTAL".equals(status)) {
                scraperWebView.loadUrl(getTimecardPortalUrl());
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1), 1500);
            } else if ("BYPASSING_SECURITY".equals(status) || "BYPASSING_KMSI".equals(status)) {
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1), 700);
            } else if ("LOGIN".equals(status)) {
                if (loginOverlayContainer.getVisibility() == View.VISIBLE) {
                    sendProgressToDashboard(15, "Login Required", "Please sign in and complete 2FA in the window...", true);
                    mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1), 1000);
                } else {
                    // In background sync, never flash the login window over the dashboard.
                    // Allow up to 8 polling attempts (~7s) for silent cookie SSO redirects.
                    if (attempt < 8) {
                        mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1), 800);
                    } else {
                        sendFinishToDashboard(false, "Session authentication required. Tap Log In to connect.", weeksSyncedCount, true);
                    }
                }
            } else {
                mainHandler.postDelayed(() -> pollForTimecardWrapper(attempt + 1), 800);
            }
        });
    }

    // Batch-expand all shift accordions in 50ms and extract initial week + dropdown options
    private void extractInitialWeekAndQueueDropdown() {
        String batchExpandJs = "(function(){" +
                "var lv = document.querySelector('.list-view'); if(lv) lv.click();" +
                "document.querySelectorAll('tr.timecard-card').forEach(function(r){" +
                "  if(!r.classList.contains('activeRow')) r.click();" +
                "});" +
                "return 'EXPANDED';" +
                "})();";

        scraperWebView.evaluateJavascript(batchExpandJs, v -> {
            mainHandler.postDelayed(() -> {
                scraperWebView.evaluateJavascript("document.documentElement.outerHTML", htmlJson -> {
                    String rawHtml = unquoteJsString(htmlJson);
                    String bridgeJs = "window.TimecardBridge.ingestWeekHtml(" + JSONObject.quote(rawHtml) + ", null)";
                    dashboardWebView.evaluateJavascript(bridgeJs, availWeeksJson -> {
                        weeksSyncedCount = 1;
                        pendingWeeksQueue.clear();

                        // If refreshing ONLY the current week (Pull-to-Refresh), skip the 7 finalized weeks
                        if (requestedMaxWeeks == 1) {
                            if (requestedSyncTimeoff) {
                                syncTimeOffBank();
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
                                pendingWeeksQueue.add(arr.getString(i));
                            }
                        } catch (Exception ignored) {}

                        totalWeeksToSync = pendingWeeksQueue.size() + 1;
                        processNextWeekInQueue(1);
                    });
                });
            }, 400);
        });
    }

    private void processNextWeekInQueue(final int currentWeekIndex) {
        if (pendingWeeksQueue.isEmpty()) {
            syncTimeOffBank();
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

        String selectWeekJs = "(function(){" +
                "var sel = document.querySelector('.timecard-date-dropdown select');" +
                "if(!sel) return 'NO_SELECT';" +
                "sel.value = '" + nextWeekDate + "';" +
                "sel.dispatchEvent(new Event('change', { bubbles: true }));" +
                "return 'SELECTED';" +
                "})();";

        scraperWebView.evaluateJavascript(selectWeekJs, val -> {
            mainHandler.postDelayed(() -> {
                String expandJs = "(function(){" +
                        "document.querySelectorAll('tr.timecard-card').forEach(function(r){" +
                        "  if(!r.classList.contains('activeRow')) r.click();" +
                        "});" +
                        "return 'OK';" +
                        "})();";
                scraperWebView.evaluateJavascript(expandJs, v2 -> {
                    mainHandler.postDelayed(() -> {
                        scraperWebView.evaluateJavascript("document.documentElement.outerHTML", htmlJson -> {
                            String rawHtml = unquoteJsString(htmlJson);
                            String bridgeJs = "window.TimecardBridge.ingestWeekHtml("
                                     + JSONObject.quote(rawHtml) + ", "
                                     + JSONObject.quote(nextWeekDate) + ")";
                            dashboardWebView.evaluateJavascript(bridgeJs, r -> {
                                weeksSyncedCount++;
                                processNextWeekInQueue(currentWeekIndex + 1);
                            });
                        });
                    }, 350);
                });
            }, 950);
        });
    }

    private void syncTimeOffBank() {
        sendProgressToDashboard(90, "Time Off Viewer", "Fetching leave balances & Entitlement Bank...", false);
        scraperWebView.loadUrl(getTimeoffPortalUrl());

        mainHandler.postDelayed(new Runnable() {
            int retries = 0;
            @Override
            public void run() {
                scraperWebView.evaluateJavascript(
                        "document.querySelector('.timeoff-wrapper') ? document.documentElement.outerHTML : ''",
                        htmlJson -> {
                            String rawHtml = unquoteJsString(htmlJson);
                            if (rawHtml != null && rawHtml.contains("timeoff-wrapper")) {
                                String bridgeJs = "window.TimecardBridge.ingestTimeoffHtml(" + JSONObject.quote(rawHtml) + ")";
                                dashboardWebView.evaluateJavascript(bridgeJs, r -> {
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
        }, 1200);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                pendingApkInstallAfterPermission = true;
                pendingApkUrl = customApkUrl;
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
        Toast.makeText(this, "Downloading Native APK update...", Toast.LENGTH_SHORT).show();
        if (dashboardWebView != null) {
            dashboardWebView.evaluateJavascript(
                    "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({downloading:true,message:'Downloading Timecard-Tracker.apk from GitHub...'});}",
                    null
            );
        }

        new Thread(() -> {
            try {
                String repo = getActiveGitHubRepo();
                long ts = System.currentTimeMillis();
                String primaryUrl = (customApkUrl != null && !customApkUrl.trim().isEmpty())
                        ? customApkUrl.trim()
                        : ("https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk");
                String primaryCacheBusted = primaryUrl + (primaryUrl.contains("?") ? "&t=" : "?t=") + ts;
                String[] candidateApkUrls = new String[] {
                        primaryCacheBusted,
                        "https://raw.githubusercontent.com/" + repo + "/main/Timecard-Tracker.apk?t=" + ts,
                        "https://cdn.jsdelivr.net/gh/" + repo + "@main/Timecard-Tracker.apk?t=" + ts,
                        "https://github.com/" + repo + "/releases/latest/download/Timecard-Tracker.apk"
                };

                File apkFile = ApkFileProvider.getApkUpdateFile(MainActivity.this);
                boolean ok = downloadBundleWithFailover(candidateApkUrls, apkFile, 12000);
                boolean validApk = false;
                if (ok && apkFile.exists() && apkFile.length() > 50000) {
                    try (FileInputStream fis = new FileInputStream(apkFile)) {
                        int b0 = fis.read();
                        int b1 = fis.read();
                        validApk = (b0 == 'P' && b1 == 'K');
                    } catch (Exception ignored) {}
                }

                if (!validApk) {
                    if (apkFile.exists()) apkFile.delete();
                    isApkDownloading = false;
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "Could not download APK from GitHub", Toast.LENGTH_LONG).show();
                        if (dashboardWebView != null) {
                            dashboardWebView.evaluateJavascript(
                                    "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({error:true,message:'Failed to download APK from GitHub.'});}",
                                    null
                            );
                        }
                    });
                    return;
                }

                isApkDownloading = false;
                mainHandler.post(() -> {
                    if (dashboardWebView != null) {
                        dashboardWebView.evaluateJavascript(
                                "if(window.TimecardBridge && window.TimecardBridge.onApkDownloadStatus){window.TimecardBridge.onApkDownloadStatus({installing:true,message:'Launching Android installer...'});}",
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
    public void onBackPressed() {
        if (loginOverlayContainer != null && loginOverlayContainer.getVisibility() == View.VISIBLE) {
            loginOverlayContainer.setVisibility(View.GONE);
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
                    scraperWebView.clearCache(true);
                    scraperWebView.clearHistory();
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
                // On first login, automatically sync all 8+ weeks as soon as login completes
                isSyncRunning = true;
                requestedMaxWeeks = 0;
                requestedSyncTimeoff = true;
                weeksSyncedCount = 0;
                if (scraperWebView != null) scraperWebView.onResume();
                loginOverlayContainer.setVisibility(View.VISIBLE);
                scraperWebView.loadUrl(getTimecardPortalUrl());
                mainHandler.postDelayed(() -> pollForTimecardWrapper(0), 1000);
            });
        }

        @JavascriptInterface
        public void startBackgroundSyncWithOptions(int maxWeeks, boolean syncTimeoff) {
            mainHandler.post(() -> {
                isSyncRunning = true;
                requestedMaxWeeks = maxWeeks;
                requestedSyncTimeoff = syncTimeoff;
                weeksSyncedCount = 0;
                if (scraperWebView != null) scraperWebView.onResume();
                // Keep scraper sheet strictly hidden during background sync
                loginOverlayContainer.setVisibility(View.GONE);
                String msg = (maxWeeks == 1)
                        ? "Refreshing current pending week in background..."
                        : "Navigating to Time Card Viewer in background...";
                sendProgressToDashboard(15, maxWeeks == 1 ? "Refreshing Current Week" : "Connecting", msg, false);
                scraperWebView.loadUrl("about:blank");
                mainHandler.postDelayed(() -> {
                    scraperWebView.loadUrl(getTimecardPortalUrl());
                    mainHandler.postDelayed(() -> pollForTimecardWrapper(0), 1000);
                }, 150);
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
            if (repo != null && !repo.trim().isEmpty()) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_KEY_GITHUB_REPO, repo.trim())
                        .apply();
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "GitHub repository set to " + repo.trim(), Toast.LENGTH_SHORT).show());
            }
        }
    }
}

