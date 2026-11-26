package com.agy.gateway.handlers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import com.agy.accessibility.AgyAccessibilityService;
import com.agy.accessibility.AgyNotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gateway handler for cross-app Android phone agent actions:
 * - {@code ui_status}: service & special permission readiness report
 * - {@code ui_tree}: semantic UI node tree via {@link AgyAccessibilityService}
 * - {@code ui_action}: click/type/scroll/swipe/tap/global actions
 * - {@code ui_screenshot}: fast API 30+ PNG screen capture
 * - {@code notifications_list} / {@code notification_action}: active notifications & replies
 * - {@code apps_list} / {@code app_launch}: installed app discovery & launching
 * - {@code send_intent} / {@code open_settings}: deep links, intents & system settings navigation
 */
public final class PhoneAgentHandler {

    private final Context appContext;

    public PhoneAgentHandler(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public JSONObject handleStatus(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            String openSetting = payload != null ? payload.optString("open_setting", "").trim() : "";
            if (!openSetting.isEmpty()) {
                JSONObject nav = openNamedSetting(openSetting);
                out.put("opened_setting", nav);
            }
            boolean reqPerms = payload != null && payload.optBoolean("request_permissions", false);
            JSONObject assistantPerms = new PersonalAssistantHandler(appContext).handlePermissions(
                    new JSONObject().put("request", reqPerms));

            boolean a11yConnected = AgyAccessibilityService.isConnected();
            boolean a11yEnabled = AgyAccessibilityService.isEnabledInSettings(appContext);
            boolean notifConnected = AgyNotificationListenerService.isConnected();
            boolean notifEnabled = AgyNotificationListenerService.isEnabledInSettings(appContext);

            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            boolean batteryUnrestricted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || (pm != null && pm.isIgnoringBatteryOptimizations(appContext.getPackageName()));

            boolean allFilesAccess = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                allFilesAccess = Api30StorageHelper.isExternalStorageManager();
            }

            boolean canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(appContext);

            boolean canRequestInstalls = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canRequestInstalls = appContext.getPackageManager().canRequestPackageInstalls();
            }

            out.put("ok", true);
            out.put("package", appContext.getPackageName());
            out.put("sdk_int", Build.VERSION.SDK_INT);
            out.put("accessibility_connected", a11yConnected);
            out.put("accessibility_enabled", a11yEnabled);
            out.put("active_package", AgyAccessibilityService.getLastActivePackage());
            out.put("active_class", AgyAccessibilityService.getLastActiveClass());
            out.put("notification_listener_connected", notifConnected);
            out.put("notification_listener_enabled", notifEnabled);
            out.put("battery_unrestricted", batteryUnrestricted);
            out.put("all_files_access", allFilesAccess);
            out.put("can_draw_overlays", canDrawOverlays);
            out.put("can_request_package_installs", canRequestInstalls);
            out.put("assistant_permissions", assistantPerms.optJSONObject("permissions"));
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    public JSONObject handleUiTree(JSONObject payload) {
        AgyAccessibilityService svc = AgyAccessibilityService.getInstance();
        if (svc == null) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("accessibility_connected", false);
                out.put("accessibility_enabled", AgyAccessibilityService.isEnabledInSettings(appContext));
                out.put("error", "Accessibility service is not connected");
                out.put("hint", "Enable Antigravity in Settings -> Accessibility (or call android_agent_status with open_setting='accessibility').");
            } catch (Exception ignored) {
            }
            return out;
        }
        boolean interactiveOnly = payload.optBoolean("interactive_only", false);
        int maxNodes = payload.optInt("max_nodes", 350);
        boolean allWindows = payload.optBoolean("all_windows", false);
        return svc.dumpUiTree(interactiveOnly, maxNodes, allWindows);
    }

    public JSONObject handleUiAction(JSONObject payload) {
        AgyAccessibilityService svc = AgyAccessibilityService.getInstance();
        if (svc == null) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("accessibility_connected", false);
                out.put("accessibility_enabled", AgyAccessibilityService.isEnabledInSettings(appContext));
                out.put("error", "Accessibility service is not connected");
                out.put("hint", "Enable Antigravity in Settings -> Accessibility (or call android_agent_status with open_setting='accessibility').");
            } catch (Exception ignored) {
            }
            return out;
        }
        return svc.performAction(payload);
    }

    public JSONObject handleUiScreenshot(JSONObject payload) {
        AgyAccessibilityService svc = AgyAccessibilityService.getInstance();
        if (svc == null) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("accessibility_connected", false);
                out.put("accessibility_enabled", AgyAccessibilityService.isEnabledInSettings(appContext));
                out.put("error", "Accessibility service is not connected");
                out.put("hint", "Enable Antigravity in Settings -> Accessibility to capture screenshots without ADB.");
            } catch (Exception ignored) {
            }
            return out;
        }
        String customPath = payload.optString("path", "").trim();
        File outFile;
        if (!customPath.isEmpty()) {
            outFile = new File(customPath);
        } else {
            File dir = new File(appContext.getCacheDir(), "screenshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            outFile = new File(dir, "screen_" + System.currentTimeMillis() + ".png");
        }
        return svc.captureScreenshotToFile(outFile);
    }

    public JSONObject handleNotificationsList(JSONObject payload) {
        if (payload != null && payload.optBoolean("history", false)) {
            return AgyNotificationListenerService.queryHistory(
                    appContext,
                    payload.optString("package", ""),
                    payload.optString("query", ""),
                    payload.optLong("since_ms", 0L),
                    payload.optInt("limit", 50));
        }
        AgyNotificationListenerService svc = AgyNotificationListenerService.getInstance();
        if (svc == null) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("notification_listener_connected", false);
                out.put("notification_listener_enabled", AgyNotificationListenerService.isEnabledInSettings(appContext));
                out.put("error", "Notification Listener service is not connected");
                out.put("hint", "Enable Antigravity in Settings -> Notification access (or call android_agent_status with open_setting='notification_listener').");
            } catch (Exception ignored) {
            }
            return out;
        }
        String pkgFilter = payload.optString("package", "");
        boolean includeOngoing = payload.optBoolean("include_ongoing", false);
        int limit = payload.optInt("limit", 50);
        return svc.listActive(pkgFilter, includeOngoing, limit);
    }

    public JSONObject handleNotificationAction(JSONObject payload) {
        if (payload != null && "vip_rules".equalsIgnoreCase(payload.optString("action", ""))) {
            return AgyNotificationListenerService.manageVipRules(appContext, payload);
        }
        AgyNotificationListenerService svc = AgyNotificationListenerService.getInstance();
        if (svc == null) {
            JSONObject out = new JSONObject();
            try {
                out.put("ok", false);
                out.put("notification_listener_connected", false);
                out.put("notification_listener_enabled", AgyNotificationListenerService.isEnabledInSettings(appContext));
                out.put("error", "Notification Listener service is not connected");
                out.put("hint", "Enable Antigravity in Settings -> Notification access.");
            } catch (Exception ignored) {
            }
            return out;
        }
        return svc.actOnNotification(payload);
    }

    public JSONObject handleAppsList(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            String query = payload.optString("query", "").trim().toLowerCase(Locale.US);
            boolean includeSystem = payload.optBoolean("include_system", true);
            int limit = Math.max(1, Math.min(payload.optInt("limit", 250), 1000));

            List<AppEntry> entries = queryLaunchableApps(includeSystem);
            JSONArray arr = new JSONArray();
            for (AppEntry e : entries) {
                if (!query.isEmpty()
                        && !e.label.toLowerCase(Locale.US).contains(query)
                        && !e.packageName.toLowerCase(Locale.US).contains(query)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("package", e.packageName);
                item.put("label", e.label);
                item.put("activity", e.activityName);
                item.put("system", e.isSystem);
                arr.put(item);
                if (arr.length() >= limit) {
                    break;
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("apps", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    public JSONObject handleAppLaunch(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            PackageManager pm = appContext.getPackageManager();
            String pkg = payload.optString("package", "").trim();
            String query = payload.optString("query", "").trim();
            String matchedLabel = "";

            if (pkg.isEmpty() && !query.isEmpty()) {
                AppEntry best = findBestAppMatch(query);
                if (best != null) {
                    pkg = best.packageName;
                    matchedLabel = best.label;
                }
            }

            if (pkg.isEmpty()) {
                putError(out, "package or matching app query is required");
                return out;
            }

            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null && !query.isEmpty()) {
                AppEntry best = findBestAppMatch(query);
                if (best != null) {
                    pkg = best.packageName;
                    matchedLabel = best.label;
                    launch = pm.getLaunchIntentForPackage(pkg);
                }
            }
            if (launch == null) {
                putError(out, "no launchable main activity found for package: " + pkg);
                return out;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(launch);

            if (matchedLabel.isEmpty()) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    CharSequence lbl = pm.getApplicationLabel(ai);
                    if (lbl != null) matchedLabel = lbl.toString();
                } catch (Throwable ignored) {
                }
            }
            out.put("ok", true);
            out.put("package", pkg);
            if (!matchedLabel.isEmpty()) {
                out.put("label", matchedLabel);
            }
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    public JSONObject handleSendIntent(JSONObject payload) {
        String setting = payload.optString("setting", "").trim();
        if (!setting.isEmpty()) {
            return openNamedSetting(setting);
        }

        JSONObject out = new JSONObject();
        try {
            String action = payload.optString("action", "").trim();
            String uriStr = payload.optString("uri", "").trim();
            String mimeType = payload.optString("mime_type", "").trim();
            String pkg = payload.optString("package", "").trim();
            String component = payload.optString("component", "").trim();
            String category = payload.optString("category", "").trim();

            if (action.isEmpty()) {
                action = !uriStr.isEmpty() ? Intent.ACTION_VIEW : "";
            }
            Intent intent = action.isEmpty() ? new Intent() : new Intent(action);

            if (!uriStr.isEmpty() && !mimeType.isEmpty()) {
                intent.setDataAndType(Uri.parse(uriStr), mimeType);
            } else if (!uriStr.isEmpty()) {
                intent.setData(Uri.parse(uriStr));
            } else if (!mimeType.isEmpty()) {
                intent.setType(mimeType);
            }

            if (!category.isEmpty()) {
                intent.addCategory(category);
            }
            if (!pkg.isEmpty()) {
                intent.setPackage(pkg);
            }
            if (!component.isEmpty()) {
                ComponentName cn = ComponentName.unflattenFromString(component);
                if (cn != null) {
                    intent.setComponent(cn);
                }
            }

            JSONObject extras = payload.optJSONObject("extras");
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = extras.opt(k);
                    if (v instanceof Boolean) {
                        intent.putExtra(k, (Boolean) v);
                    } else if (v instanceof Integer) {
                        intent.putExtra(k, (Integer) v);
                    } else if (v instanceof Long) {
                        intent.putExtra(k, (Long) v);
                    } else if (v instanceof Number) {
                        intent.putExtra(k, ((Number) v).doubleValue());
                    } else if (v != null && v != JSONObject.NULL) {
                        intent.putExtra(k, String.valueOf(v));
                    }
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);

            out.put("ok", true);
            if (!action.isEmpty()) out.put("action", action);
            if (!uriStr.isEmpty()) out.put("uri", uriStr);
            if (!pkg.isEmpty()) out.put("package", pkg);
            if (!component.isEmpty()) out.put("component", component);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    public JSONObject handleOpenSettings(JSONObject payload) {
        String setting = payload.optString("setting", payload.optString("target", "settings")).trim();
        return openNamedSetting(setting);
    }

    private JSONObject openNamedSetting(String rawSetting) {
        JSONObject out = new JSONObject();
        String key = rawSetting.toLowerCase(Locale.US);
        String pkgUri = "package:" + appContext.getPackageName();
        String action;
        boolean withPkgUri = false;
        String fallbackAction = Settings.ACTION_SETTINGS;

        switch (key) {
            case "accessibility":
            case "a11y":
                action = Settings.ACTION_ACCESSIBILITY_SETTINGS;
                break;
            case "notification_listener":
            case "notifications":
            case "notification_access":
                action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
                break;
            case "battery":
            case "battery_optimization":
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
                withPkgUri = true;
                fallbackAction = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS;
                break;
            case "app_details":
            case "app_info":
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
                withPkgUri = true;
                break;
            case "overlay":
            case "draw_overlays":
                action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
                withPkgUri = true;
                break;
            case "install_unknown_apps":
            case "unknown_sources":
                action = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                        : Settings.ACTION_SECURITY_SETTINGS;
                withPkgUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
                fallbackAction = Settings.ACTION_SECURITY_SETTINGS;
                break;
            case "all_files":
            case "manage_storage":
                action = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION";
                withPkgUri = true;
                fallbackAction = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION";
                break;
            case "wifi":
                action = Settings.ACTION_WIFI_SETTINGS;
                break;
            case "bluetooth":
                action = Settings.ACTION_BLUETOOTH_SETTINGS;
                break;
            case "location":
                action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
                break;
            case "display":
                action = Settings.ACTION_DISPLAY_SETTINGS;
                break;
            case "sound":
                action = Settings.ACTION_SOUND_SETTINGS;
                break;
            case "developer":
            case "wireless_debugging":
                action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
                break;
            default:
                action = Settings.ACTION_SETTINGS;
                break;
        }

        try {
            Intent intent = new Intent(action);
            if (withPkgUri) {
                intent.setData(Uri.parse(pkgUri));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                appContext.startActivity(intent);
            } catch (Throwable primaryErr) {
                Intent fb = new Intent(fallbackAction);
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(fb);
                action = fallbackAction;
            }
            out.put("ok", true);
            out.put("setting", key);
            out.put("intent_action", action);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private List<AppEntry> queryLaunchableApps(boolean includeSystem) {
        PackageManager pm = appContext.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (resolved != null) {
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null || ri.activityInfo.packageName == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg)) {
                    continue;
                }
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                boolean isSystem = ai != null
                        && ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                        && ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0);
                if (!includeSystem && isSystem) {
                    continue;
                }
                CharSequence lblSeq = ri.loadLabel(pm);
                String label = lblSeq != null ? lblSeq.toString().trim() : pkg;
                String act = ri.activityInfo.name != null ? ri.activityInfo.name : "";
                result.add(new AppEntry(pkg, label, act, isSystem));
            }
        }
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return result;
    }

    private AppEntry findBestAppMatch(String query) {
        String q = query.trim().toLowerCase(Locale.US);
        List<AppEntry> apps = queryLaunchableApps(true);
        AppEntry prefixMatch = null;
        AppEntry containsMatch = null;
        for (AppEntry e : apps) {
            String lbl = e.label.toLowerCase(Locale.US);
            String pkg = e.packageName.toLowerCase(Locale.US);
            if (lbl.equals(q) || pkg.equals(q)) {
                return e;
            }
            if (prefixMatch == null && lbl.startsWith(q)) {
                prefixMatch = e;
            }
            if (containsMatch == null && (lbl.contains(q) || pkg.contains(q))) {
                containsMatch = e;
            }
        }
        return prefixMatch != null ? prefixMatch : containsMatch;
    }

    private static void putError(JSONObject out, String msg) {
        try {
            out.put("ok", false);
            out.put("error", msg != null ? msg : "unknown error");
        } catch (Exception ignored) {
        }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final String activityName;
        final boolean isSystem;

        AppEntry(String packageName, String label, String activityName, boolean isSystem) {
            this.packageName = packageName;
            this.label = label;
            this.activityName = activityName;
            this.isSystem = isSystem;
        }
    }

    private static final class Api30StorageHelper {
        static boolean isExternalStorageManager() {
            return Environment.isExternalStorageManager();
        }
    }
}
