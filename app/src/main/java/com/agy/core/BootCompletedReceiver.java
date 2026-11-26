package com.agy.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts {@link CoreServerService} and restores exact proactive alarms automatically
 * after device boot or in-place APK self-upgrades so scheduled briefings and
 * background watchers stay active 24/7 without manual launch.
 */
public final class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "AgyBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "Received " + action + " -> starting CoreServerService & restoring exact alarms");
        Context appCtx = context.getApplicationContext();
        try {
            CoreServiceLauncher.start(appCtx);
        } catch (Throwable t) {
            Log.w(TAG, "CoreServiceLauncher.start on boot failed: " + t.getMessage());
        }
        try {
            ProactiveAlarmReceiver.restorePersistedAlarms(appCtx);
        } catch (Throwable t) {
            Log.w(TAG, "restorePersistedAlarms failed: " + t.getMessage());
        }
    }
}
