package com.timecard.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Closed-App Background Service that periodically checks for new daily timecards
 * and punch updates via Android JobScheduler, firing push notifications even when
 * the app is completely closed or device was rebooted.
 */
public class TimecardSyncJobService extends JobService {

    public static final int JOB_ID = 2026;
    private static final String NOTIFICATION_CHANNEL_ID = "daily_timecard_channel";
    private static final String STORE_FILENAME = "timecard_store_v4.json";
    private static final String AUTH_MARKER_FILENAME = "portal_auth_v4.flag";
    private static final String PORTAL_CFG_FILENAME = "portal_config.json";
    private static final String DEFAULT_TIMECARD_PATH = "/us/en/tools-and-resources/pay-and-benefits/time-card-viewer.html";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView headlessWebView;
    private boolean isFinished = false;

    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        ComponentName serviceComponent = new ComponentName(context, TimecardSyncJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, serviceComponent)
                .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L) // 15-minute periodic interval
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);

        scheduler.schedule(builder.build());
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        isFinished = false;

        // Skip background sync if user has logged out
        File authFlag = new File(getFilesDir(), AUTH_MARKER_FILENAME);
        if (!authFlag.exists()) {
            return false;
        }

        mainHandler.post(() -> runHeadlessCheck(params));
        return true; // Asynchronous work running
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        cleanupWebView();
        isFinished = true;
        return true; // Reschedule on abort
    }

    private void runHeadlessCheck(final JobParameters params) {
        try {
            headlessWebView = new WebView(getApplicationContext());
            WebSettings settings = headlessWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            );

            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(headlessWebView, true);

            headlessWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    startPolling(params, 0);
                }
            });

            // 45-second watchdog to guarantee termination and protect battery
            mainHandler.postDelayed(() -> {
                if (!isFinished) {
                    finishJobCleanly(params, false);
                }
            }, 45000);

            headlessWebView.loadUrl(getPortalTimecardUrl());

        } catch (Exception e) {
            finishJobCleanly(params, false);
        }
    }

    private void startPolling(final JobParameters params, final int attempt) {
        if (isFinished || headlessWebView == null) return;
        if (attempt > 30) {
            finishJobCleanly(params, false);
            return;
        }

        String checkJs = "(function(){" +
                "  var w = document.querySelector('.timecard-wrapper');" +
                "  if (!w) return JSON.stringify({ready: false});" +
                "  var loader = document.querySelector('#tcvLoader');" +
                "  var loaderVis = loader && loader.offsetParent !== null && window.getComputedStyle(loader).display !== 'none';" +
                "  var rows = document.querySelectorAll('tr.timecard-card');" +
                "  if (loaderVis || rows.length === 0) return JSON.stringify({ready: false});" +
                "  var shifts = [];" +
                "  for (var i = 0; i < rows.length; i++) {" +
                "    var r = rows[i];" +
                "    var dEl = r.querySelector('.day-date, .shift-date, td:first-child');" +
                "    var hEl = r.querySelector('.total-hours, td:nth-child(5)');" +
                "    var d = dEl ? dEl.innerText.trim() : '';" +
                "    var h = hEl ? hEl.innerText.trim() : '';" +
                "    if (d) shifts.push({date: d, hours: h});" +
                "  }" +
                "  var sel = document.querySelector('.timecard-date-dropdown select');" +
                "  var wed = sel && sel.value ? sel.value.trim() : '';" +
                "  return JSON.stringify({ready: true, shifts: shifts, week_end_date: wed});" +
                "})();";

        headlessWebView.evaluateJavascript(checkJs, resultJson -> {
            try {
                if (resultJson != null && !resultJson.equals("null")) {
                    String clean = resultJson.startsWith("\"") && resultJson.endsWith("\"")
                            ? new JSONObject("{\"res\":" + resultJson + "}").getString("res")
                            : resultJson;

                    JSONObject res = new JSONObject(clean);
                    if (res.optBoolean("ready", false)) {
                        handleExtractedShifts(res);
                        finishJobCleanly(params, false);
                        return;
                    }
                }
            } catch (Exception ignored) {}

            mainHandler.postDelayed(() -> startPolling(params, attempt + 1), 1000);
        });
    }

    private void handleExtractedShifts(JSONObject res) {
        try {
            JSONArray newShifts = res.optJSONArray("shifts");
            String weekEndDate = res.optString("week_end_date", "");
            if (newShifts == null || newShifts.length() == 0) return;

            File storeFile = new File(getFilesDir(), STORE_FILENAME);
            Set<String> knownDates = new HashSet<>();

            JSONObject storeObj = null;
            if (storeFile.exists()) {
                byte[] b = readFileFully(storeFile);
                if (b.length > 0) {
                    storeObj = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    JSONArray weeks = storeObj.optJSONArray("weeks");
                    if (weeks != null && weeks.length() > 0) {
                        JSONObject latestWeek = weeks.getJSONObject(0);
                        JSONArray existingShifts = latestWeek.optJSONArray("shifts");
                        if (existingShifts != null) {
                            for (int i = 0; i < existingShifts.length(); i++) {
                                JSONObject s = existingShifts.getJSONObject(i);
                                String d = s.optString("date", "");
                                if (!d.isEmpty()) knownDates.add(d);
                            }
                        }
                    }
                }
            }

            // Detect newly posted shift dates
            boolean hasNewShifts = false;
            for (int j = 0; j < newShifts.length(); j++) {
                JSONObject s = newShifts.getJSONObject(j);
                String shiftDate = s.optString("date", "");
                String hours = s.optString("hours", "0.00");

                if (!shiftDate.isEmpty() && !knownDates.contains(shiftDate)) {
                    knownDates.add(shiftDate);
                    hasNewShifts = true;
                    // New daily timecard found while app was closed!
                    postNativeNotification(
                            "New Timecard Posted!",
                            "Shift for " + shiftDate + " (" + hours + " hrs) is now available. Tap to inspect.",
                            weekEndDate,
                            shiftDate
                    );
                }
            }

            // Save newly discovered shift dates so they are not repeatedly notified every 15 minutes
            if (hasNewShifts && storeObj != null) {
                JSONArray weeks = storeObj.optJSONArray("weeks");
                if (weeks != null && weeks.length() > 0) {
                    JSONObject latestWeek = weeks.getJSONObject(0);
                    JSONArray existingShifts = latestWeek.optJSONArray("shifts");
                    if (existingShifts == null) {
                        existingShifts = new JSONArray();
                        latestWeek.put("shifts", existingShifts);
                    }
                    for (int j = 0; j < newShifts.length(); j++) {
                        JSONObject s = newShifts.getJSONObject(j);
                        String shiftDate = s.optString("date", "");
                        boolean alreadyInList = false;
                        for (int k = 0; k < existingShifts.length(); k++) {
                            if (shiftDate.equals(existingShifts.getJSONObject(k).optString("date", ""))) {
                                alreadyInList = true;
                                break;
                            }
                        }
                        if (!alreadyInList) {
                            existingShifts.put(s);
                        }
                    }
                    try (FileOutputStream fos = new FileOutputStream(storeFile, false)) {
                        fos.write(storeObj.toString().getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }

        } catch (Exception ignored) {}
    }

    private void postNativeNotification(String title, String body, String weekEndDate, String shiftDate) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Daily Timecard Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alerts when a new daily timecard is posted by supervisor");
            nm.createNotificationChannel(channel);

            Intent intent = new Intent(this, MainActivity.class);
            if (weekEndDate != null && !weekEndDate.isEmpty()) intent.putExtra("EXTRA_WEEK_END_DATE", weekEndDate);
            if (shiftDate != null && !shiftDate.isEmpty()) intent.putExtra("EXTRA_SHIFT_DATE", shiftDate);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            int notifId = (int) (System.currentTimeMillis() % 100000);
            PendingIntent pi = PendingIntent.getActivity(this, notifId, intent, flags);

            Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);

            nm.notify(notifId, builder.build());
        } catch (Exception ignored) {}
    }

    private void finishJobCleanly(JobParameters params, boolean reschedule) {
        isFinished = true;
        cleanupWebView();
        try {
            jobFinished(params, reschedule);
        } catch (Exception ignored) {}
    }

    private void cleanupWebView() {
        mainHandler.post(() -> {
            if (headlessWebView != null) {
                try {
                    headlessWebView.stopLoading();
                    headlessWebView.loadUrl("about:blank");
                    headlessWebView.destroy();
                } catch (Exception ignored) {}
                headlessWebView = null;
            }
        });
    }

    private String getPortalTimecardUrl() {
        try {
            File f = new File(getFilesDir(), PORTAL_CFG_FILENAME);
            if (f.exists()) {
                byte[] data = readFileFully(f);
                if (data.length > 0) {
                    JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    String base = obj.optString("base_url", "");
                    if (!base.isEmpty()) return base + DEFAULT_TIMECARD_PATH;
                }
            }
        } catch (Exception ignored) {}
        String base = new String(
                android.util.Base64.decode("aHR0cHM6Ly93d3cudXBzZXJzLmNvbQ==", android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8
        );
        return base + DEFAULT_TIMECARD_PATH;
    }

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
}

