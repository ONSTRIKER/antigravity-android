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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Persistent NotificationListenerService allowing the AI agent to read active
 * device notifications, trigger notification actions, dismiss clutter, and send
 * inline {@link RemoteInput} replies across apps.
 */
public final class AgyNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "AgyNotifListener";
    private static volatile AgyNotificationListenerService instance;

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
        super.onDestroy();
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
