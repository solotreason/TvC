package com.timecard.tracker;

import android.annotation.SuppressLint;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Closed-app periodic JobService that scrapes updated timecard & time-off HTML in the background,
 * uses smart shift-aware battery throttling and unchanged-DOM fingerprint short-circuiting,
 * invokes the shared android_bridge.js engine only when data changes, and posts precision alerts
 * strictly for: (1) completed daily timecards, (2) supervisor punch modifications, and (3) Option Day usage.
 */
public class TimecardSyncJobService extends JobService {

    public static final int JOB_ID = 2026;
    public static final int FAST_JOB_ID = 2027;

    private static final String PREF_NOTIFIED_SIGNATURES = "notified_shift_signatures_v2";
    private static final long PERIODIC_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long PERIODIC_FLEX_MS = 5 * 60 * 1000L;
    private static final long PRIME_CHECK_DELAY_MS = 5 * 60 * 1000L;
    private static final long PRIME_CHECK_DEADLINE_MS = 8 * 60 * 1000L;
    private static final long FAST_CHECK_DELAY_MS = 10 * 60 * 1000L;
    private static final long FAST_CHECK_DEADLINE_MS = 12 * 60 * 1000L;
    private static final long PRIME_MIN_GAP_MS = 4 * 60 * 1000L;
    private static final long ACTIVE_MIN_GAP_MS = 8 * 60 * 1000L;
    private static final long OVERNIGHT_MIN_GAP_MS = 2L * 60 * 60 * 1000L;
    private static final long POST_COMPLETE_MIN_GAP_MS = 3L * 60 * 60 * 1000L;
    private static final long POWER_SAVE_MIN_GAP_MS = 2L * 60 * 60 * 1000L;
    private static final long TIMEOFF_CHECK_INTERVAL_MS = 30 * 60 * 1000L;
    private static final int LOW_BATTERY_SKIP_PCT = 15;
    private static final long WATCHDOG_TIMEOUT_MS = 55_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView headlessWebView;
    private WebView bridgeWebView;
    private boolean isFinished = false;
    private boolean bridgeIngestStarted = false;
    private int interactiveLoginHits = 0;
    private Runnable watchdogRunnable;

    private String preStoreJson = "";
    private String pendingPageFingerprint = "";
    private String pendingTimeoffFingerprint = "";
    private String capturedTimeoffHtml = "";
    private final List<CapturedWeek> capturedWeeks = new ArrayList<>();
    private final Set<String> capturedWeekKeys = new HashSet<>();

    private static final class CapturedWeek {
        final String weekEndDate;
        final String rawHtml;

        CapturedWeek(String weekEndDate, String rawHtml) {
            this.weekEndDate = weekEndDate;
            this.rawHtml = rawHtml;
        }
    }

    /** Schedules periodic closed-app checks (15m safety net) + shift-aware fast check when waiting. */
    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        ComponentName serviceComponent = new ComponentName(context, TimecardSyncJobService.class);

        JobInfo pending = scheduler.getPendingJob(JOB_ID);
        if (pending == null || !pending.isPeriodic() || pending.getIntervalMillis() != PERIODIC_INTERVAL_MS) {
            JobInfo jobInfo = new JobInfo.Builder(JOB_ID, serviceComponent)
                    .setPeriodic(PERIODIC_INTERVAL_MS, PERIODIC_FLEX_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(jobInfo);
        }

        scheduleNextFastCheckIfWaiting(context, scheduler, serviceComponent);
    }

    private static void scheduleNextFastCheckIfWaiting(
            Context context,
            JobScheduler scheduler,
            ComponentName serviceComponent
    ) {
        try {
            if (!PortalHelper.checkCookiesLoggedIn(context)) return;
            String storeJson = PortalHelper.readFileString(context, PortalHelper.STORE_FILENAME);
            if (isTodayShiftCompletedInStore(storeJson) || isOvernightQuietWindow(storeJson)) {
                scheduler.cancel(FAST_JOB_ID);
                return;
            }
            boolean inPrimeWindow = isPrimePostShiftWindow(storeJson);
            long targetDelay = inPrimeWindow ? PRIME_CHECK_DELAY_MS : FAST_CHECK_DELAY_MS;
            long targetDeadline = inPrimeWindow ? PRIME_CHECK_DEADLINE_MS : FAST_CHECK_DEADLINE_MS;

            JobInfo existingFast = scheduler.getPendingJob(FAST_JOB_ID);
            if (existingFast != null && existingFast.getMinLatencyMillis() <= targetDelay) {
                return;
            }
            JobInfo fastJob = new JobInfo.Builder(FAST_JOB_ID, serviceComponent)
                    .setMinimumLatency(targetDelay)
                    .setOverrideDeadline(targetDeadline)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(fastJob);
        } catch (Exception ignored) {}
    }

    /** Cancels scheduled closed-app background checks. */
    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
            scheduler.cancel(FAST_JOB_ID);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!PortalHelper.checkCookiesLoggedIn(this)) {
            return false;
        }

        String storeSnapshot = PortalHelper.readFileString(this, PortalHelper.STORE_FILENAME);
        if (shouldSkipForBatteryEfficiency(storeSnapshot)) {
            schedule(this);
            return false;
        }

        isFinished = false;
        bridgeIngestStarted = false;
        interactiveLoginHits = 0;
        capturedWeeks.clear();
        capturedWeekKeys.clear();
        pendingPageFingerprint = "";
        pendingTimeoffFingerprint = "";
        capturedTimeoffHtml = "";
        preStoreJson = storeSnapshot;

        watchdogRunnable = () -> finishJobCleanly(params, false);
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS);

        mainHandler.post(() -> runHeadlessCheck(params));
        return true;
    }

    /** Zero-WebView pre-flight check to conserve battery when low, in power saver, overnight, or already synced today. */
    private boolean shouldSkipForBatteryEfficiency(String storeJson) {
        try {
            Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            boolean isCharging = false;
            int batteryPct = -1;
            if (batteryIntent != null) {
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    batteryPct = (level * 100) / scale;
                }
            }

            if (!isCharging && batteryPct >= 0 && batteryPct < LOW_BATTERY_SKIP_PCT) {
                return true;
            }

            SharedPreferences prefs = getSharedPreferences(PortalHelper.PREFS_NAME, MODE_PRIVATE);
            long lastSyncMs = Math.max(
                    prefs.getLong(PortalHelper.PREF_LAST_BG_SYNC_MS, 0L),
                    PortalHelper.getStoreLastModified(this)
            );
            if (lastSyncMs <= 0L) {
                return false;
            }

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean powerSave = pm != null && pm.isPowerSaveMode();
            boolean inPrimeWindow = isPrimePostShiftWindow(storeJson);

            long minGapMs = inPrimeWindow ? PRIME_MIN_GAP_MS : ACTIVE_MIN_GAP_MS;
            if (!isCharging) {
                if (powerSave) {
                    minGapMs = POWER_SAVE_MIN_GAP_MS;
                } else if (isTodayShiftCompletedInStore(storeJson)) {
                    minGapMs = POST_COMPLETE_MIN_GAP_MS;
                } else if (isOvernightQuietWindow(storeJson)) {
                    minGapMs = OVERNIGHT_MIN_GAP_MS;
                }
            }

            return (System.currentTimeMillis() - lastSyncMs) < minGapMs;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        cancelWatchdog();
        isFinished = true;
        cleanupWebViews();
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void runHeadlessCheck(final JobParameters params) {
        if (isFinished) return;
        try {
            headlessWebView = new WebView(this);
            PortalHelper.setupWebViewDefaults(headlessWebView);

            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(headlessWebView, true);

            headlessWebView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request != null && request.getUrl() != null
                            && PortalHelper.isBlockedBackgroundAsset(request.getUrl().toString())) {
                        return PortalHelper.emptyAssetResponse();
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    CookieManager.getInstance().flush();
                    PortalHelper.injectMicrosoftSecurityBypass(TimecardSyncJobService.this, view);
                }
            });

            headlessWebView.loadUrl(PortalHelper.getTimecardPortalUrl(this));
            mainHandler.postDelayed(() -> pollPortalReady(params, 0), 1200);
        } catch (Exception e) {
            finishJobCleanly(params, false);
        }
    }

    private void pollPortalReady(final JobParameters params, final int attempt) {
        if (isFinished || headlessWebView == null) return;
        if (attempt > 45) {
            finishJobCleanly(params, false);
            return;
        }

        String checkJs = "(function(){" +
                "  try {" +
                "    var loginEl = document.querySelector('input[type=\"password\"], #i0116, #idRichContext_DisplaySign, #idTxtBx_SAOTCC_OTC');" +
                "    var kmsiEl = document.querySelector('#idSIButton9, #btnAskLater, #skipLink');" +
                "    var interactiveLogin = !!(loginEl && loginEl.offsetParent !== null && !(kmsiEl && kmsiEl.offsetParent !== null));" +
                "    var w = document.querySelector('.timecard-wrapper');" +
                "    var loader = document.querySelector('#tcvLoader');" +
                "    var loaderVis = !!(loader && loader.offsetParent !== null && window.getComputedStyle(loader).display !== 'none');" +
                "    var rows = document.querySelectorAll('tr.timecard-card');" +
                "    if (!w || loaderVis || rows.length === 0) {" +
                "      return JSON.stringify({ready: false, interactiveLogin: interactiveLogin});" +
                "    }" +
                "    var sel = document.querySelector('.timecard-date-dropdown select');" +
                "    var selectedWeek = sel && sel.value ? sel.value.trim() : '';" +
                "    var statusText = (sel && sel.selectedIndex >= 0) ? (sel.options[sel.selectedIndex].text || '').trim() : '';" +
                "    var opts = [];" +
                "    if (sel) {" +
                "      for (var i = 0; i < sel.options.length; i++) {" +
                "        var v = (sel.options[i].value || '').trim();" +
                "        if (v) opts.push(v);" +
                "      }" +
                "    }" +
                "    var grossEl = document.querySelector('.weekGrossPay');" +
                "    var hrsEl = document.querySelector('.weekTotalHours');" +
                "    var updEl = document.querySelector('.timecard-wrapper__lastUpdated');" +
                "    var rowSigs = [];" +
                "    for (var r = 0; r < rows.length; r++) {" +
                "      rowSigs.push((rows[r].getAttribute('data-date') || '') + ':' + (rows[r].innerText || '').replace(/\\s+/g, ' ').trim());" +
                "    }" +
                "    var corrEl = document.querySelector('.corrections-wrapper, #correctionsTable');" +
                "    var domSignature = [" +
                "      selectedWeek, statusText," +
                "      grossEl ? grossEl.innerText.trim() : ''," +
                "      hrsEl ? hrsEl.innerText.trim() : ''," +
                "      updEl ? updEl.innerText.trim() : ''," +
                "      rowSigs.join(';')," +
                "      corrEl ? corrEl.innerText.replace(/\\s+/g, ' ').trim() : ''" +
                "    ].join('|');" +
                "    return JSON.stringify({ready: true, selectedWeek: selectedWeek, weekOptions: opts, domSignature: domSignature});" +
                "  } catch(e) { return JSON.stringify({ready: false}); }" +
                "})();";

        headlessWebView.evaluateJavascript(checkJs, raw -> {
            if (isFinished) return;
            try {
                JSONObject res = new JSONObject(unquoteJsString(raw));
                if (res.optBoolean("ready", false)) {
                    interactiveLoginHits = 0;
                    PortalHelper.setAuthenticatedFlag(TimecardSyncJobService.this, true);
                    String selectedWeek = res.optString("selectedWeek", "");
                    List<String> weekOptions = jsonArrayToList(res.optJSONArray("weekOptions"));
                    String domSig = res.optString("domSignature", "");
                    pendingPageFingerprint = selectedWeek + ":" + sha256Hex(domSig);

                    SharedPreferences prefs = getSharedPreferences(PortalHelper.PREFS_NAME, MODE_PRIVATE);
                    String lastFp = prefs.getString(PortalHelper.PREF_LAST_PAGE_FINGERPRINT, "");
                    boolean needsRollover = weekOptions.size() >= 2 && isWeekNeedingRolloverCheck(weekOptions.get(1));

                    // Short-circuit: if Time Card DOM matches saved fingerprint and week is on disk,
                    // only check Time Off Viewer if its periodic window is due; otherwise exit with 0 accordions/bridgeWebView
                    if (!domSig.isEmpty()
                            && !needsRollover
                            && pendingPageFingerprint.equals(lastFp)
                            && hasWeekInStore(preStoreJson, selectedWeek)) {
                        prefs.edit()
                                .putLong(PortalHelper.PREF_LAST_BG_SYNC_MS, System.currentTimeMillis())
                                .apply();
                        if (shouldCheckTimeOffBank(prefs)) {
                            loadAndCaptureTimeOff(params);
                        } else {
                            CookieManager.getInstance().flush();
                            finishJobCleanly(params, false);
                        }
                        return;
                    }

                    expandAccordionsAndCapture(params, selectedWeek, weekOptions, false);
                    return;
                }

                if (res.optBoolean("interactiveLogin", false)) {
                    interactiveLoginHits++;
                    if (interactiveLoginHits >= 5) {
                        finishJobCleanly(params, false);
                        return;
                    }
                } else {
                    interactiveLoginHits = 0;
                }
            } catch (Exception ignored) {}

            mainHandler.postDelayed(() -> pollPortalReady(params, attempt + 1), 800);
        });
    }

    private void expandAccordionsAndCapture(
            final JobParameters params,
            final String expectedWeek,
            final List<String> weekOptions,
            final boolean isRolloverFollowup
    ) {
        if (isFinished || headlessWebView == null) return;

        String expandJs = "(function(){" +
                "  try {" +
                "    var listBtn = document.querySelector('.list-view');" +
                "    if (listBtn && !listBtn.classList.contains('active')) listBtn.click();" +
                "    var rows = document.querySelectorAll('tr.timecard-card');" +
                "    for (var i = 0; i < rows.length; i++) {" +
                "      if (!rows[i].classList.contains('activeRow')) rows[i].click();" +
                "    }" +
                "  } catch(e) {}" +
                "  return 'EXPANDED';" +
                "})();";

        headlessWebView.evaluateJavascript(expandJs, ignored -> {
            if (isFinished) return;
            mainHandler.postDelayed(() -> captureCurrentDom(params, expectedWeek, weekOptions, isRolloverFollowup), 550);
        });
    }

    private void captureCurrentDom(
            final JobParameters params,
            final String expectedWeek,
            final List<String> weekOptions,
            final boolean isRolloverFollowup
    ) {
        if (isFinished || headlessWebView == null) return;

        String grabJs = "(function(){" +
                "  var sel = document.querySelector('.timecard-date-dropdown select');" +
                "  var wed = sel && sel.value ? sel.value.trim() : '';" +
                "  return JSON.stringify({weekEnd: wed, html: document.documentElement.outerHTML});" +
                "})();";

        headlessWebView.evaluateJavascript(grabJs, raw -> {
            if (isFinished) return;
            try {
                JSONObject obj = new JSONObject(unquoteJsString(raw));
                String html = obj.optString("html", "");
                String wed = obj.optString("weekEnd", expectedWeek != null ? expectedWeek : "");
                if (!html.isEmpty() && !capturedWeekKeys.contains(wed)) {
                    capturedWeekKeys.add(wed);
                    capturedWeeks.add(new CapturedWeek(wed, html));
                }

                // On week rollover (e.g., Sunday morning), also catch updates in the previous non-finalized week
                if (!isRolloverFollowup && weekOptions != null && weekOptions.size() >= 2) {
                    String prevWeek = weekOptions.get(1);
                    if (!prevWeek.isEmpty() && !capturedWeekKeys.contains(prevWeek) && isWeekNeedingRolloverCheck(prevWeek)) {
                        selectDropdownWeekAndCapture(params, prevWeek, weekOptions);
                        return;
                    }
                }
            } catch (Exception ignored) {}

            if (capturedWeeks.isEmpty()) {
                finishJobCleanly(params, false);
            } else {
                loadAndCaptureTimeOff(params);
            }
        });
    }

    private boolean shouldCheckTimeOffBank(SharedPreferences prefs) {
        long lastTimeoffMs = prefs.getLong(PortalHelper.PREF_LAST_TIMEOFF_SYNC_MS, 0L);
        if (lastTimeoffMs <= 0L || (System.currentTimeMillis() - lastTimeoffMs) >= TIMEOFF_CHECK_INTERVAL_MS) {
            return true;
        }
        return !hasTimeOffEntitlementsInStore(preStoreJson);
    }

    private void loadAndCaptureTimeOff(final JobParameters params) {
        if (isFinished || headlessWebView == null) {
            proceedAfterTimeOffCheck(params);
            return;
        }
        try {
            headlessWebView.loadUrl(PortalHelper.getTimeoffPortalUrl(this));
            mainHandler.postDelayed(() -> pollTimeOffReady(params, 0), 900);
        } catch (Exception e) {
            proceedAfterTimeOffCheck(params);
        }
    }

    private void pollTimeOffReady(final JobParameters params, final int attempt) {
        if (isFinished || headlessWebView == null) return;
        if (attempt > 10) {
            proceedAfterTimeOffCheck(params);
            return;
        }

        String checkTimeOffJs = "(function(){" +
                "  try {" +
                "    var w = document.querySelector('.timeoff-wrapper');" +
                "    var loader = document.querySelector('#tovLoader, #toLoader');" +
                "    var loaderVis = !!(loader && loader.offsetParent !== null && window.getComputedStyle(loader).display !== 'none');" +
                "    var rows = document.querySelectorAll('.timeoff-list-view table tbody tr');" +
                "    if (!w || loaderVis || rows.length === 0) return JSON.stringify({ready: false});" +
                "    var sigs = [];" +
                "    for (var i = 0; i < rows.length; i++) {" +
                "      sigs.push((rows[i].getAttribute('data-timeoff-type') || '') + ':' + (rows[i].innerText || '').replace(/\\s+/g, ' ').trim());" +
                "    }" +
                "    return JSON.stringify({ready: true, sig: sigs.join(';'), html: document.documentElement.outerHTML});" +
                "  } catch(e) { return JSON.stringify({ready: false}); }" +
                "})();";

        headlessWebView.evaluateJavascript(checkTimeOffJs, raw -> {
            if (isFinished) return;
            try {
                JSONObject res = new JSONObject(unquoteJsString(raw));
                if (res.optBoolean("ready", false)) {
                    String sig = res.optString("sig", "");
                    String html = res.optString("html", "");
                    pendingTimeoffFingerprint = sha256Hex(sig);

                    SharedPreferences prefs = getSharedPreferences(PortalHelper.PREFS_NAME, MODE_PRIVATE);
                    String lastTimeoffFp = prefs.getString(PortalHelper.PREF_LAST_TIMEOFF_FINGERPRINT, "");
                    prefs.edit().putLong(PortalHelper.PREF_LAST_TIMEOFF_SYNC_MS, System.currentTimeMillis()).apply();

                    if (!html.isEmpty() && (!pendingTimeoffFingerprint.equals(lastTimeoffFp) || !hasTimeOffEntitlementsInStore(preStoreJson))) {
                        capturedTimeoffHtml = html;
                    }
                    proceedAfterTimeOffCheck(params);
                    return;
                }
            } catch (Exception ignored) {}
            mainHandler.postDelayed(() -> pollTimeOffReady(params, attempt + 1), 500);
        });
    }

    private void proceedAfterTimeOffCheck(final JobParameters params) {
        if (capturedWeeks.isEmpty() && (capturedTimeoffHtml == null || capturedTimeoffHtml.isEmpty())) {
            CookieManager.getInstance().flush();
            finishJobCleanly(params, false);
        } else {
            ingestCapturedWeeksWithBridge(params);
        }
    }

    private boolean isWeekNeedingRolloverCheck(String weekEndDate) {
        if (preStoreJson == null || preStoreJson.trim().isEmpty()) return false;
        try {
            JSONObject store = new JSONObject(preStoreJson);
            JSONArray weeks = store.optJSONArray("weeks");
            if (weeks == null) return false;
            for (int i = 0; i < weeks.length(); i++) {
                JSONObject w = weeks.optJSONObject(i);
                if (w != null && weekEndDate.equals(w.optString("week_end_date", ""))) {
                    return !"Finalized".equalsIgnoreCase(w.optString("status", ""));
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void selectDropdownWeekAndCapture(
            final JobParameters params,
            final String targetWeek,
            final List<String> weekOptions
    ) {
        if (isFinished || headlessWebView == null) return;
        String selectJs = "(function(target){" +
                "  var sel = document.querySelector('.timecard-date-dropdown select');" +
                "  if (!sel) return false;" +
                "  sel.value = target;" +
                "  sel.dispatchEvent(new Event('change', {bubbles: true}));" +
                "  return true;" +
                "})(" + JSONObject.quote(targetWeek) + ");";

        headlessWebView.evaluateJavascript(selectJs, raw -> {
            if (isFinished) return;
            mainHandler.postDelayed(() -> pollRolloverWeekReady(params, targetWeek, weekOptions, 0), 900);
        });
    }

    private void pollRolloverWeekReady(
            final JobParameters params,
            final String targetWeek,
            final List<String> weekOptions,
            final int attempt
    ) {
        if (isFinished || headlessWebView == null) return;
        if (attempt > 15) {
            loadAndCaptureTimeOff(params);
            return;
        }

        String checkJs = "(function(){" +
                "  var loader = document.querySelector('#tcvLoader');" +
                "  var loaderVis = !!(loader && loader.offsetParent !== null && window.getComputedStyle(loader).display !== 'none');" +
                "  var sel = document.querySelector('.timecard-date-dropdown select');" +
                "  var cur = sel && sel.value ? sel.value.trim() : '';" +
                "  return JSON.stringify({ready: !loaderVis && !!cur, cur: cur});" +
                "})();";

        headlessWebView.evaluateJavascript(checkJs, raw -> {
            if (isFinished) return;
            try {
                JSONObject obj = new JSONObject(unquoteJsString(raw));
                if (obj.optBoolean("ready", false) && targetWeek.equals(obj.optString("cur", ""))) {
                    expandAccordionsAndCapture(params, targetWeek, weekOptions, true);
                    return;
                }
            } catch (Exception ignored) {}
            mainHandler.postDelayed(() -> pollRolloverWeekReady(params, targetWeek, weekOptions, attempt + 1), 600);
        });
    }

    /** Executes android_bridge.js inside a local offline WebView to parse & persist captured HTML identically to foreground. */
    @SuppressLint("SetJavaScriptEnabled")
    private void ingestCapturedWeeksWithBridge(final JobParameters params) {
        boolean hasTimeoff = capturedTimeoffHtml != null && !capturedTimeoffHtml.isEmpty();
        if (isFinished || (capturedWeeks.isEmpty() && !hasTimeoff)) {
            finishJobCleanly(params, false);
            return;
        }

        try {
            bridgeWebView = new WebView(this);
            WebSettings settings = bridgeWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            bridgeWebView.addJavascriptInterface(new BgNativeBridge(), "AndroidNative");

            bridgeWebView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) {
                        return super.shouldInterceptRequest(view, request);
                    }
                    String host = request.getUrl().getHost();
                    String path = request.getUrl().getPath();
                    if (PortalHelper.APP_HOST.equalsIgnoreCase(host)) {
                        if ("/__bg_bridge.html".equals(path)) {
                            String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                                    "<script src=\"/android_bridge.js\"></script></head><body></body></html>";
                            return new WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))
                            );
                        }
                        return PortalHelper.loadAppAssetResponse(TimecardSyncJobService.this, path);
                    }
                    return PortalHelper.emptyAssetResponse();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!bridgeIngestStarted && url != null && url.contains("__bg_bridge.html")) {
                        bridgeIngestStarted = true;
                        runBridgeIngestionJs(params);
                    }
                }
            });

            bridgeWebView.loadUrl("https://" + PortalHelper.APP_HOST + "/__bg_bridge.html");
        } catch (Exception e) {
            finishJobCleanly(params, false);
        }
    }

    private void runBridgeIngestionJs(final JobParameters params) {
        if (isFinished || bridgeWebView == null) return;

        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("  if (!window.TimecardBridge || !window.TimecardBridge.ingestWeekHtml) return JSON.stringify({ok:false});");
        for (CapturedWeek w : capturedWeeks) {
            js.append("  window.TimecardBridge.ingestWeekHtml(")
                    .append(quoteForJs(w.rawHtml))
                    .append(",")
                    .append(quoteForJs(w.weekEndDate))
                    .append(");");
        }
        if (capturedTimeoffHtml != null && !capturedTimeoffHtml.isEmpty()) {
            js.append("  if (window.TimecardBridge.ingestTimeoffHtml) {");
            js.append("    window.TimecardBridge.ingestTimeoffHtml(")
                    .append(quoteForJs(capturedTimeoffHtml))
                    .append(");");
            js.append("  }");
        }
        js.append("  if (window.TimecardBridge.recordBackgroundSyncComplete) {");
        js.append("    window.TimecardBridge.recordBackgroundSyncComplete(")
                .append(quoteForJs("Background auto-check updated " + Math.max(1, capturedWeeks.size()) + " week(s)."))
                .append(", 'Background Sync');");
        js.append("  }");
        js.append("  var newCards = window.TimecardBridge.getNewTimecardsJson ? window.TimecardBridge.getNewTimecardsJson() : '[]';");
        js.append("  var optAlerts = window.TimecardBridge.getOptionDayAlertsJson ? window.TimecardBridge.getOptionDayAlertsJson() : '[]';");
        js.append("  return JSON.stringify({ok:true, new_timecards: JSON.parse(newCards || '[]'), option_day_alerts: JSON.parse(optAlerts || '[]')});");
        js.append("})();");

        bridgeWebView.evaluateJavascript(js.toString(), raw -> {
            if (isFinished) return;
            try {
                JSONObject res = new JSONObject(unquoteJsString(raw));
                if (res.optBoolean("ok", false)) {
                    String postStoreJson = PortalHelper.readFileString(
                            TimecardSyncJobService.this,
                            PortalHelper.STORE_FILENAME
                    );
                    notifyForChanges(
                            res.optJSONArray("new_timecards"),
                            res.optJSONArray("option_day_alerts"),
                            preStoreJson,
                            postStoreJson
                    );
                    SharedPreferences.Editor editor = getSharedPreferences(PortalHelper.PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putLong(PortalHelper.PREF_LAST_BG_SYNC_MS, System.currentTimeMillis());
                    if (pendingPageFingerprint != null && !pendingPageFingerprint.isEmpty()) {
                        editor.putString(PortalHelper.PREF_LAST_PAGE_FINGERPRINT, pendingPageFingerprint);
                    }
                    if (pendingTimeoffFingerprint != null && !pendingTimeoffFingerprint.isEmpty()) {
                        editor.putString(PortalHelper.PREF_LAST_TIMEOFF_FINGERPRINT, pendingTimeoffFingerprint);
                    }
                    editor.apply();
                }
            } catch (Exception ignored) {}

            CookieManager.getInstance().flush();
            finishJobCleanly(params, false);
        });
    }

    /**
     * Fires deduplicated notifications strictly for:
     * (1) Completed daily timecard posted (never incomplete clock-in-only punches),
     * (2) Supervisor punch modifications (audit_logs), and
     * (3) Option Day used (either daily timecard Option Day row or Time Off Viewer Option Day balance decrease).
     */
    private void notifyForChanges(
            JSONArray bridgeNewTimecards,
            JSONArray bridgeOptionAlerts,
            String beforeJson,
            String afterJson
    ) {
        try {
            if (afterJson == null || afterJson.trim().isEmpty()) return;

            JSONObject afterStore = new JSONObject(afterJson);
            JSONObject beforeStore = (beforeJson != null && !beforeJson.trim().isEmpty())
                    ? new JSONObject(beforeJson)
                    : new JSONObject();

            boolean isInitialBaseline = beforeStore.optJSONArray("weeks") == null
                    || beforeStore.optJSONArray("weeks").length() == 0;

            SharedPreferences prefs = getSharedPreferences(PortalHelper.PREFS_NAME, MODE_PRIVATE);
            Set<String> notifiedSet = new HashSet<>(prefs.getStringSet(PREF_NOTIFIED_SIGNATURES, new HashSet<>()));
            Map<String, JSONObject> preShiftsByDate = indexShiftsByDate(beforeStore);
            Map<String, JSONObject> afterShiftsByDate = indexShiftsByDate(afterStore);

            if (isInitialBaseline) {
                for (Map.Entry<String, JSONObject> entry : afterShiftsByDate.entrySet()) {
                    JSONObject s = entry.getValue();
                    if (isShiftComplete(s)) {
                        notifiedSet.add(entry.getKey() + "|complete");
                    }
                    if (isOptionDayShift(s)) {
                        notifiedSet.add(entry.getKey() + "|option_day");
                    }
                }
                prefs.edit().putStringSet(PREF_NOTIFIED_SIGNATURES, notifiedSet).apply();
                return;
            }

            LinkedHashMap<String, JSONObject> candidateShifts = new LinkedHashMap<>();

            // 1. Shifts flagged directly by TimecardBridge.upsertParsedWeek
            if (bridgeNewTimecards != null) {
                for (int i = 0; i < bridgeNewTimecards.length(); i++) {
                    JSONObject item = bridgeNewTimecards.optJSONObject(i);
                    String date = item != null ? item.optString("date", "") : "";
                    if (!date.isEmpty() && afterShiftsByDate.containsKey(date)) {
                        candidateShifts.put(date, afterShiftsByDate.get(date));
                    }
                }
            }

            // 2. Shifts that transitioned to completed or Option Day since last sync
            for (Map.Entry<String, JSONObject> entry : afterShiftsByDate.entrySet()) {
                String date = entry.getKey();
                JSONObject postShift = entry.getValue();
                JSONObject preShift = preShiftsByDate.get(date);

                boolean becameCompleted = isShiftComplete(postShift)
                        && (preShift == null || !isShiftComplete(preShift));
                boolean becameOptionDay = isOptionDayShift(postShift)
                        && (preShift == null || !isOptionDayShift(preShift));

                if (becameCompleted || becameOptionDay) {
                    candidateShifts.put(date, postShift);
                }
            }

            List<JSONObject> freshCompletedAlerts = new ArrayList<>();
            List<JSONObject> freshOptionDayAlerts = new ArrayList<>();

            for (Map.Entry<String, JSONObject> entry : candidateShifts.entrySet()) {
                String date = entry.getKey();
                JSONObject s = entry.getValue();
                if (isOptionDayShift(s)) {
                    String optStageKey = date + "|option_day";
                    if (!notifiedSet.contains(optStageKey)) {
                        notifiedSet.add(optStageKey);
                        freshOptionDayAlerts.add(s);
                    }
                } else if (isShiftComplete(s)) {
                    // Only notify when shift is COMPLETED (never on clock-in-only open punch)
                    String compStageKey = date + "|complete";
                    if (!notifiedSet.contains(compStageKey)) {
                        notifiedSet.add(compStageKey);
                        freshCompletedAlerts.add(s);
                    }
                }
            }

            // Trigger (1): Completed daily timecard(s) posted
            if (!freshCompletedAlerts.isEmpty()) {
                JSONObject latest = freshCompletedAlerts.get(freshCompletedAlerts.size() - 1);
                String targetWeekEnd = latest.optString("week_end_date", "");
                String targetDate = latest.optString("date", "");
                String title = freshCompletedAlerts.size() == 1
                        ? "New Timecard Added (" + targetDate + ")"
                        : freshCompletedAlerts.size() + " New Daily Timecards Added";

                StringBuilder body = new StringBuilder();
                for (int i = 0; i < freshCompletedAlerts.size(); i++) {
                    if (i > 0) body.append(" | ");
                    JSONObject s = freshCompletedAlerts.get(i);
                    String startClk = s.optString("start_time_clock", "");
                    String endClk = s.optString("end_time_clock", "");
                    String punches = (!startClk.isEmpty() && !endClk.isEmpty())
                            ? startClk + " - " + endClk
                            : "Completed";
                    body.append(s.optString("date", ""))
                            .append(": ")
                            .append(punches)
                            .append(String.format(Locale.US, " (%.2f hrs • $%.2f)",
                                    s.optDouble("total_hours", 0.0),
                                    s.optDouble("gross_pay", 0.0)));
                }

                PortalHelper.postShiftNotification(this, title, body.toString(), targetWeekEnd, targetDate);
            }

            // Trigger (3a): Option Day posted on daily timecard row
            if (!freshOptionDayAlerts.isEmpty()) {
                JSONObject latestOpt = freshOptionDayAlerts.get(freshOptionDayAlerts.size() - 1);
                String targetWeekEnd = latestOpt.optString("week_end_date", "");
                String targetDate = latestOpt.optString("date", "");
                String title = "Option Day Used (" + targetDate + ")";

                StringBuilder body = new StringBuilder();
                for (int i = 0; i < freshOptionDayAlerts.size(); i++) {
                    if (i > 0) body.append(" | ");
                    JSONObject s = freshOptionDayAlerts.get(i);
                    String payCode = s.optString("pay_code", "OPTION DAY").trim();
                    if (payCode.isEmpty()) payCode = "OPTION DAY";
                    body.append(s.optString("date", ""))
                            .append(": ")
                            .append(payCode)
                            .append(String.format(Locale.US, " (%.2f hrs • $%.2f)",
                                    s.optDouble("total_hours", 0.0),
                                    s.optDouble("gross_pay", 0.0)));
                }
                String remainingSummary = getRemainingOptionDaySummary(afterStore);
                if (!remainingSummary.isEmpty()) {
                    body.append(" • ").append(remainingSummary);
                }
                PortalHelper.postShiftNotification(this, title, body.toString(), targetWeekEnd, targetDate);
            }

            // Trigger (3b): Option Day / Option Week entitlement balance decrease in Time Off Viewer
            notifyForOptionDayBankChanges(
                    bridgeOptionAlerts,
                    beforeStore.optJSONObject("timeoff"),
                    afterStore.optJSONObject("timeoff"),
                    !freshOptionDayAlerts.isEmpty(),
                    notifiedSet
            );

            // Trigger (2): Supervisor punch modifications detected in background
            notifyForNewAudits(beforeStore.optJSONArray("audit_logs"), afterStore.optJSONArray("audit_logs"), notifiedSet);

            prefs.edit().putStringSet(PREF_NOTIFIED_SIGNATURES, notifiedSet).apply();
        } catch (Exception ignored) {}
    }

    private void notifyForOptionDayBankChanges(
            JSONArray bridgeOptionAlerts,
            JSONObject preTimeoff,
            JSONObject postTimeoff,
            boolean alreadyNotifiedDailyRow,
            Set<String> notifiedSet
    ) {
        try {
            List<JSONObject> bankDrops = new ArrayList<>();
            if (bridgeOptionAlerts != null && bridgeOptionAlerts.length() > 0) {
                for (int i = 0; i < bridgeOptionAlerts.length(); i++) {
                    JSONObject obj = bridgeOptionAlerts.optJSONObject(i);
                    if (obj != null) bankDrops.add(obj);
                }
            } else if (preTimeoff != null && postTimeoff != null) {
                JSONArray preArr = preTimeoff.optJSONArray("entitlements");
                JSONArray postArr = postTimeoff.optJSONArray("entitlements");
                if (preArr != null && postArr != null && preArr.length() > 0) {
                    Map<String, JSONObject> preMap = new LinkedHashMap<>();
                    for (int i = 0; i < preArr.length(); i++) {
                        JSONObject e = preArr.optJSONObject(i);
                        if (e != null) {
                            preMap.put(e.optString("timeoff_type", "").trim().toUpperCase(Locale.US), e);
                        }
                    }
                    for (int j = 0; j < postArr.length(); j++) {
                        JSONObject postItem = postArr.optJSONObject(j);
                        if (postItem == null) continue;
                        String tType = postItem.optString("timeoff_type", "").trim().toUpperCase(Locale.US);
                        String title = postItem.optString("title", "").trim();
                        boolean isOpt = "OPT".equals(tType) || "OPW".equals(tType)
                                || title.toUpperCase(Locale.US).contains("OPTION");
                        JSONObject preItem = preMap.get(tType);
                        if (isOpt && preItem != null) {
                            double oldAvail = preItem.optDouble("available_time", 0.0);
                            double newAvail = postItem.optDouble("available_time", 0.0);
                            if (oldAvail > newAvail + 0.005) {
                                JSONObject drop = new JSONObject();
                                drop.put("timeoff_type", tType);
                                drop.put("title", title.isEmpty() ? "Option Day" : title);
                                drop.put("old_available", oldAvail);
                                drop.put("new_available", newAvail);
                                drop.put("time_basis", postItem.optString("time_basis", "Days"));
                                bankDrops.add(drop);
                            }
                        }
                    }
                }
            }

            for (JSONObject drop : bankDrops) {
                String tType = drop.optString("timeoff_type", "OPT");
                String title = drop.optString("title", "Option Day");
                double oldAvail = drop.optDouble("old_available", 0.0);
                double newAvail = drop.optDouble("new_available", 0.0);
                String basis = drop.optString("time_basis", "Days");
                String sig = String.format(Locale.US, "optbank|%s|%.2f->%.2f", tType, oldAvail, newAvail);
                if (notifiedSet.contains(sig)) continue;

                notifiedSet.add(sig);
                if (!alreadyNotifiedDailyRow) {
                    String notifTitle = title + " Used";
                    String notifBody = String.format(
                            Locale.US,
                            "%s balance decreased: %.2f → %.2f %s remaining",
                            title, oldAvail, newAvail, basis
                    );
                    PortalHelper.postShiftNotification(this, notifTitle, notifBody, "", "opt_" + tType);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private static String getRemainingOptionDaySummary(JSONObject store) {
        if (store == null) return "";
        try {
            JSONObject timeoff = store.optJSONObject("timeoff");
            JSONArray arr = timeoff != null ? timeoff.optJSONArray("entitlements") : null;
            if (arr == null) return "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                String tType = e.optString("timeoff_type", "").trim().toUpperCase(Locale.US);
                String title = e.optString("title", "").trim();
                if ("OPT".equals(tType) || title.toUpperCase(Locale.US).contains("OPTION DAY")) {
                    return String.format(
                            Locale.US,
                            "Remaining: %.2f %s",
                            e.optDouble("available_time", 0.0),
                            e.optString("time_basis", "Days")
                    );
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void notifyForNewAudits(JSONArray preAudits, JSONArray postAudits, Set<String> notifiedSet) {
        if (postAudits == null || postAudits.length() == 0) return;

        Set<String> existingAuditKeys = new HashSet<>();
        if (preAudits != null) {
            for (int i = 0; i < preAudits.length(); i++) {
                JSONObject a = preAudits.optJSONObject(i);
                if (a != null) existingAuditKeys.add(auditKey(a));
            }
        }

        for (int i = 0; i < postAudits.length(); i++) {
            JSONObject a = postAudits.optJSONObject(i);
            if (a == null) continue;
            String k = auditKey(a);
            if (existingAuditKeys.contains(k) || notifiedSet.contains("audit|" + k)) continue;

            notifiedSet.add("audit|" + k);
            String title = "Punch Modification Detected (" + a.optString("date", "") + ")";
            String body = a.optString("field_name", "Punch")
                    + " changed: "
                    + a.optString("old_value", "")
                    + " → "
                    + a.optString("new_value", "");
            PortalHelper.postShiftNotification(this, title, body, a.optString("week_end_date", ""), a.optString("date", ""));
            break; // Post concise alert per sync cycle
        }
    }

    private static String auditKey(JSONObject a) {
        return a.optString("week_end_date", "") + "|"
                + a.optString("date", "") + "|"
                + a.optString("field_name", "") + "|"
                + a.optString("old_value", "") + "|"
                + a.optString("new_value", "");
    }

    private static Map<String, JSONObject> indexShiftsByDate(JSONObject store) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        JSONObject details = store.optJSONObject("week_details");
        if (details == null) return map;
        JSONArray names = details.names();
        if (names == null) return map;
        for (int i = 0; i < names.length(); i++) {
            String wed = names.optString(i, "");
            JSONObject week = details.optJSONObject(wed);
            JSONArray shifts = week != null ? week.optJSONArray("shifts") : null;
            if (shifts == null) continue;
            for (int j = 0; j < shifts.length(); j++) {
                JSONObject s = shifts.optJSONObject(j);
                String d = s != null ? s.optString("date", "") : "";
                if (!d.isEmpty()) map.put(d, s);
            }
        }
        return map;
    }

    private static boolean isOptionDayShift(JSONObject shift) {
        if (shift == null) return false;
        if (shift.optBoolean("is_option_day", false)) return true;
        String raw = shift.optString("pay_code", "").trim().toUpperCase(Locale.US);
        if (raw.contains("OPTION")
                || raw.contains("OPT DAY")
                || raw.contains("OPTDAY")
                || raw.contains("OPT WK")
                || raw.contains("OPT WEEK")
                || "OPT".equals(raw)
                || "OPW".equals(raw)
                || raw.startsWith("OPT ")) {
            return true;
        }
        JSONArray details = shift.optJSONArray("details");
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONObject det = details.optJSONObject(i);
                if (det == null) continue;
                String t = (det.optString("trans_type", "") + " " + det.optString("activity", ""))
                        .trim()
                        .toUpperCase(Locale.US);
                if (t.contains("OPTION")
                        || t.contains("OPT DAY")
                        || t.contains("OPTDAY")
                        || t.contains("OPT WK")
                        || t.contains("OPT WEEK")
                        || "OPT".equals(t)
                        || "OPW".equals(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isShiftComplete(JSONObject shift) {
        if (shift == null) return false;
        return shift.optDouble("total_hours", 0.0) > 0.005
                && !shift.optString("end_time_clock", "").isEmpty();
    }

    private static boolean isTodayShiftCompletedInStore(String storeJson) {
        if (storeJson == null || storeJson.trim().isEmpty()) return false;
        try {
            String todayIso = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            Map<String, JSONObject> shifts = indexShiftsByDate(new JSONObject(storeJson));
            JSONObject todayShift = shifts.get(todayIso);
            if (todayShift == null) return false;
            String payCode = todayShift.optString("pay_code", "").trim().toUpperCase(Locale.US);
            return isShiftComplete(todayShift)
                    || isOptionDayShift(todayShift)
                    || payCode.contains("SCHEDULED OFF");
        } catch (Exception ignored) {}
        return false;
    }

    /** Returns true when an active clock-in is open or current time is within the learned shift-end window. */
    private static boolean isPrimePostShiftWindow(String storeJson) {
        try {
            if (hasOpenClockInShift(storeJson)) return true;
            Calendar cal = Calendar.getInstance();
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            if (dayOfWeek == Calendar.SUNDAY) return false;

            double nowHourDec = cal.get(Calendar.HOUR_OF_DAY) + (cal.get(Calendar.MINUTE) / 60.0);
            double typicalEnd = estimateTypicalEndHourDec(storeJson);
            return nowHourDec >= (typicalEnd - 1.5) && nowHourDec <= (typicalEnd + 4.0);
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true during 1:00 AM - 5:00 AM local time when no open punch exists. */
    private static boolean isOvernightQuietWindow(String storeJson) {
        try {
            if (hasOpenClockInShift(storeJson)) return false;
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            return hour >= 1 && hour < 5;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasOpenClockInShift(String storeJson) {
        if (storeJson == null || storeJson.trim().isEmpty()) return false;
        try {
            String todayIso = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            Map<String, JSONObject> shifts = indexShiftsByDate(new JSONObject(storeJson));
            JSONObject today = shifts.get(todayIso);
            if (today != null) {
                boolean hasStart = !today.isNull("start_time_dec") && !today.optString("start_time_clock", "").isEmpty();
                if (hasStart && !isShiftComplete(today)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static double estimateTypicalEndHourDec(String storeJson) {
        if (storeJson == null || storeJson.trim().isEmpty()) return 19.5;
        try {
            Map<String, JSONObject> shifts = indexShiftsByDate(new JSONObject(storeJson));
            double sum = 0.0;
            int count = 0;
            for (JSONObject s : shifts.values()) {
                if (s != null && isShiftComplete(s) && !s.isNull("end_time_dec")) {
                    double endDec = s.optDouble("end_time_dec", -1.0);
                    if (endDec > 0.0 && endDec <= 24.0) {
                        sum += endDec;
                        count++;
                        if (count >= 12) break;
                    }
                }
            }
            if (count > 0) {
                return sum / count;
            }
        } catch (Exception ignored) {}
        return 19.5;
    }

    private static boolean hasTimeOffEntitlementsInStore(String storeJson) {
        if (storeJson == null || storeJson.trim().isEmpty()) return false;
        try {
            JSONObject timeoff = new JSONObject(storeJson).optJSONObject("timeoff");
            JSONArray arr = timeoff != null ? timeoff.optJSONArray("entitlements") : null;
            return arr != null && arr.length() > 0;
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasWeekInStore(String storeJson, String weekEndDate) {
        if (storeJson == null || storeJson.trim().isEmpty() || weekEndDate == null || weekEndDate.isEmpty()) {
            return false;
        }
        try {
            JSONObject details = new JSONObject(storeJson).optJSONObject("week_details");
            return details != null && details.has(weekEndDate);
        } catch (Exception ignored) {}
        return false;
    }

    private static String sha256Hex(String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static List<String> jsonArrayToList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String quoteForJs(String s) {
        String quoted = JSONObject.quote(s != null ? s : "");
        return quoted.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private String unquoteJsString(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        try {
            Object v = new JSONTokener(raw).nextValue();
            return v instanceof String ? (String) v : String.valueOf(v);
        } catch (Exception e) {
            return raw;
        }
    }

    private void cancelWatchdog() {
        if (watchdogRunnable != null) {
            mainHandler.removeCallbacks(watchdogRunnable);
            watchdogRunnable = null;
        }
    }

    private void finishJobCleanly(final JobParameters params, final boolean reschedule) {
        if (isFinished) return;
        isFinished = true;
        cancelWatchdog();
        mainHandler.post(() -> {
            cleanupWebViews();
            try {
                jobFinished(params, reschedule);
            } catch (Exception ignored) {}
            schedule(TimecardSyncJobService.this);
        });
    }

    private void cleanupWebViews() {
        headlessWebView = destroyWebView(headlessWebView);
        bridgeWebView = destroyWebView(bridgeWebView);
    }

    private static WebView destroyWebView(WebView wv) {
        if (wv == null) return null;
        try {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.clearHistory();
            wv.removeAllViews();
            wv.destroy();
        } catch (Exception ignored) {}
        return null;
    }

    /** Minimal synchronous AndroidNative bridge exposed strictly to offline __bg_bridge.html. */
    private final class BgNativeBridge {
        @JavascriptInterface
        public String loadStateJson() {
            return PortalHelper.readFileString(TimecardSyncJobService.this, PortalHelper.STORE_FILENAME);
        }

        @JavascriptInterface
        public void saveStateJson(String json) {
            PortalHelper.writeTextFileAtomic(TimecardSyncJobService.this, PortalHelper.STORE_FILENAME, json);
        }

        @JavascriptInterface
        public String loadArchiveJson() {
            return PortalHelper.readFileString(TimecardSyncJobService.this, PortalHelper.ARCHIVE_FILENAME);
        }

        @JavascriptInterface
        public void saveArchiveJson(String json) {
            PortalHelper.mergeAndSaveArchiveJsonSync(TimecardSyncJobService.this, json);
        }

        @JavascriptInterface
        public long getStoreLastModified() {
            return PortalHelper.getStoreLastModified(TimecardSyncJobService.this);
        }

        @JavascriptInterface
        public boolean isLoggedIn() {
            return true;
        }
    }
}
