package com.agy.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent native Android Accessibility Service powering cross-app UI inspection,
 * semantic node actions, touch/swipe gestures, global navigation, and fast API 30+
 * screenshots without requiring ADB or Shizuku across reboots.
 */
public final class AgyAccessibilityService extends AccessibilityService {

    private static final String TAG = "AgyAccessibility";
    private static final int MAX_NODES_DEFAULT = 350;
    private static final int MAX_DEPTH_DEFAULT = 28;

    private static volatile AgyAccessibilityService instance;
    private static volatile String lastActivePackage = "";
    private static volatile String lastActiveClass = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static AgyAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static String getLastActivePackage() {
        return lastActivePackage;
    }

    public static String getLastActiveClass() {
        return lastActiveClass;
    }

    public static boolean isEnabledInSettings(Context context) {
        if (context == null) {
            return false;
        }
        try {
            int enabled = Settings.Secure.getInt(
                    context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) {
                return false;
            }
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue == null || settingValue.isEmpty()) {
                return false;
            }
            ComponentName expected = new ComponentName(context, AgyAccessibilityService.class);
            String flatFull = expected.flattenToString();
            String flatShort = expected.flattenToShortString();
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String item = splitter.next();
                if (flatFull.equalsIgnoreCase(item) || flatShort.equalsIgnoreCase(item)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "AgyAccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null && pkg.length() > 0) {
                lastActivePackage = pkg.toString();
            }
            if (cls != null && cls.length() > 0) {
                lastActiveClass = cls.toString();
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "AgyAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    /** Captures a live semantic UI tree across interactive windows (or active window). */
    public JSONObject dumpUiTree(boolean interactiveOnly, int maxNodes, boolean allWindows) {
        JSONObject out = new JSONObject();
        int cap = maxNodes > 0 ? Math.min(maxNodes, 1500) : MAX_NODES_DEFAULT;
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        JSONArray windowsMeta = new JSONArray();
        try {
            if (allWindows && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        if (w == null) continue;
                        JSONObject wm = new JSONObject();
                        wm.put("id", w.getId());
                        wm.put("type", w.getType());
                        wm.put("layer", w.getLayer());
                        wm.put("active", w.isActive());
                        wm.put("focused", w.isFocused());
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && w.getTitle() != null) {
                            wm.put("title", w.getTitle().toString());
                        }
                        Rect wb = new Rect();
                        w.getBoundsInScreen(wb);
                        wm.put("bounds", rectToJson(wb));
                        windowsMeta.put(wm);
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) {
                            roots.add(r);
                        }
                    }
                }
            }
            if (roots.isEmpty()) {
                AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
                if (activeRoot != null) {
                    roots.add(activeRoot);
                }
            }

            JSONArray nodes = new JSONArray();
            int[] counter = new int[]{0};
            String activePkg = lastActivePackage;
            for (AccessibilityNodeInfo r : roots) {
                if (r.getPackageName() != null && (activePkg == null || activePkg.isEmpty())) {
                    activePkg = r.getPackageName().toString();
                }
                collectNodes(r, 0, interactiveOnly, cap, counter, nodes);
            }

            out.put("ok", true);
            out.put("via", "accessibility");
            out.put("active_package", activePkg != null ? activePkg : "");
            out.put("active_class", lastActiveClass != null ? lastActiveClass : "");
            out.put("node_count", nodes.length());
            out.put("truncated", counter[0] >= cap);
            if (windowsMeta.length() > 0) {
                out.put("windows", windowsMeta);
            }
            out.put("nodes", nodes);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void collectNodes(AccessibilityNodeInfo node,
                                     int depth,
                                     boolean interactiveOnly,
                                     int maxNodes,
                                     int[] counter,
                                     JSONArray out) throws Exception {
        if (node == null || depth > MAX_DEPTH_DEFAULT || counter[0] >= maxNodes) {
            return;
        }
        if (node.isVisibleToUser()) {
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            String desc = node.getContentDescription() != null
                    ? node.getContentDescription().toString().trim() : "";
            String viewId = node.getViewIdResourceName() != null
                    ? node.getViewIdResourceName() : "";
            boolean actionable = node.isClickable() || node.isLongClickable()
                    || node.isEditable() || node.isScrollable() || node.isCheckable();
            boolean meaningful = actionable || !text.isEmpty() || !desc.isEmpty() || !viewId.isEmpty();

            if (interactiveOnly ? actionable || !text.isEmpty() || !desc.isEmpty() : meaningful) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    int idx = counter[0]++;
                    JSONObject item = new JSONObject();
                    item.put("index", idx);
                    item.put("depth", depth);
                    if (!text.isEmpty()) item.put("text", text);
                    if (!desc.isEmpty()) item.put("desc", desc);
                    if (!viewId.isEmpty()) item.put("view_id", viewId);
                    if (node.getClassName() != null) {
                        item.put("class", shortenClass(node.getClassName().toString()));
                    }
                    if (node.getPackageName() != null) {
                        item.put("package", node.getPackageName().toString());
                    }
                    item.put("bounds", rectToJson(bounds));
                    JSONArray center = new JSONArray();
                    center.put(bounds.centerX());
                    center.put(bounds.centerY());
                    item.put("center", center);
                    if (node.isClickable()) item.put("clickable", true);
                    if (node.isLongClickable()) item.put("long_clickable", true);
                    if (node.isEditable()) item.put("editable", true);
                    if (node.isScrollable()) item.put("scrollable", true);
                    if (node.isCheckable()) {
                        item.put("checkable", true);
                        item.put("checked", node.isChecked());
                    }
                    if (node.isFocused()) item.put("focused", true);
                    if (node.isSelected()) item.put("selected", true);
                    if (!node.isEnabled()) item.put("enabled", false);
                    out.put(item);
                }
            }
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && counter[0] < maxNodes; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, depth + 1, interactiveOnly, maxNodes, counter, out);
            }
        }
    }

    private static String shortenClass(String cls) {
        if (cls.startsWith("android.widget.")) return cls.substring("android.widget.".length());
        if (cls.startsWith("android.view.")) return cls.substring("android.view.".length());
        return cls;
    }

    private static JSONArray rectToJson(Rect r) {
        JSONArray arr = new JSONArray();
        arr.put(r.left);
        arr.put(r.top);
        arr.put(r.right);
        arr.put(r.bottom);
        return arr;
    }

    /** Executes a node action, gesture, or global hardware action. */
    public JSONObject performAction(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            String action = payload.optString("action", "").trim().toLowerCase(Locale.US);
            if (action.isEmpty()) {
                out.put("ok", false);
                out.put("error", "action is required");
                return out;
            }

            // 1. Global hardware / system actions
            Integer globalCode = resolveGlobalAction(action);
            if (globalCode != null) {
                boolean ok = performGlobalAction(globalCode);
                out.put("ok", ok);
                out.put("action", action);
                out.put("via", "accessibility_global");
                return out;
            }

            // 2. Coordinate gestures: tap, long_press, swipe
            if ("tap".equals(action) || ("click".equals(action) && payload.has("x") && payload.has("y"))) {
                float x = (float) payload.optDouble("x", -1);
                float y = (float) payload.optDouble("y", -1);
                if (x < 0 || y < 0) {
                    out.put("ok", false);
                    out.put("error", "valid x and y coordinates are required for tap");
                    return out;
                }
                boolean ok = dispatchStroke(x, y, x, y, Math.max(40L, payload.optLong("duration_ms", 60L)));
                out.put("ok", ok);
                out.put("action", "tap");
                out.put("x", x);
                out.put("y", y);
                out.put("via", "accessibility_gesture");
                return out;
            }

            if ("long_press".equals(action) || ("long_click".equals(action) && payload.has("x") && payload.has("y"))) {
                float x = (float) payload.optDouble("x", -1);
                float y = (float) payload.optDouble("y", -1);
                if (x < 0 || y < 0) {
                    out.put("ok", false);
                    out.put("error", "valid x and y coordinates are required for long_press");
                    return out;
                }
                long dur = Math.max(500L, payload.optLong("duration_ms", 650L));
                boolean ok = dispatchStroke(x, y, x, y, dur);
                out.put("ok", ok);
                out.put("action", "long_press");
                out.put("x", x);
                out.put("y", y);
                out.put("duration_ms", dur);
                out.put("via", "accessibility_gesture");
                return out;
            }

            if ("swipe".equals(action) || "drag".equals(action)) {
                float x1 = (float) payload.optDouble("x1", payload.optDouble("start_x", -1));
                float y1 = (float) payload.optDouble("y1", payload.optDouble("start_y", -1));
                float x2 = (float) payload.optDouble("x2", payload.optDouble("end_x", -1));
                float y2 = (float) payload.optDouble("y2", payload.optDouble("end_y", -1));
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    out.put("ok", false);
                    out.put("error", "x1, y1, x2, y2 coordinates are required for swipe");
                    return out;
                }
                long dur = Math.max(80L, Math.min(payload.optLong("duration_ms", 280L), 5000L));
                boolean ok = dispatchStroke(x1, y1, x2, y2, dur);
                out.put("ok", ok);
                out.put("action", action);
                out.put("duration_ms", dur);
                out.put("via", "accessibility_gesture");
                return out;
            }

            // 3. Semantic node selector actions: click, long_click, set_text, paste, focus, scroll_forward, scroll_backward
            AccessibilityNodeInfo target = findMatchingNode(payload);
            if (target == null && "set_text".equals(action)) {
                target = findFocusedEditableNode();
            }
            if (target == null) {
                out.put("ok", false);
                out.put("error", "no matching UI node found for selector");
                return out;
            }

            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            boolean ok = false;

            if ("click".equals(action)) {
                AccessibilityNodeInfo clickable = findClickableAncestor(target);
                if (clickable != null) {
                    ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                if (!ok && bounds.width() > 0 && bounds.height() > 0) {
                    ok = dispatchStroke(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 60L);
                }
            } else if ("long_click".equals(action)) {
                AccessibilityNodeInfo lc = findLongClickableAncestor(target);
                if (lc != null) {
                    ok = lc.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                }
                if (!ok && bounds.width() > 0 && bounds.height() > 0) {
                    ok = dispatchStroke(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 650L);
                }
            } else if ("set_text".equals(action) || "type".equals(action)) {
                String text = payload.optString("text", "");
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (ok && payload.optBoolean("submit", false)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                    }
                }
            } else if ("paste".equals(action)) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            } else if ("focus".equals(action)) {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            } else if ("scroll_forward".equals(action) || "scroll_down".equals(action)) {
                AccessibilityNodeInfo sc = findScrollableAncestorOrSelf(target);
                ok = sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else if ("scroll_backward".equals(action) || "scroll_up".equals(action)) {
                AccessibilityNodeInfo sc = findScrollableAncestorOrSelf(target);
                ok = sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            } else {
                out.put("ok", false);
                out.put("error", "unsupported action: " + action);
                return out;
            }

            out.put("ok", ok);
            out.put("action", action);
            out.put("bounds", rectToJson(bounds));
            if (target.getText() != null) out.put("matched_text", target.getText().toString());
            if (target.getViewIdResourceName() != null) out.put("matched_view_id", target.getViewIdResourceName());
            out.put("via", "accessibility_node");
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private Integer resolveGlobalAction(String action) {
        switch (action) {
            case "back":
                return GLOBAL_ACTION_BACK;
            case "home":
                return GLOBAL_ACTION_HOME;
            case "recents":
                return GLOBAL_ACTION_RECENTS;
            case "notifications":
                return GLOBAL_ACTION_NOTIFICATIONS;
            case "quick_settings":
                return GLOBAL_ACTION_QUICK_SETTINGS;
            case "power_dialog":
                return GLOBAL_ACTION_POWER_DIALOG;
            case "split_screen":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    return GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN;
                }
                return null;
            case "lock_screen":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return GLOBAL_ACTION_LOCK_SCREEN;
                }
                return null;
            case "take_screenshot_system":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return GLOBAL_ACTION_TAKE_SCREENSHOT;
                }
                return null;
            default:
                return null;
        }
    }

    private boolean dispatchStroke(float x1, float y1, float x2, float y2, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            if (x1 != x2 || y1 != y2) {
                path.lineTo(x2, y2);
            }
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0L, durationMs);
            final GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean completed = new AtomicBoolean(false);
            Runnable postRun = new Runnable() {
                @Override
                public void run() {
                    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            completed.set(true);
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            completed.set(false);
                            latch.countDown();
                        }
                    }, mainHandler);
                    if (!accepted) {
                        latch.countDown();
                    }
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                postRun.run();
                return true;
            } else {
                mainHandler.post(postRun);
                latch.await(durationMs + 2000L, TimeUnit.MILLISECONDS);
                return completed.get();
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatchStroke failed: " + t.getMessage());
            return false;
        }
    }

    private AccessibilityNodeInfo findMatchingNode(JSONObject payload) {
        int wantIndex = payload.optInt("node_index", -1);
        String wantViewId = payload.optString("view_id", "").trim();
        String wantText = payload.optString("target_text", payload.optString("selector", "")).trim();
        String wantDesc = payload.optString("desc", "").trim();
        boolean exact = payload.optBoolean("exact", false);
        boolean interactiveOnly = payload.optBoolean("interactive_only", false);

        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && payload.optBoolean("all_windows", false)) {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w != null && w.getRoot() != null) roots.add(w.getRoot());
                }
            }
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }

        if (wantIndex >= 0) {
            int[] counter = new int[]{0};
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo hit = findByTreeIndex(r, 0, interactiveOnly, wantIndex, counter);
                if (hit != null) return hit;
            }
            return null;
        }

        if (wantViewId.isEmpty() && wantText.isEmpty() && wantDesc.isEmpty()) {
            return null;
        }

        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo hit = searchNodeRecursive(r, wantViewId, wantText, wantDesc, exact);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findByTreeIndex(AccessibilityNodeInfo node,
                                                         int depth,
                                                         boolean interactiveOnly,
                                                         int targetIdx,
                                                         int[] counter) {
        if (node == null || depth > MAX_DEPTH_DEFAULT) return null;
        if (node.isVisibleToUser()) {
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            String desc = node.getContentDescription() != null
                    ? node.getContentDescription().toString().trim() : "";
            String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            boolean actionable = node.isClickable() || node.isLongClickable()
                    || node.isEditable() || node.isScrollable() || node.isCheckable();
            boolean meaningful = actionable || !text.isEmpty() || !desc.isEmpty() || !viewId.isEmpty();
            if (interactiveOnly ? actionable || !text.isEmpty() || !desc.isEmpty() : meaningful) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() > 0 && bounds.height() > 0) {
                    if (counter[0] == targetIdx) {
                        return node;
                    }
                    counter[0]++;
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo found = findByTreeIndex(node.getChild(i), depth + 1, interactiveOnly, targetIdx, counter);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo searchNodeRecursive(AccessibilityNodeInfo node,
                                                             String wantViewId,
                                                             String wantText,
                                                             String wantDesc,
                                                             boolean exact) {
        if (node == null) return null;
        if (node.isVisibleToUser()) {
            boolean match = true;
            if (!wantViewId.isEmpty()) {
                String vid = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
                match = vid.equals(wantViewId) || vid.endsWith(":id/" + wantViewId);
            }
            if (match && !wantText.isEmpty()) {
                String txt = node.getText() != null ? node.getText().toString() : "";
                String dsc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
                if (exact) {
                    match = txt.equalsIgnoreCase(wantText) || dsc.equalsIgnoreCase(wantText);
                } else {
                    String q = wantText.toLowerCase(Locale.US);
                    match = txt.toLowerCase(Locale.US).contains(q) || dsc.toLowerCase(Locale.US).contains(q);
                }
            }
            if (match && !wantDesc.isEmpty()) {
                String dsc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
                match = exact ? dsc.equalsIgnoreCase(wantDesc)
                        : dsc.toLowerCase(Locale.US).contains(wantDesc.toLowerCase(Locale.US));
            }
            if (match) {
                return node;
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo found = searchNodeRecursive(node.getChild(i), wantViewId, wantText, wantDesc, exact);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findFocusedEditableNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        return findFirstEditableRecursive(root);
    }

    private static AccessibilityNodeInfo findFirstEditableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isEditable()) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo found = findFirstEditableRecursive(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo findLongClickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur.isLongClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo findScrollableAncestorOrSelf(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur.isScrollable()) return cur;
            cur = cur.getParent();
        }
        return findFirstScrollableRecursive(node);
    }

    private static AccessibilityNodeInfo findFirstScrollableRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && node.isScrollable()) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo found = findFirstScrollableRecursive(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Captures a PNG screenshot directly via Android 11+ (API 30+) Accessibility API. */
    public JSONObject captureScreenshotToFile(File outFile) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "Accessibility takeScreenshot requires Android 11+ (API 30+)");
            } catch (Exception ignored) {
            }
            return err;
        }
        return Api30ScreenshotHelper.capture(this, outFile, mainHandler);
    }

    /** Isolated API 30+ helper so ART on API 24-29 never resolves TakeScreenshotCallback. */
    private static final class Api30ScreenshotHelper {
        static JSONObject capture(final AgyAccessibilityService service,
                                  final File outFile,
                                  final Handler handler) {
            final JSONObject resp = new JSONObject();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> errorRef = new AtomicReference<>(null);
            final int[] dims = new int[]{0, 0};

            Executor directExecutor = new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };

            try {
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                service.takeScreenshot(Display.DEFAULT_DISPLAY, directExecutor,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                                HardwareBuffer hb = null;
                                Bitmap hwBitmap = null;
                                Bitmap swBitmap = null;
                                try {
                                    hb = screenshot.getHardwareBuffer();
                                    ColorSpace cs = screenshot.getColorSpace();
                                    hwBitmap = Bitmap.wrapHardwareBuffer(hb, cs);
                                    if (hwBitmap == null) {
                                        errorRef.set("wrapHardwareBuffer returned null");
                                        return;
                                    }
                                    swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                    Bitmap target = swBitmap != null ? swBitmap : hwBitmap;
                                    dims[0] = target.getWidth();
                                    dims[1] = target.getHeight();
                                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                        target.compress(Bitmap.CompressFormat.PNG, 100, fos);
                                    }
                                } catch (Throwable t) {
                                    errorRef.set(t.getMessage());
                                } finally {
                                    if (swBitmap != null) swBitmap.recycle();
                                    if (hwBitmap != null) hwBitmap.recycle();
                                    if (hb != null) hb.close();
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                errorRef.set("takeScreenshot failed with code " + errorCode);
                                latch.countDown();
                            }
                        });

                if (!latch.await(6000L, TimeUnit.MILLISECONDS)) {
                    resp.put("ok", false);
                    resp.put("error", "takeScreenshot timed out");
                    return resp;
                }
                if (errorRef.get() != null) {
                    resp.put("ok", false);
                    resp.put("error", errorRef.get());
                    return resp;
                }
                resp.put("ok", true);
                resp.put("path", outFile.getAbsolutePath());
                resp.put("width", dims[0]);
                resp.put("height", dims[1]);
                resp.put("via", "accessibility_api30");
            } catch (Throwable t) {
                try {
                    resp.put("ok", false);
                    resp.put("error", t.getMessage());
                } catch (Exception ignored) {
                }
            }
            return resp;
        }
    }
}
