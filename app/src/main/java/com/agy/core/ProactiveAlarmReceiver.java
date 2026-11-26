package com.agy.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.agy.gateway.handlers.NotificationHandler;
import com.agy.util.AtomicFileWriter;
import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Hardware-exact wakeup receiver backed by {@link AlarmManager#setExactAndAllowWhileIdle}.
 * Wakes {@link CoreServerService} and dispatches scheduled {@code agy-daemon} tasks or
 * interactive briefing notifications at exact wall-clock times even under Android Doze.
 */
public final class ProactiveAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AgyProactiveAlarm";
    public static final String ACTION_PROACTIVE_ALARM = "com.agy.ACTION_PROACTIVE_ALARM";
    private static final String EXTRA_ALARM_ID = "alarm_id";
    private static final String EXTRA_TASK_ID = "task_id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_DAILY = "repeat_daily";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String alarmId = intent.getStringExtra(EXTRA_ALARM_ID);
        final String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        final String message = intent.getStringExtra(EXTRA_MESSAGE);
        final boolean repeatDaily = intent.getBooleanExtra(EXTRA_DAILY, false);

        Log.i(TAG, "Exact proactive alarm fired: id=" + alarmId + " task=" + taskId);

        final PendingResult pending = goAsync();
        PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm != null
                ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agy:proactive_alarm")
                : null;
        if (wl != null) {
            wl.acquire(60_000L);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        CoreServiceLauncher.start(appCtx);
                    } catch (Throwable ignored) {
                    }

                    if (repeatDaily && alarmId != null && !alarmId.isEmpty()) {
                        long nextTrigger = System.currentTimeMillis() + 86_400_000L;
                        scheduleExact(appCtx, alarmId, nextTrigger, taskId, title, message, true);
                    } else if (alarmId != null && !alarmId.isEmpty()) {
                        removePersistedEntry(appCtx, alarmId);
                    }

                    if (taskId != null && !taskId.trim().isEmpty()) {
                        // Allow core a brief moment if waking from cold state
                        Thread.sleep(1500L);
                        File filesDir = appCtx.getFilesDir();
                        File prefix = new File(filesDir, "usr");
                        File pythonBin = new File(prefix, "bin/python3");
                        File daemonScript = new File(prefix, "bin/agy-daemon");
                        if (pythonBin.canExecute() && daemonScript.isFile()) {
                            ProcessBuilder pb = new ProcessBuilder(
                                    pythonBin.getAbsolutePath(),
                                    daemonScript.getAbsolutePath(),
                                    "task", "run-now", taskId.trim());
                            pb.directory(filesDir);
                            pb.environment().put("HOME", filesDir.getAbsolutePath());
                            pb.environment().put("PREFIX", prefix.getAbsolutePath());
                            pb.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
                            pb.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
                            pb.environment().put("SSL_CERT_FILE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
                            Process p = pb.start();
                            p.waitFor(25, TimeUnit.SECONDS);
                        }
                    } else if ((title != null && !title.isEmpty()) || (message != null && !message.isEmpty())) {
                        JSONObject payload = new JSONObject();
                        payload.put("channel", "agy_alerts");
                        payload.put("tag", "alarm_" + (alarmId != null ? alarmId : "proactive"));
                        payload.put("title", title != null && !title.isEmpty() ? title : "Antigravity Reminder");
                        payload.put("message", message != null ? message : "");
                        new NotificationHandler(appCtx).handle(payload);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error handling proactive alarm: " + t.getMessage());
                } finally {
                    if (wl != null && wl.isHeld()) {
                        try {
                            wl.release();
                        } catch (Throwable ignored) {
                        }
                    }
                    pending.finish();
                }
            }
        }, "AgyAlarmWorker").start();
    }

    public static synchronized JSONObject scheduleExact(Context context,
                                                        String alarmId,
                                                        long triggerAtMillis,
                                                        String taskId,
                                                        String title,
                                                        String message,
                                                        boolean repeatDaily) {
        JSONObject out = new JSONObject();
        try {
            Context appCtx = context.getApplicationContext();
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                out.put("ok", false);
                out.put("error", "AlarmManager unavailable");
                return out;
            }
            String id = (alarmId == null || alarmId.trim().isEmpty())
                    ? "alarm_" + (System.currentTimeMillis() % 1_000_000L)
                    : alarmId.trim();

            PendingIntent pi = buildPendingIntent(appCtx, id, taskId, title, message, repeatDaily, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }

            JSONObject store = loadStore(appCtx);
            JSONObject entry = new JSONObject();
            entry.put("id", id);
            entry.put("trigger_at_ms", triggerAtMillis);
            entry.put("task_id", taskId != null ? taskId : "");
            entry.put("title", title != null ? title : "");
            entry.put("message", message != null ? message : "");
            entry.put("repeat_daily", repeatDaily);
            store.put(id, entry);
            saveStore(appCtx, store);

            out.put("ok", true);
            out.put("alarm", entry);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized JSONObject cancelExact(Context context, String alarmId) {
        JSONObject out = new JSONObject();
        try {
            Context appCtx = context.getApplicationContext();
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = buildPendingIntent(appCtx, alarmId, "", "", "", false, false);
            if (am != null && pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
            removePersistedEntry(appCtx, alarmId);
            out.put("ok", true);
            out.put("cancelled", alarmId);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized JSONObject listExact(Context context) {
        JSONObject out = new JSONObject();
        try {
            JSONObject store = loadStore(context.getApplicationContext());
            JSONArray arr = new JSONArray();
            Iterator<String> keys = store.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject item = store.optJSONObject(k);
                if (item != null) {
                    arr.put(item);
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("alarms", arr);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized void restorePersistedAlarms(Context context) {
        Context appCtx = context.getApplicationContext();
        JSONObject store = loadStore(appCtx);
        AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long now = System.currentTimeMillis();
        boolean mutated = false;
        Iterator<String> it = store.keys();
        java.util.List<String> expired = new java.util.ArrayList<>();

        while (it.hasNext()) {
            String id = it.next();
            JSONObject entry = store.optJSONObject(id);
            if (entry == null) continue;
            long triggerAt = entry.optLong("trigger_at_ms", 0L);
            boolean repeatDaily = entry.optBoolean("repeat_daily", false);
            while (repeatDaily && triggerAt > 0L && triggerAt <= now) {
                triggerAt += 86_400_000L;
                try {
                    entry.put("trigger_at_ms", triggerAt);
                } catch (Exception ignored) {
                }
                mutated = true;
            }
            if (triggerAt <= now && !repeatDaily) {
                expired.add(id);
                mutated = true;
                continue;
            }
            PendingIntent pi = buildPendingIntent(
                    appCtx,
                    id,
                    entry.optString("task_id", ""),
                    entry.optString("title", ""),
                    entry.optString("message", ""),
                    repeatDaily,
                    true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
        for (String exp : expired) {
            store.remove(exp);
        }
        if (mutated) {
            saveStore(appCtx, store);
        }
    }

    private static PendingIntent buildPendingIntent(Context appCtx,
                                                    String alarmId,
                                                    String taskId,
                                                    String title,
                                                    String message,
                                                    boolean repeatDaily,
                                                    boolean create) {
        Intent i = new Intent(appCtx, ProactiveAlarmReceiver.class);
        i.setAction(ACTION_PROACTIVE_ALARM);
        i.putExtra(EXTRA_ALARM_ID, alarmId);
        i.putExtra(EXTRA_TASK_ID, taskId != null ? taskId : "");
        i.putExtra(EXTRA_TITLE, title != null ? title : "");
        i.putExtra(EXTRA_MESSAGE, message != null ? message : "");
        i.putExtra(EXTRA_DAILY, repeatDaily);
        int reqCode = (alarmId.hashCode() & 0x7fffffff);
        int flags = create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(appCtx, reqCode, i, flags);
    }

    private static File storeFile(Context appCtx) {
        File dir = new File(appCtx.getFilesDir(), ".gemini/memory");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "exact_alarms.json");
    }

    private static JSONObject loadStore(Context appCtx) {
        File f = storeFile(appCtx);
        if (!f.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            String raw = IoUtil.readAll(in, "UTF-8").trim();
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void saveStore(Context appCtx, JSONObject store) {
        try {
            AtomicFileWriter.writeText(storeFile(appCtx), store.toString(2) + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void removePersistedEntry(Context appCtx, String alarmId) {
        JSONObject store = loadStore(appCtx);
        if (store.has(alarmId)) {
            store.remove(alarmId);
            saveStore(appCtx, store);
        }
    }
}
