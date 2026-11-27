package com.timecard.tracker;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared portal URL resolution, synchronized atomic disk storage, WebView asset/auth helpers,
 * and native shift notifications shared cleanly across MainActivity and TimecardSyncJobService.
 */
final class PortalHelper {

    static final String APP_HOST = "appassets.local";
    static final String NOTIFICATION_CHANNEL_ID = "daily_timecard_channel";
    static final String STORE_FILENAME = "timecard_store_v4.json";
    static final String ARCHIVE_FILENAME = "timecard_archive_v1.json";
    static final String AUTH_MARKER_FILENAME = "portal_auth_v4.flag";
    static final String PORTAL_CFG_FILENAME = "portal_config.json";
    static final String OTA_DIR_NAME = "ota_bundle";
    static final String PREFS_NAME = "tvc_prefs";
    static final String PREF_LAST_BG_SYNC_MS = "last_bg_sync_ms";
    static final String PREF_LAST_PAGE_FINGERPRINT = "last_page_fingerprint_v1";
    static final String PREF_LAST_TIMEOFF_SYNC_MS = "last_timeoff_bg_sync_ms";
    static final String PREF_LAST_TIMEOFF_FINGERPRINT = "last_timeoff_fingerprint_v1";
    static final String PREF_LAST_ROLLOVER_SYNC_MS = "last_rollover_bg_sync_ms";
    static final String PREF_AUTH_SESSION_EXPIRED = "auth_session_expired_v1";
    static final String PREF_NOTIFIED_SIGNATURES = "notified_shift_signatures_v2";
    static final String PREF_LAST_NOTIF_KEY = "last_notif_dedup_key_v1";
    static final String PREF_LAST_NOTIF_BODY_HASH = "last_notif_body_hash_v1";
    static final String PREF_LAST_NOTIF_MS = "last_notif_dedup_ms_v1";
    static final int MAX_NOTIFIED_SIGNATURES = 400;
    private static final long CROSS_CALLER_NOTIF_DEDUP_MS = 90_000L;
    private static final long IDENTICAL_PAYLOAD_DEDUP_MS = 10 * 60 * 1000L;

    static final String EXTRA_WEEK_END_DATE = "EXTRA_WEEK_END_DATE";
    static final String EXTRA_SHIFT_DATE = "EXTRA_SHIFT_DATE";

    static final String DEFAULT_TIMECARD_PATH = "/us/en/tools-and-resources/pay-and-benefits/time-card-viewer.html";
    static final String DEFAULT_TIMEOFF_PATH = "/us/en/tools-and-resources/pay-and-benefits/time-off-viewer.html";

    private static final String DEFAULT_PORTAL_BASE_B64 = "aHR0cHM6Ly93d3cudXBzZXJzLmNvbQ==";
    private static final String USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Object FILE_LOCK = new Object();
    private static final Object NOTIF_LOCK = new Object();

    private PortalHelper() {}

    /** Resolves the configured portal base URL from portal_config.json or encoded default. */
    static String getPortalBaseUrl(Context context) {
        try {
            File f = new File(context.getFilesDir(), PORTAL_CFG_FILENAME);
            if (f.exists()) {
                byte[] b = readFileFully(f);
                if (b.length > 0) {
                    JSONObject obj = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    String base = obj.optString("base_url", "").trim();
                    if (!base.isEmpty()) {
                        return stripTrailingSlash(base);
                    }
                }
            }
        } catch (Exception ignored) {}
        String decoded = new String(
                Base64.decode(DEFAULT_PORTAL_BASE_B64, Base64.DEFAULT),
                StandardCharsets.UTF_8
        );
        return stripTrailingSlash(decoded);
    }

    /** Returns the full portal Time Card Viewer URL. */
    static String getTimecardPortalUrl(Context context) {
        return getPortalBaseUrl(context) + DEFAULT_TIMECARD_PATH;
    }

    /** Returns the full portal Time Off Viewer URL. */
    static String getTimeoffPortalUrl(Context context) {
        return getPortalBaseUrl(context) + DEFAULT_TIMEOFF_PATH;
    }

    private static String stripTrailingSlash(String url) {
        String out = url == null ? "" : url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** Reads an entire file stream safely without mid-UTF-8 buffer splits. */
    static byte[] readFileFully(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Reads a UTF-8 file inside app filesDir under the shared file lock, or empty string if absent. */
    static String readFileString(Context context, String filename) {
        synchronized (FILE_LOCK) {
            try {
                File f = new File(context.getFilesDir(), filename);
                if (!f.exists() || f.length() == 0) return "";
                return new String(readFileFully(f), StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            return "";
        }
    }

    /** Atomically writes UTF-8 text to a file in app filesDir to prevent torn JSON reads. */
    static void writeTextFileAtomic(Context context, String filename, String content) {
        if (content == null) return;
        synchronized (FILE_LOCK) {
            File target = new File(context.getFilesDir(), filename);
            File tmp = new File(context.getFilesDir(), filename + ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(bytes);
                fos.flush();
            } catch (Exception ignored) {
                // Fallback direct write if temp write fails
                try (FileOutputStream fos = new FileOutputStream(target, false)) {
                    fos.write(bytes);
                    fos.flush();
                } catch (Exception ignored2) {}
                return;
            }
            if (!tmp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target, false)) {
                    fos.write(bytes);
                    fos.flush();
                } catch (Exception ignored) {}
                tmp.delete();
            }
        }
    }

    /** Returns the store file modification timestamp in epoch millis, or 0 if not created yet. */
    static long getStoreLastModified(Context context) {
        File f = new File(context.getFilesDir(), STORE_FILENAME);
        return f.exists() ? f.lastModified() : 0L;
    }

    /**
     * Returns true ONLY when the URL path itself targets Time Card Viewer or Time Off Viewer (or about:blank).
     * Never matches /welcome.html, /login, federated SSO hosts, or SSO URLs that merely carry viewer paths in query params.
     */
    static boolean isTimecardOrTimeoffViewerUrl(String url) {
        if (url == null || url.trim().isEmpty()) return true;
        String lower = url.trim().toLowerCase(Locale.US);
        if ("about:blank".equals(lower)) return true;

        int qIdx = lower.indexOf('?');
        int hIdx = lower.indexOf('#');
        int cut = lower.length();
        if (qIdx >= 0 && qIdx < cut) cut = qIdx;
        if (hIdx >= 0 && hIdx < cut) cut = hIdx;
        String withoutQuery = lower.substring(0, cut);

        if (withoutQuery.contains("microsoftonline.com")
                || withoutQuery.contains("login.live.com")
                || withoutQuery.contains("login.windows.net")
                || withoutQuery.contains("/oauth")
                || withoutQuery.contains("/saml")
                || withoutQuery.contains("/idp/")) {
            return false;
        }

        String pathOnly = withoutQuery;
        int schemeIdx = withoutQuery.indexOf("://");
        if (schemeIdx >= 0) {
            int slashIdx = withoutQuery.indexOf('/', schemeIdx + 3);
            pathOnly = slashIdx >= 0 ? withoutQuery.substring(slashIdx) : "/";
        }
        try {
            Uri parsed = Uri.parse(url.trim());
            if (parsed.getPath() != null && !parsed.getPath().isEmpty()) {
                pathOnly = parsed.getPath().toLowerCase(Locale.US);
            }
        } catch (Exception ignored) {}

        return pathOnly.contains("/time-card-viewer") || pathOnly.contains("/time-off-viewer");
    }

    /** Returns true when a URL is the Time Card / Time Off Viewer destination page (never login/welcome pages). */
    static boolean isPortalDestinationUrl(Context context, String url) {
        return isTimecardOrTimeoffViewerUrl(url);
    }

    /** Returns true when a URL is a sign-in / SSO / welcome page (any non-viewer HTTP URL outside mysignins). */
    static boolean isExternalSsoLoginUrl(Context context, String url) {
        if (url == null || url.trim().isEmpty() || isTimecardOrTimeoffViewerUrl(url)) {
            return false;
        }
        String lower = url.trim().toLowerCase();
        if (lower.contains("mysignins.microsoft.com")) {
            return false;
        }
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Persists or clears the local authentication marker flag cleanly. */
    static void setAuthenticatedFlag(Context context, boolean authenticated) {
        synchronized (FILE_LOCK) {
            try {
                File f = new File(context.getFilesDir(), AUTH_MARKER_FILENAME);
                if (authenticated) {
                    try (FileOutputStream fos = new FileOutputStream(f, false)) {
                        fos.write("1".getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                } else if (f.exists()) {
                    f.delete();
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_AUTH_SESSION_EXPIRED, !authenticated)
                        .apply();
            } catch (Exception ignored) {}
        }
    }

    /** Checks whether the user has a verified portal/SSO session marker or authenticated SSO cookies. */
    static boolean checkCookiesLoggedIn(Context context) {
        try {
            File authFlag = new File(context.getFilesDir(), AUTH_MARKER_FILENAME);
            if (authFlag.exists()) {
                return true;
            }
            boolean sessionMarkedExpired = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_AUTH_SESSION_EXPIRED, false);
            if (sessionMarkedExpired) {
                return false;
            }
            CookieManager cm = CookieManager.getInstance();
            String portalCookies = cm.getCookie(getPortalBaseUrl(context));
            String ssoCookies = cm.getCookie("https://login.microsoftonline.com");
            // Only match real authenticated session cookies, never unauthenticated visitor/analytics cookies
            boolean hasPortalAuth = portalCookies != null && portalCookies.contains("user_firstName");
            boolean hasSsoAuth = ssoCookies != null && (
                    ssoCookies.contains("ESTSAUTHPERSISTENT")
                            || ssoCookies.contains("ESTSAUTH=")
            );
            return hasPortalAuth || hasSsoAuth;
        } catch (Exception e) {
            return false;
        }
    }

    /** Applies common WebView defaults shared across foreground and headless scraper views. */
    @SuppressLint("SetJavaScriptEnabled")
    static void setupWebViewDefaults(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(USER_AGENT_MOBILE);
    }

    /** Returns true if a URL is a non-essential background image, font, or analytics script. */
    static boolean isBlockedBackgroundAsset(String rawUrl) {
        if (rawUrl == null) return false;
        String url = rawUrl.toLowerCase();
        return url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg")
                || url.endsWith(".gif") || url.endsWith(".woff2") || url.endsWith(".woff")
                || url.endsWith(".ttf") || url.contains("tealium") || url.contains("adroll")
                || url.contains("doubleclick") || url.contains("google-analytics")
                || url.contains("demdex") || url.contains("hotjar") || url.contains("optimizely");
    }

    /** Returns an empty text/plain WebResourceResponse for blocked background assets. */
    static WebResourceResponse emptyAssetResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                new ByteArrayInputStream(new byte[0])
        );
    }

    /** Resolves an app asset from OTA local directory first, falling back to APK assets/www/. */
    static WebResourceResponse loadAppAssetResponse(Context context, String rawPath) {
        String path = rawPath;
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.startsWith("/static/")) {
            path = path.substring("/static".length());
        }
        String relPath = path.startsWith("/") ? path.substring(1) : path;

        // 1. Check OTA override directory (with canonical containment check against traversal)
        try {
            File otaDir = new File(context.getFilesDir(), OTA_DIR_NAME);
            File otaFile = new File(otaDir, relPath);
            String canonicalBase = otaDir.getCanonicalPath() + File.separator;
            String canonicalTarget = otaFile.getCanonicalPath();
            if (canonicalTarget.startsWith(canonicalBase)
                    && otaFile.exists() && otaFile.isFile() && otaFile.length() > 0) {
                return new WebResourceResponse(getMimeType(relPath), "UTF-8", new FileInputStream(otaFile));
            }
        } catch (Exception ignored) {}

        // 2. Fallback to bundled APK assets
        String assetPath = "www/" + relPath;
        try {
            InputStream is = context.getAssets().open(assetPath);
            return new WebResourceResponse(getMimeType(assetPath), "UTF-8", is);
        } catch (Exception e) {
            return null;
        }
    }

    /** Maps common web extensions to MIME types. */
    static String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "text/plain";
    }

    /** Auto-clicks KMSI / "Ask later" / security interstitial bypass prompts in SSO flows. */
    static void injectMicrosoftSecurityBypass(Context context, WebView view) {
        if (view == null) return;
        String targetPortalUrl = JSONObject.quote(getTimecardPortalUrl(context));
        String bypassJs = "(function(){" +
                "  try {" +
                "    var skipSelectors = [" +
                "      '#btnAskLater', '#skipLink', 'a#skip', 'button#skip'," +
                "      'a[id*=\"AskLater\"]', 'button[id*=\"AskLater\"]', 'a[id*=\"skip\"]', 'button[id*=\"skip\"]'" +
                "    ];" +
                "    for (var i = 0; i < skipSelectors.length; i++) {" +
                "      var el = document.querySelector(skipSelectors[i]);" +
                "      if (el && el.offsetParent !== null) { el.click(); return 'CLICKED_SKIP_ID'; }" +
                "    }" +
                "    var links = Array.from(document.querySelectorAll('a, button, input[type=\"button\"], input[type=\"submit\"]'));" +
                "    for (var j = 0; j < links.length; j++) {" +
                "      var txt = (links[j].innerText || links[j].value || '').trim().toLowerCase();" +
                "      if (txt.indexOf('skip for now') !== -1 || txt.indexOf('ask later') !== -1 || txt.indexOf('skip setup') !== -1 || txt.indexOf('no thanks') !== -1) {" +
                "        links[j].click(); return 'CLICKED_SKIP_TEXT';" +
                "      }" +
                "    }" +
                "    var kmsiBtn = document.querySelector('#idSIButton9');" +
                "    var isKmsi = document.querySelector('#KmsiDescription') || document.querySelector('#idBtn_Back') || (document.body && document.body.innerText.toLowerCase().indexOf('stay signed in?') !== -1);" +
                "    if (kmsiBtn && isKmsi && kmsiBtn.offsetParent !== null) {" +
                "      kmsiBtn.click(); return 'CLICKED_KMSI';" +
                "    }" +
                "    var href = (window.location.href || '').toLowerCase();" +
                "    var bodyTxt = document.body ? (document.body.innerText || '').toLowerCase() : '';" +
                "    var isSecurityScreen = href.indexOf('mysignins.microsoft.com') !== -1 ||" +
                "                           bodyTxt.indexOf('more information required') !== -1 ||" +
                "                           bodyTxt.indexOf('keep your account secure') !== -1 ||" +
                "                           bodyTxt.indexOf('help us protect your account') !== -1 ||" +
                "                           bodyTxt.indexOf('secure your account') !== -1;" +
                "    if (isSecurityScreen && !window.__tvcSecurityRedirectScheduled) {" +
                "      window.__tvcSecurityRedirectScheduled = true;" +
                "      setTimeout(function() { window.location.href = " + targetPortalUrl + "; }, 800);" +
                "      return 'SECURITY_REDIRECT_SCHEDULED';" +
                "    }" +
                "  } catch(e) {}" +
                "  return 'NOOP';" +
                "})();";
        view.evaluateJavascript(bypassJs, null);
    }

    /** Creates the Daily Timecard Alerts notification channel on Android 8+. */
    static void setupNotificationChannel(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel channel = new NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            "Daily Timecard Alerts",
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    channel.setDescription("Notifications whenever a new daily timecard is posted");
                    nm.createNotificationChannel(channel);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Reads the shared set of notified shift/audit/Option Day signatures under NOTIF_LOCK. */
    static Set<String> getNotifiedSignatures(Context context) {
        synchronized (NOTIF_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                Set<String> stored = prefs.getStringSet(PREF_NOTIFIED_SIGNATURES, Collections.emptySet());
                return stored != null ? new HashSet<>(stored) : new HashSet<>();
            } catch (Exception ignored) {
                return new HashSet<>();
            }
        }
    }

    /** Persists the shared set of notified shift/audit/Option Day signatures under NOTIF_LOCK. */
    static void saveNotifiedSignatures(Context context, Set<String> signatures) {
        if (signatures == null) return;
        synchronized (NOTIF_LOCK) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putStringSet(PREF_NOTIFIED_SIGNATURES, new HashSet<>(signatures)).apply();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Posts a native Android notification that deep-links into MainActivity for the given shift.
     * Keys notifId deterministically by weekEndDate|shiftDate so foreground & background updates for
     * the same shift date replace in-place rather than stacking duplicate notifications, and enforces
     * a shared cross-component deduplication guard across MainActivity and TimecardSyncJobService.
     */
    static void postShiftNotification(Context context, String title, String body, String weekEndDate, String shiftDate) {
        try {
            setupNotificationChannel(context);
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
                return;
            }

            String safeTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "New Timecard Added";
            String safeBody = (body != null && !body.trim().isEmpty()) ? body.trim() : "A new daily timecard has been posted.";
            String normWeek = weekEndDate != null ? weekEndDate.trim() : "";
            String normShift = shiftDate != null ? shiftDate.trim() : "";

            String upperTitle = safeTitle.toUpperCase(Locale.US);
            String category;
            if (upperTitle.contains("TIMECARD & PUNCH") || upperTitle.contains("MORE UPDATE")) {
                category = "combined";
            } else if (upperTitle.contains("PUNCH") || upperTitle.contains("MODIFICATION")) {
                category = "punch";
            } else if (upperTitle.contains("OPTION")) {
                category = "option_day";
            } else {
                category = "complete";
            }

            synchronized (NOTIF_LOCK) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long lastMs = prefs.getLong(PREF_LAST_NOTIF_MS, 0L);
                String lastKey = prefs.getString(PREF_LAST_NOTIF_KEY, "");
                String lastBodyHash = prefs.getString(PREF_LAST_NOTIF_BODY_HASH, "");

                String dedupKey = (!normShift.isEmpty() || !normWeek.isEmpty())
                        ? (normWeek + "|" + normShift + "|" + category)
                        : (safeTitle + "|" + safeBody);
                String bodyHash = String.valueOf((safeTitle + "|" + safeBody).hashCode());

                long elapsed = now - lastMs;
                if (elapsed >= 0L) {
                    if (dedupKey.equals(lastKey) && elapsed < CROSS_CALLER_NOTIF_DEDUP_MS) {
                        return;
                    }
                    if (bodyHash.equals(lastBodyHash) && elapsed < IDENTICAL_PAYLOAD_DEDUP_MS) {
                        return;
                    }
                }

                Set<String> sigs = new HashSet<>(prefs.getStringSet(PREF_NOTIFIED_SIGNATURES, Collections.emptySet()));
                boolean sigAdded = false;
                if (normShift.length() == 10 && normShift.charAt(4) == '-' && normShift.charAt(7) == '-') {
                    if ("option_day".equals(category)) {
                        sigAdded = sigs.add(normShift + "|option_day");
                    } else if ("complete".equals(category) || "combined".equals(category)) {
                        sigAdded = sigs.add(normShift + "|complete");
                    }
                }

                SharedPreferences.Editor editor = prefs.edit()
                        .putString(PREF_LAST_NOTIF_KEY, dedupKey)
                        .putString(PREF_LAST_NOTIF_BODY_HASH, bodyHash)
                        .putLong(PREF_LAST_NOTIF_MS, now);
                if (sigAdded && sigs.size() <= MAX_NOTIFIED_SIGNATURES) {
                    editor.putStringSet(PREF_NOTIFIED_SIGNATURES, sigs);
                }
                editor.apply();
            }

            String idSeed = !normShift.isEmpty()
                    ? (normWeek + "|" + normShift)
                    : (!normWeek.isEmpty() ? normWeek : "timecard_general_alert");
            int notifId = idSeed.hashCode() & 0x7fffffff;
            if (notifId == 0) notifId = 1;

            Intent intent = new Intent(context, MainActivity.class);
            intent.setAction("com.timecard.tracker.ACTION_FOCUS_SHIFT_" + notifId);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (!normWeek.isEmpty()) {
                intent.putExtra(EXTRA_WEEK_END_DATE, normWeek);
            }
            if (!normShift.isEmpty()) {
                intent.putExtra(EXTRA_SHIFT_DATE, normShift);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getActivity(context, notifId, intent, flags);

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    : new Notification.Builder(context);

            builder.setSmallIcon(R.drawable.ic_stat_tvc)
                    .setColor(0xFFF59E0B)
                    .setContentTitle(safeTitle)
                    .setContentText(safeBody)
                    .setStyle(new Notification.BigTextStyle().bigText(safeBody))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            nm.notify(notifId, builder.build());
        } catch (Exception ignored) {}
    }

    /** Cumulatively merges incoming archive JSON into timecard_archive_v1.json under FILE_LOCK. */
    static void mergeAndSaveArchiveJsonSync(Context context, String jsonInput) {
        if (jsonInput == null || jsonInput.trim().isEmpty()) return;
        synchronized (FILE_LOCK) {
            try {
                File f = new File(context.getFilesDir(), ARCHIVE_FILENAME);
                JSONObject incoming = new JSONObject(jsonInput);
                if (!f.exists() || f.length() == 0) {
                    writeTextFileAtomic(context, ARCHIVE_FILENAME, jsonInput);
                    return;
                }

                byte[] existingBytes = readFileFully(f);
                if (existingBytes.length == 0) {
                    writeTextFileAtomic(context, ARCHIVE_FILENAME, jsonInput);
                    return;
                }

                JSONObject existing = new JSONObject(new String(existingBytes, StandardCharsets.UTF_8));
                JSONObject combined = new JSONObject();

                String empId = incoming.optString("emp_id", "");
                if (empId.isEmpty()) empId = existing.optString("emp_id", "");
                combined.put("emp_id", empId);

                combined.put("archived_weeks", mergeWeekSummaries(
                        incoming.optJSONArray("archived_weeks"),
                        existing.optJSONArray("archived_weeks")
                ));
                combined.put("week_details", mergeWeekDetails(
                        incoming.optJSONObject("week_details"),
                        existing.optJSONObject("week_details")
                ));
                combined.put("audit_logs", mergeKeyedRecords(
                        incoming.optJSONArray("audit_logs"),
                        existing.optJSONArray("audit_logs"),
                        new String[]{"week_end_date", "date", "field_name", "old_value", "new_value"}
                ));
                combined.put("corrections", mergeKeyedRecords(
                        incoming.optJSONArray("corrections"),
                        existing.optJSONArray("corrections"),
                        new String[]{"timestamp", "item", "old_val", "new_val"}
                ));

                writeTextFileAtomic(context, ARCHIVE_FILENAME, combined.toString());
            } catch (Exception ignored) {}
        }
    }

    private static JSONArray mergeWeekSummaries(JSONArray incoming, JSONArray existing) {
        LinkedHashMap<String, JSONObject> map = new LinkedHashMap<>();
        putWeeksByEndDate(map, incoming, true);
        putWeeksByEndDate(map, existing, false);
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys, Collections.reverseOrder());
        JSONArray out = new JSONArray();
        for (String k : keys) {
            out.put(map.get(k));
        }
        return out;
    }

    private static void putWeeksByEndDate(LinkedHashMap<String, JSONObject> map, JSONArray arr, boolean overwrite) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String wed = obj.optString("week_end_date", "");
            if (!wed.isEmpty() && (overwrite || !map.containsKey(wed))) {
                map.put(wed, obj);
            }
        }
    }

    private static JSONObject mergeWeekDetails(JSONObject incoming, JSONObject existing) {
        JSONObject out = new JSONObject();
        try {
            if (existing != null) {
                Iterator<String> exKeys = existing.keys();
                while (exKeys.hasNext()) {
                    String k = exKeys.next();
                    out.put(k, existing.get(k));
                }
            }
            if (incoming != null) {
                Iterator<String> inKeys = incoming.keys();
                while (inKeys.hasNext()) {
                    String k = inKeys.next();
                    out.put(k, incoming.get(k));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray mergeKeyedRecords(JSONArray incoming, JSONArray existing, String[] keyFields) {
        LinkedHashMap<String, JSONObject> map = new LinkedHashMap<>();
        putKeyedItems(map, incoming, keyFields, true);
        putKeyedItems(map, existing, keyFields, false);
        JSONArray out = new JSONArray();
        for (JSONObject item : map.values()) {
            out.put(item);
        }
        return out;
    }

    private static void putKeyedItems(LinkedHashMap<String, JSONObject> map, JSONArray arr, String[] keyFields, boolean overwrite) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int f = 0; f < keyFields.length; f++) {
                if (f > 0) sb.append('|');
                sb.append(obj.optString(keyFields[f], ""));
            }
            String k = sb.toString();
            if (overwrite || !map.containsKey(k)) {
                map.put(k, obj);
            }
        }
    }

    /** Clears cached background sync timestamps, fingerprints, and notification dedup state cleanly on logout. */
    static void clearBackgroundSyncPrefs(Context context) {
        synchronized (NOTIF_LOCK) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(PREF_LAST_BG_SYNC_MS)
                        .remove(PREF_LAST_PAGE_FINGERPRINT)
                        .remove(PREF_LAST_TIMEOFF_SYNC_MS)
                        .remove(PREF_LAST_TIMEOFF_FINGERPRINT)
                        .remove(PREF_LAST_ROLLOVER_SYNC_MS)
                        .remove(PREF_NOTIFIED_SIGNATURES)
                        .remove(PREF_LAST_NOTIF_KEY)
                        .remove(PREF_LAST_NOTIF_BODY_HASH)
                        .remove(PREF_LAST_NOTIF_MS)
                        .apply();
            } catch (Exception ignored) {}
        }
    }

    /** Returns true if Android battery optimization is disabled ("Unrestricted") for this app. */
    static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /** Prompts the user to set battery usage to Unrestricted so closed-app sync isn't Doze-delayed. */
    @SuppressLint("BatteryLife")
    static void requestIgnoreBatteryOptimizations(Context context) {
        if (isIgnoringBatteryOptimizations(context)) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                if (!(context instanceof android.app.Activity)) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }
}

