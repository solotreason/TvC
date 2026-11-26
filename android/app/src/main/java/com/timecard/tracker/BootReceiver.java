package com.timecard.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reschedules closed-app background timecard checks whenever the phone reboots
 * or the application is updated.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                Context appCtx = context.getApplicationContext();
                TimecardSyncJobService.schedule(appCtx != null ? appCtx : context);
            } catch (Exception ignored) {}
        }
    }
}


