package com.agy.accessibility;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.agy.gateway.handlers.NotificationHandler;
import com.agy.util.AtomicFileWriter;
import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persistent NotificationListenerService allowing the AI agent to:
 * 1) Read active device notifications, trigger actions, dismiss clutter, and send inline
 *    {@link RemoteInput} replies across apps.
 * 2) Maintain a persistent cross-session Notification History ledger ({@code notification_history.jsonl})
 *    so swiped-away emails, chats, and texts remain searchable for daily briefings.
 * 3) Evaluate VIP Wakeup Rules with a strict Interruption Budget & Quiet Hours so urgent
 *    messages can wake {@code agy-daemon} / AI cascades automatically without noise.
 */
public final class AgyNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "AgyNotifListener";
    private static final int MAX_RING_HISTORY = 300;
    private static final long DEDUP_WINDOW_MS = 45_000L;
    private static final long MAX_HISTORY_BYTES = 512L * 1024L;

    private static volatile AgyNotificationListenerService instance;

    private static final Object HISTORY_LOCK = new Object();
    private static final Deque<JSONObject> memoryHistory = new ArrayDeque<>();
    private static final Map<String, Long> recentFingerprints = new HashMap<>();
    private static final Map<String, Long> ruleLastFiredAt = new HashMap<>();
    private static boolean historyHydrated = false;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    public static AgyNotificationListenerService getInstance() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean isEnabledInSettings(Context context) {
        if (context == null) {
            return false;
        }
        try {
            String flat = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) {
                return false;
            }
            ComponentName cn = new ComponentName(context, AgyNotificationListenerService.class);
            return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        hydrateHistoryOnce(getApplicationContext());
        Log.i(TAG, "AgyNotificationListenerService connected");
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "AgyNotificationListenerService disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        bgExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        final Context appCtx = getApplicationContext();
        final String pkg = sbn.getPackageName() != null ? sbn.getPackageName() : "";
        if (pkg.isEmpty() || pkg.equals(appCtx.getPackageName()) || "com.android.systemui".equals(pkg)) {
            return;
        }
        if (sbn.isOngoing()) {
            return;
        }
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        Bundle extras = n.extras;
        final String title = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null);
        final String text = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null);
        final String bigText = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null);
        final String subText = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_SUB_TEXT) : null);
        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) {
            return;
        }

        final long now = System.currentTimeMillis();
        final String fp = pkg + "|" + title + "|" + (bigText.isEmpty() ? text : bigText);
        synchronized (HISTORY_LOCK) {
            Long prev = recentFingerprints.get(fp);
            if (prev != null && (now - prev) < DEDUP_WINDOW_MS) {
                return;
            }
            recentFingerprints.put(fp, now);
            if (recentFingerprints.size() > 500) {
                recentFingerprints.clear();
            }
        }

        boolean hasReply = findReplyAction(n, -1) != null;
        final JSONObject entry = new JSONObject();
        try {
            entry.put("ts", now);
            entry.put("key", sbn.getKey());
            entry.put("package", pkg);
            if (!title.isEmpty()) entry.put("title", title);
            if (!text.isEmpty()) entry.put("text", text);
            if (!bigText.isEmpty() && !bigText.equals(text)) entry.put("big_text", bigText);
            if (!subText.isEmpty()) entry.put("sub_text", subText);
            entry.put("has_reply_input", hasReply);
        } catch (Exception ignored) {
        }

        synchronized (HISTORY_LOCK) {
            hydrateHistoryOnce(appCtx);
            memoryHistory.addFirst(entry);
            while (memoryHistory.size() > MAX_RING_HISTORY) {
                memoryHistory.removeLast();
            }
        }

        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                appendHistoryToDisk(appCtx, entry);
                evaluateVipRules(appCtx, entry);
            }
        });
    }

    public static JSONObject queryHistory(Context context,
                                          String packageFilter,
                                          String query,
                                          long sinceMs,
                                          int limit) {
        JSONObject out = new JSONObject();
        try {
            Context appCtx = context.getApplicationContext();
            hydrateHistoryOnce(appCtx);
            int max = Math.max(1, Math.min(limit > 0 ? limit : 50, MAX_RING_HISTORY));
            String pkgLow = packageFilter != null ? packageFilter.trim().toLowerCase(Locale.US) : "";
            String qLow = query != null ? query.trim().toLowerCase(Locale.US) : "";

            JSONArray arr = new JSONArray();
            synchronized (HISTORY_LOCK) {
                for (JSONObject item : memoryHistory) {
                    long ts = item.optLong("ts", 0L);
                    if (sinceMs > 0L && ts < sinceMs) {
                        continue;
                    }
                    String p = item.optString("package", "").toLowerCase(Locale.US);
                    if (!pkgLow.isEmpty() && !p.contains(pkgLow)) {
                        continue;
                    }
                    if (!qLow.isEmpty()) {
                        String blob = (item.optString("title", "") + " "
                                + item.optString("text", "") + " "
                                + item.optString("big_text", "") + " " + p).toLowerCase(Locale.US);
                        if (!blob.contains(qLow)) {
                            continue;
                        }
                    }
                    arr.put(item);
                    if (arr.length() >= max) {
                        break;
                    }
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("history", arr);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static synchronized JSONObject manageVipRules(Context context, JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            Context appCtx = context.getApplicationContext();
            String sub = payload.optString("rule_action", payload.optString("sub_action", "list")).trim().toLowerCase(Locale.US);
            JSONObject cfg = loadVipConfig(appCtx);
            JSONArray rules = cfg.optJSONArray("rules");
            if (rules == null) {
                rules = new JSONArray();
                cfg.put("rules", rules);
            }

            if ("list".equals(sub)) {
                out.put("ok", true);
                out.put("quiet_hours_start", cfg.optInt("quiet_hours_start", 22));
                out.put("quiet_hours_end", cfg.optInt("quiet_hours_end", 6));
                out.put("rules", rules);
                return out;
            }

            if ("add".equals(sub)) {
                String id = payload.optString("rule_id", "vip_" + (System.currentTimeMillis() % 100000L)).trim();
                JSONObject rule = new JSONObject();
                rule.put("id", id);
                rule.put("enabled", payload.optBoolean("enabled", true));
                rule.put("name", payload.optString("name", id));
                rule.put("package_contains", payload.optString("package_contains", ""));
                rule.put("sender_contains", payload.optString("sender_contains", ""));
                rule.put("text_contains", payload.optString("text_contains", ""));
                rule.put("cooldown_sec", Math.max(30, payload.optInt("cooldown_sec", 300)));
                rule.put("override_quiet_hours", payload.optBoolean("override_quiet_hours", false));
                rule.put("wake_ai", payload.optBoolean("wake_ai", false));
                rule.put("prompt", payload.optString("prompt", ""));

                JSONArray next = new JSONArray();
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject existing = rules.optJSONObject(i);
                    if (existing != null && !id.equals(existing.optString("id"))) {
                        next.put(existing);
                    }
                }
                next.put(rule);
                cfg.put("rules", next);
                saveVipConfig(appCtx, cfg);
                out.put("ok", true);
                out.put("rule", rule);
                return out;
            }

            if ("remove".equals(sub)) {
                String id = payload.optString("rule_id", "").trim();
                JSONArray next = new JSONArray();
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject existing = rules.optJSONObject(i);
                    if (existing != null && !id.equals(existing.optString("id"))) {
                        next.put(existing);
                    }
                }
                cfg.put("rules", next);
                saveVipConfig(appCtx, cfg);
                out.put("ok", true);
                out.put("removed", id);
                return out;
            }

            if ("set_quiet_hours".equals(sub)) {
                cfg.put("quiet_hours_start", payload.optInt("quiet_hours_start", 22));
                cfg.put("quiet_hours_end", payload.optInt("quiet_hours_end", 6));
                saveVipConfig(appCtx, cfg);
                out.put("ok", true);
                out.put("quiet_hours_start", cfg.optInt("quiet_hours_start"));
                out.put("quiet_hours_end", cfg.optInt("quiet_hours_end"));
                return out;
            }

            out.put("ok", false);
            out.put("error", "unsupported rule_action: " + sub);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void evaluateVipRules(Context appCtx, JSONObject entry) {
        try {
            JSONObject cfg = loadVipConfig(appCtx);
            JSONArray rules = cfg.optJSONArray("rules");
            if (rules == null || rules.length() == 0) {
                return;
            }
            int qStart = cfg.optInt("quiet_hours_start", 22);
            int qEnd = cfg.optInt("quiet_hours_end", 6);
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean inQuietHours = (qStart > qEnd)
                    ? (hour >= qStart || hour < qEnd)
                    : (hour >= qStart && hour < qEnd);

            String pkg = entry.optString("package", "").toLowerCase(Locale.US);
            String title = entry.optString("title", "").toLowerCase(Locale.US);
            String body = (entry.optString("big_text", "").isEmpty()
                    ? entry.optString("text", "")
                    : entry.optString("big_text", "")).toLowerCase(Locale.US);
            long now = System.currentTimeMillis();

            for (int i = 0; i < rules.length(); i++) {
                JSONObject r = rules.optJSONObject(i);
                if (r == null || !r.optBoolean("enabled", true)) continue;
                if (inQuietHours && !r.optBoolean("override_quiet_hours", false)) continue;

                String pMatch = r.optString("package_contains", "").trim().toLowerCase(Locale.US);
                String sMatch = r.optString("sender_contains", "").trim().toLowerCase(Locale.US);
                String tMatch = r.optString("text_contains", "").trim().toLowerCase(Locale.US);
                if (pMatch.isEmpty() && sMatch.isEmpty() && tMatch.isEmpty()) continue;

                if (!pMatch.isEmpty() && !pkg.contains(pMatch)) continue;
                if (!sMatch.isEmpty() && !title.contains(sMatch)) continue;
                if (!tMatch.isEmpty() && !body.contains(tMatch) && !title.contains(tMatch)) continue;

                String ruleId = r.optString("id", "vip_" + i);
                long cooldownMs = Math.max(30L, r.optLong("cooldown_sec", 300L)) * 1000L;
                synchronized (HISTORY_LOCK) {
                    Long last = ruleLastFiredAt.get(ruleId);
                    if (last != null && (now - last) < cooldownMs) {
                        continue;
                    }
                    ruleLastFiredAt.put(ruleId, now);
                }

                String ruleName = r.optString("name", ruleId);
                Log.i(TAG, "VIP rule matched: " + ruleName + " on " + pkg);

                if (r.optBoolean("wake_ai", false)) {
                    dispatchVipAiWakeup(appCtx, r, entry);
                } else {
                    JSONObject alert = new JSONObject();
                    alert.put("channel", "agy_alerts");
                    alert.put("tag", "vip_" + ruleId);
                    alert.put("title", "VIP Alert: " + entry.optString("title", ruleName));
                    alert.put("message", entry.optString("text", ""));
                    new NotificationHandler(appCtx).handle(alert);
                }
                break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "evaluateVipRules error: " + t.getMessage());
        }
    }

    private void dispatchVipAiWakeup(Context appCtx, JSONObject rule, JSONObject entry) {
        try {
            File filesDir = appCtx.getFilesDir();
            File prefix = new File(filesDir, "usr");
            File pythonBin = new File(prefix, "bin/python3");
            File daemonScript = new File(prefix, "bin/agy-daemon");
            if (!pythonBin.canExecute() || !daemonScript.isFile()) {
                return;
            }
            String basePrompt = rule.optString("prompt",
                    "A VIP notification arrived. Triage urgency, check related calendar/memory context, and post an actionable summary notification via android_notify.");
            String fullPrompt = basePrompt
                    + "\n\nIncoming VIP Notification JSON:\n"
                    + entry.toString();

            ProcessBuilder pb = new ProcessBuilder(
                    pythonBin.getAbsolutePath(),
                    daemonScript.getAbsolutePath(),
                    "dispatch-prompt",
                    "--name", "VIP: " + rule.optString("name", "Alert"),
                    "--prompt", fullPrompt);
            pb.directory(filesDir);
            pb.environment().put("HOME", filesDir.getAbsolutePath());
            pb.environment().put("PREFIX", prefix.getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
            pb.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
            pb.environment().put("SSL_CERT_FILE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
            Process p = pb.start();
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchVipAiWakeup failed: " + t.getMessage());
        }
    }

    private static File historyFile(Context appCtx) {
        File dir = new File(appCtx.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "notification_history.jsonl");
    }

    private static File vipConfigFile(Context appCtx) {
        File dir = new File(appCtx.getFilesDir(), ".gemini/memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "vip_rules.json");
    }

    private static JSONObject loadVipConfig(Context appCtx) {
        File f = vipConfigFile(appCtx);
        if (!f.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            String raw = IoUtil.readAll(in, "UTF-8").trim();
            return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void saveVipConfig(Context appCtx, JSONObject cfg) {
        try {
            AtomicFileWriter.writeText(vipConfigFile(appCtx), cfg.toString(2) + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static void hydrateHistoryOnce(Context appCtx) {
        synchronized (HISTORY_LOCK) {
            if (historyHydrated) return;
            historyHydrated = true;
            File f = historyFile(appCtx);
            if (!f.isFile()) return;
            List<JSONObject> loaded = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        loaded.add(new JSONObject(line));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            int start = Math.max(0, loaded.size() - MAX_RING_HISTORY);
            for (int i = start; i < loaded.size(); i++) {
                memoryHistory.addFirst(loaded.get(i));
            }
        }
    }

    private static synchronized void appendHistoryToDisk(Context appCtx, JSONObject entry) {
        File f = historyFile(appCtx);
        try {
            if (f.isFile() && f.length() > MAX_HISTORY_BYTES) {
                StringBuilder compacted = new StringBuilder();
                synchronized (HISTORY_LOCK) {
                    List<JSONObject> snap = new ArrayList<>(memoryHistory);
                    for (int i = snap.size() - 1; i >= 0; i--) {
                        compacted.append(snap.get(i).toString()).append('\n');
                    }
                }
                AtomicFileWriter.writeText(f, compacted.toString());
                return;
            }
            byte[] line = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(line);
            }
        } catch (Throwable ignored) {
        }
    }

    public JSONObject listActive(String packageFilter, boolean includeOngoing, int limit) {
        JSONObject out = new JSONObject();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            JSONArray items = new JSONArray();
            int max = limit > 0 ? Math.min(limit, 200) : 50;
            String filterLower = packageFilter != null ? packageFilter.trim().toLowerCase(Locale.US) : "";

            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn == null) continue;
                    if (!includeOngoing && sbn.isOngoing()) continue;
                    String pkg = sbn.getPackageName() != null ? sbn.getPackageName() : "";
                    if (!filterLower.isEmpty() && !pkg.toLowerCase(Locale.US).contains(filterLower)) {
                        continue;
                    }
                    Notification n = sbn.getNotification();
                    if (n == null) continue;
                    Bundle extras = n.extras;
                    String title = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null);
                    String text = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null);
                    String bigText = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null);
                    String subText = charSeq(extras != null ? extras.getCharSequence(Notification.EXTRA_SUB_TEXT) : null);

                    JSONObject item = new JSONObject();
                    item.put("key", sbn.getKey());
                    item.put("package", pkg);
                    item.put("id", sbn.getId());
                    if (sbn.getTag() != null) item.put("tag", sbn.getTag());
                    item.put("post_time", sbn.getPostTime());
                    item.put("ongoing", sbn.isOngoing());
                    item.put("clearable", sbn.isClearable());
                    if (!title.isEmpty()) item.put("title", title);
                    if (!text.isEmpty()) item.put("text", text);
                    if (!bigText.isEmpty() && !bigText.equals(text)) item.put("big_text", bigText);
                    if (!subText.isEmpty()) item.put("sub_text", subText);

                    JSONArray actionsJson = new JSONArray();
                    if (n.actions != null) {
                        for (int i = 0; i < n.actions.length; i++) {
                            Notification.Action act = n.actions[i];
                            if (act == null) continue;
                            JSONObject aj = new JSONObject();
                            aj.put("index", i);
                            aj.put("title", act.title != null ? act.title.toString() : "");
                            RemoteInput[] ris = act.getRemoteInputs();
                            aj.put("has_reply_input", ris != null && ris.length > 0);
                            actionsJson.put(aj);
                        }
                    }
                    if (actionsJson.length() > 0) {
                        item.put("actions", actionsJson);
                    }
                    items.put(item);
                    if (items.length() >= max) break;
                }
            }
            out.put("ok", true);
            out.put("via", "notification_listener");
            out.put("count", items.length());
            out.put("notifications", items);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public JSONObject actOnNotification(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            String action = payload.optString("action", "").trim().toLowerCase(Locale.US);
            if ("dismiss_all".equals(action)) {
                cancelAllNotifications();
                out.put("ok", true);
                out.put("action", "dismiss_all");
                return out;
            }

            String key = payload.optString("key", "").trim();
            if (key.isEmpty()) {
                out.put("ok", false);
                out.put("error", "notification key is required");
                return out;
            }

            if ("dismiss".equals(action) || "cancel".equals(action)) {
                cancelNotification(key);
                out.put("ok", true);
                out.put("action", "dismiss");
                out.put("key", key);
                return out;
            }

            StatusBarNotification target = findByKey(key);
            if (target == null || target.getNotification() == null) {
                out.put("ok", false);
                out.put("error", "active notification not found for key: " + key);
                return out;
            }

            Notification n = target.getNotification();
            if ("open".equals(action) || "click".equals(action)) {
                if (n.contentIntent == null) {
                    out.put("ok", false);
                    out.put("error", "notification has no contentIntent");
                    return out;
                }
                n.contentIntent.send();
                out.put("ok", true);
                out.put("action", "open");
                out.put("key", key);
                return out;
            }

            if ("trigger_action".equals(action) || "button".equals(action)) {
                int idx = payload.optInt("action_index", 0);
                if (n.actions == null || idx < 0 || idx >= n.actions.length || n.actions[idx] == null) {
                    out.put("ok", false);
                    out.put("error", "invalid action_index: " + idx);
                    return out;
                }
                PendingIntent pi = n.actions[idx].actionIntent;
                if (pi == null) {
                    out.put("ok", false);
                    out.put("error", "notification action has no PendingIntent");
                    return out;
                }
                pi.send();
                out.put("ok", true);
                out.put("action", "trigger_action");
                out.put("action_index", idx);
                return out;
            }

            if ("reply".equals(action)) {
                String replyText = payload.optString("reply_text", payload.optString("text", ""));
                if (replyText.isEmpty()) {
                    out.put("ok", false);
                    out.put("error", "reply_text is required for reply");
                    return out;
                }
                Notification.Action replyAct = findReplyAction(n, payload.optInt("action_index", -1));
                if (replyAct == null || replyAct.actionIntent == null || replyAct.getRemoteInputs() == null) {
                    out.put("ok", false);
                    out.put("error", "notification has no inline RemoteInput reply action");
                    return out;
                }
                Intent fillIn = new Intent();
                Bundle results = new Bundle();
                for (RemoteInput ri : replyAct.getRemoteInputs()) {
                    if (ri != null && ri.getResultKey() != null) {
                        results.putCharSequence(ri.getResultKey(), replyText);
                    }
                }
                RemoteInput.addResultsToIntent(replyAct.getRemoteInputs(), fillIn, results);
                replyAct.actionIntent.send(getApplicationContext(), 0, fillIn);
                out.put("ok", true);
                out.put("action", "reply");
                out.put("key", key);
                return out;
            }

            out.put("ok", false);
            out.put("error", "unsupported notification action: " + action);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", t.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private StatusBarNotification findByKey(String key) {
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            if (sbn != null && key.equals(sbn.getKey())) {
                return sbn;
            }
        }
        return null;
    }

    private static Notification.Action findReplyAction(Notification n, int explicitIdx) {
        if (n.actions == null) return null;
        if (explicitIdx >= 0 && explicitIdx < n.actions.length) {
            return n.actions[explicitIdx];
        }
        for (Notification.Action act : n.actions) {
            if (act != null && act.getRemoteInputs() != null && act.getRemoteInputs().length > 0) {
                return act;
            }
        }
        return null;
    }

    private static String charSeq(CharSequence cs) {
        return cs != null ? cs.toString().trim() : "";
    }
}
