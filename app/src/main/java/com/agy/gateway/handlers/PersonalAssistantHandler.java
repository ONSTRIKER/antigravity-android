package com.agy.gateway.handlers;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Telephony;

import com.agy.core.ProactiveAlarmReceiver;
import com.agy.ui.PermissionRequestActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native Android Personal Assistant ContentProvider & OS Context handler:
 * - {@code contacts}: search/resolve phone contacts (names, phones, emails, orgs/roles)
 * - {@code device_calendar}: read/create events across ALL synced phone calendars
 *   (Microsoft Outlook work calendar + Google Calendar + Samsung Calendar)
 * - {@code alarms}: set native Clock alarms/timers + hardware-exact proactive AI wakeups
 * - {@code location}: instant GPS/network location & commute awareness
 * - {@code comms_history}: search SMS threads & Call Log (missed/incoming/outgoing calls)
 * - {@code assistant_permissions}: inspect & one-shot prompt for assistant runtime permissions
 */
public final class PersonalAssistantHandler {

    private static final String[] ASSISTANT_PERMISSIONS = new String[]{
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
    };

    private final Context appContext;

    public PersonalAssistantHandler(Context context) {
        this.appContext = context.getApplicationContext();
    }

    private boolean hasPerm(String perm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return appContext.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    public JSONObject handlePermissions(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            boolean request = payload != null && payload.optBoolean("request", false);
            if (request) {
                PermissionRequestActivity.launchForMissing(appContext, ASSISTANT_PERMISSIONS);
            }
            JSONObject grants = new JSONObject();
            grants.put("read_contacts", hasPerm(Manifest.permission.READ_CONTACTS));
            grants.put("write_contacts", hasPerm(Manifest.permission.WRITE_CONTACTS));
            grants.put("read_calendar", hasPerm(Manifest.permission.READ_CALENDAR));
            grants.put("write_calendar", hasPerm(Manifest.permission.WRITE_CALENDAR));
            grants.put("fine_location", hasPerm(Manifest.permission.ACCESS_FINE_LOCATION));
            grants.put("coarse_location", hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION));
            grants.put("read_sms", hasPerm(Manifest.permission.READ_SMS));
            grants.put("read_call_log", hasPerm(Manifest.permission.READ_CALL_LOG));

            out.put("ok", true);
            out.put("prompted", request);
            out.put("permissions", grants);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Contacts (ContactsContract)
    // -------------------------------------------------------------------------
    public JSONObject handleContacts(JSONObject payload) {
        String action = payload.optString("action", "search").trim().toLowerCase(Locale.US);
        if ("add_intent".equals(action) || "insert_ui".equals(action)) {
            return openAddContactIntent(payload);
        }
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            if (payload.optBoolean("prompt_if_missing", true)) {
                PermissionRequestActivity.launchForMissing(appContext,
                        new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS});
            }
            return permDenied("READ_CONTACTS", "Grant Contacts permission on screen (or run agy-grant-all) and retry.");
        }

        JSONObject out = new JSONObject();
        try {
            String query = payload.optString("query", "").trim().toLowerCase(Locale.US);
            int limit = Math.max(1, Math.min(payload.optInt("limit", 50), 300));
            ContentResolver cr = appContext.getContentResolver();

            Map<String, ContactRecord> map = new LinkedHashMap<>();

            // 1. Base contacts
            try (Cursor c = cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY},
                    null, null,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        String name = c.getString(1);
                        if (id == null || name == null || name.trim().isEmpty()) continue;
                        map.put(id, new ContactRecord(id, name.trim()));
                    }
                }
            }

            // 2. Phone numbers
            try (Cursor p = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null)) {
                if (p != null) {
                    while (p.moveToNext()) {
                        ContactRecord rec = map.get(p.getString(0));
                        String num = p.getString(1);
                        if (rec != null && num != null && !num.trim().isEmpty()) {
                            String clean = num.trim();
                            if (!rec.phones.contains(clean)) rec.phones.add(clean);
                        }
                    }
                }
            }

            // 3. Emails
            try (Cursor e = cr.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Email.ADDRESS
                    },
                    null, null, null)) {
                if (e != null) {
                    while (e.moveToNext()) {
                        ContactRecord rec = map.get(e.getString(0));
                        String addr = e.getString(1);
                        if (rec != null && addr != null && !addr.trim().isEmpty()) {
                            String clean = addr.trim();
                            if (!rec.emails.contains(clean)) rec.emails.add(clean);
                        }
                    }
                }
            }

            // 4. Organization & Role
            try (Cursor org = cr.query(
                    ContactsContract.Data.CONTENT_URI,
                    new String[]{
                            ContactsContract.Data.CONTACT_ID,
                            ContactsContract.CommonDataKinds.Organization.COMPANY,
                            ContactsContract.CommonDataKinds.Organization.TITLE
                    },
                    ContactsContract.Data.MIMETYPE + " = ?",
                    new String[]{ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE},
                    null)) {
                if (org != null) {
                    while (org.moveToNext()) {
                        ContactRecord rec = map.get(org.getString(0));
                        if (rec != null) {
                            if (org.getString(1) != null) rec.company = org.getString(1).trim();
                            if (org.getString(2) != null) rec.jobTitle = org.getString(2).trim();
                        }
                    }
                }
            }

            JSONArray arr = new JSONArray();
            for (ContactRecord rec : map.values()) {
                if (!query.isEmpty() && !rec.matches(query)) {
                    continue;
                }
                arr.put(rec.toJson());
                if (arr.length() >= limit) {
                    break;
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("contacts", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private JSONObject openAddContactIntent(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION);
            intent.setType(ContactsContract.RawContacts.CONTENT_TYPE);
            if (payload.has("name")) {
                intent.putExtra(ContactsContract.Intents.Insert.NAME, payload.optString("name"));
            }
            if (payload.has("phone")) {
                intent.putExtra(ContactsContract.Intents.Insert.PHONE, payload.optString("phone"));
            }
            if (payload.has("email")) {
                intent.putExtra(ContactsContract.Intents.Insert.EMAIL, payload.optString("email"));
            }
            if (payload.has("company")) {
                intent.putExtra(ContactsContract.Intents.Insert.COMPANY, payload.optString("company"));
            }
            if (payload.has("job_title")) {
                intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE, payload.optString("job_title"));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            out.put("ok", true);
            out.put("action", "add_intent");
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Unified Device Calendar (Outlook + Google + Samsung via CalendarContract)
    // -------------------------------------------------------------------------
    public JSONObject handleCalendar(JSONObject payload) {
        String action = payload.optString("action", "events").trim().toLowerCase(Locale.US);
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) {
            if (payload.optBoolean("prompt_if_missing", true)) {
                PermissionRequestActivity.launchForMissing(appContext,
                        new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR});
            }
            return permDenied("READ_CALENDAR", "Grant Calendar permission on screen (or run agy-grant-all) and retry.");
        }

        if ("calendars".equals(action) || "list_calendars".equals(action)) {
            return listCalendars();
        }
        if ("create".equals(action) || "add".equals(action)) {
            return createCalendarEvent(payload);
        }
        return queryCalendarInstances(payload);
    }

    private JSONObject listCalendars() {
        JSONObject out = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            String[] proj = new String[]{
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.VISIBLE,
                    CalendarContract.Calendars.IS_PRIMARY
            };
            try (Cursor c = appContext.getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject item = new JSONObject();
                        item.put("id", c.getLong(0));
                        item.put("display_name", c.getString(1) != null ? c.getString(1) : "");
                        item.put("account_name", c.getString(2) != null ? c.getString(2) : "");
                        item.put("account_type", c.getString(3) != null ? c.getString(3) : "");
                        item.put("visible", c.getInt(4) != 0);
                        item.put("primary", c.getInt(5) != 0);
                        arr.put(item);
                    }
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("calendars", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private JSONObject queryCalendarInstances(JSONObject payload) {
        JSONObject out = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            long startMs = payload.optLong("start_ms", now - 12L * 3600_000L);
            double daysAhead = payload.optDouble("days_ahead", 7.0);
            long endMs = payload.optLong("end_ms", now + (long) (Math.max(0.5, daysAhead) * 86_400_000L));
            String query = payload.optString("query", "").trim().toLowerCase(Locale.US);
            String accountFilter = payload.optString("account", "").trim().toLowerCase(Locale.US);
            int limit = Math.max(1, Math.min(payload.optInt("limit", 100), 500));

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, startMs);
            ContentUris.appendId(builder, endMs);

            String[] proj = new String[]{
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Instances.ORGANIZER,
                    CalendarContract.Instances.AVAILABILITY
            };

            JSONArray arr = new JSONArray();
            try (Cursor c = appContext.getContentResolver().query(
                    builder.build(),
                    proj,
                    CalendarContract.Instances.VISIBLE + " = 1",
                    null,
                    CalendarContract.Instances.BEGIN + " ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String title = c.getString(1) != null ? c.getString(1) : "(No title)";
                        String desc = c.getString(2) != null ? c.getString(2) : "";
                        String loc = c.getString(3) != null ? c.getString(3) : "";
                        String calName = c.getString(7) != null ? c.getString(7) : "";
                        String acct = c.getString(8) != null ? c.getString(8) : "";

                        if (!accountFilter.isEmpty()
                                && !acct.toLowerCase(Locale.US).contains(accountFilter)
                                && !calName.toLowerCase(Locale.US).contains(accountFilter)) {
                            continue;
                        }
                        if (!query.isEmpty()) {
                            String blob = (title + " " + desc + " " + loc + " " + calName).toLowerCase(Locale.US);
                            if (!blob.contains(query)) continue;
                        }

                        JSONObject ev = new JSONObject();
                        ev.put("event_id", c.getLong(0));
                        ev.put("title", title);
                        if (!desc.isEmpty()) ev.put("description", desc.length() > 500 ? desc.substring(0, 500) : desc);
                        if (!loc.isEmpty()) ev.put("location", loc);
                        ev.put("begin_ms", c.getLong(4));
                        ev.put("end_ms", c.getLong(5));
                        ev.put("all_day", c.getInt(6) != 0);
                        ev.put("calendar_name", calName);
                        ev.put("account_name", acct);
                        if (c.getString(9) != null) ev.put("organizer", c.getString(9));
                        ev.put("busy", c.getInt(10) == CalendarContract.Events.AVAILABILITY_BUSY);
                        arr.put(ev);
                        if (arr.length() >= limit) break;
                    }
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("events", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private JSONObject createCalendarEvent(JSONObject payload) {
        if (!hasPerm(Manifest.permission.WRITE_CALENDAR)) {
            PermissionRequestActivity.launchForMissing(appContext, new String[]{Manifest.permission.WRITE_CALENDAR});
            return permDenied("WRITE_CALENDAR", "Grant Calendar write permission on screen and retry.");
        }
        JSONObject out = new JSONObject();
        try {
            long calId = payload.optLong("calendar_id", findPrimaryCalendarId());
            if (calId < 0) {
                putError(out, "No writable calendar found on device");
                return out;
            }
            String title = payload.optString("title", "").trim();
            long beginMs = payload.optLong("begin_ms", 0L);
            long endMs = payload.optLong("end_ms", beginMs + 3600_000L);
            if (title.isEmpty() || beginMs <= 0L) {
                putError(out, "title and begin_ms are required");
                return out;
            }

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calId);
            values.put(CalendarContract.Events.TITLE, title);
            values.put(CalendarContract.Events.DTSTART, beginMs);
            values.put(CalendarContract.Events.DTEND, endMs);
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            if (payload.has("description")) {
                values.put(CalendarContract.Events.DESCRIPTION, payload.optString("description"));
            }
            if (payload.has("location")) {
                values.put(CalendarContract.Events.EVENT_LOCATION, payload.optString("location"));
            }
            values.put(CalendarContract.Events.ALL_DAY, payload.optBoolean("all_day", false) ? 1 : 0);

            Uri uri = appContext.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            long eventId = uri != null ? ContentUris.parseId(uri) : -1L;
            out.put("ok", eventId >= 0);
            out.put("event_id", eventId);
            out.put("calendar_id", calId);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private long findPrimaryCalendarId() {
        long fallback = -1L;
        try (Cursor c = appContext.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY},
                CalendarContract.Calendars.VISIBLE + " = 1",
                null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    if (fallback < 0) fallback = id;
                    if (c.getInt(1) != 0) return id;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    // -------------------------------------------------------------------------
    // Native Clock Alarms/Timers & Exact Proactive AI Wakeups
    // -------------------------------------------------------------------------
    public JSONObject handleAlarms(JSONObject payload) {
        JSONObject out = new JSONObject();
        String action = payload.optString("action", "list_exact").trim().toLowerCase(Locale.US);
        try {
            if ("set_clock_alarm".equals(action) || "alarm".equals(action)) {
                int hour = payload.optInt("hour", -1);
                int minute = payload.optInt("minute", 0);
                if (hour < 0 || hour > 23) {
                    putError(out, "hour (0..23) is required for set_clock_alarm");
                    return out;
                }
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, hour);
                i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
                i.putExtra(AlarmClock.EXTRA_MESSAGE, payload.optString("label", "Antigravity Alarm"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, payload.optBoolean("skip_ui", true));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(i);
                out.put("ok", true);
                out.put("action", "set_clock_alarm");
                out.put("hour", hour);
                out.put("minute", minute);
                return out;
            }

            if ("set_timer".equals(action) || "timer".equals(action)) {
                int seconds = payload.optInt("seconds", 0);
                if (seconds <= 0) {
                    putError(out, "seconds (>0) is required for set_timer");
                    return out;
                }
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
                i.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
                i.putExtra(AlarmClock.EXTRA_MESSAGE, payload.optString("label", "Antigravity Timer"));
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, payload.optBoolean("skip_ui", true));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(i);
                out.put("ok", true);
                out.put("action", "set_timer");
                out.put("seconds", seconds);
                return out;
            }

            if ("schedule_exact".equals(action)) {
                long triggerAt = payload.optLong("trigger_at_ms", 0L);
                if (triggerAt <= 0L && payload.has("hour")) {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    cal.set(java.util.Calendar.HOUR_OF_DAY, payload.optInt("hour", 7));
                    cal.set(java.util.Calendar.MINUTE, payload.optInt("minute", 0));
                    cal.set(java.util.Calendar.SECOND, 0);
                    cal.set(java.util.Calendar.MILLISECOND, 0);
                    if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                    }
                    triggerAt = cal.getTimeInMillis();
                }
                if (triggerAt <= 0L) {
                    putError(out, "trigger_at_ms or hour/minute is required");
                    return out;
                }
                return ProactiveAlarmReceiver.scheduleExact(
                        appContext,
                        payload.optString("alarm_id", ""),
                        triggerAt,
                        payload.optString("task_id", ""),
                        payload.optString("title", ""),
                        payload.optString("message", ""),
                        payload.optBoolean("repeat_daily", false));
            }

            if ("cancel_exact".equals(action)) {
                return ProactiveAlarmReceiver.cancelExact(appContext, payload.optString("alarm_id", ""));
            }

            return ProactiveAlarmReceiver.listExact(appContext);
        } catch (Throwable t) {
            putError(out, t.getMessage());
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // Device Location (GPS / Network)
    // -------------------------------------------------------------------------
    public JSONObject handleLocation(JSONObject payload) {
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
                && !hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (payload.optBoolean("prompt_if_missing", true)) {
                PermissionRequestActivity.launchForMissing(appContext,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            }
            return permDenied("ACCESS_FINE_LOCATION", "Grant Location permission on screen (or run agy-grant-all) and retry.");
        }

        JSONObject out = new JSONObject();
        try {
            LocationManager lm = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                putError(out, "LocationManager unavailable");
                return out;
            }

            boolean fresh = payload.optBoolean("fresh", false);
            long timeoutMs = Math.max(1000L, Math.min(payload.optLong("timeout_ms", 5000L), 15000L));
            Location best = getBestLastKnownLocation(lm);

            if (fresh || best == null || (System.currentTimeMillis() - best.getTime()) > 15 * 60_000L) {
                Location updated = requestSingleUpdate(lm, timeoutMs);
                if (updated != null) {
                    best = updated;
                }
            }

            if (best == null) {
                putError(out, "No location fix currently available (check device Location toggle)");
                return out;
            }

            out.put("ok", true);
            out.put("latitude", best.getLatitude());
            out.put("longitude", best.getLongitude());
            out.put("accuracy_m", best.getAccuracy());
            out.put("provider", best.getProvider());
            out.put("age_sec", Math.max(0L, (System.currentTimeMillis() - best.getTime()) / 1000L));
            out.put("maps_url", String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f",
                    best.getLatitude(), best.getLongitude()));
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private Location getBestLastKnownLocation(LocationManager lm) {
        Location best = null;
        String[] providers = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String p : providers) {
            try {
                Location loc = lm.getLastKnownLocation(p);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) {
                    best = loc;
                }
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private Location requestSingleUpdate(final LocationManager lm, long timeoutMs) {
        final AtomicReference<Location> ref = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null);
        if (provider == null) return null;

        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                ref.set(location);
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String p, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String p) {}

            @Override
            public void onProviderDisabled(String p) {
                latch.countDown();
            }
        };

        try {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        } finally {
            try {
                lm.removeUpdates(listener);
            } catch (Throwable ignored) {
            }
        }
        return ref.get();
    }

    // -------------------------------------------------------------------------
    // SMS & Call Log History
    // -------------------------------------------------------------------------
    public JSONObject handleCommsHistory(JSONObject payload) {
        String kind = payload.optString("kind", "sms").trim().toLowerCase(Locale.US);
        if ("calls".equals(kind) || "call_log".equals(kind)) {
            return queryCallLog(payload);
        }
        return querySms(payload);
    }

    private JSONObject querySms(JSONObject payload) {
        if (!hasPerm(Manifest.permission.READ_SMS)) {
            if (payload.optBoolean("prompt_if_missing", true)) {
                PermissionRequestActivity.launchForMissing(appContext, new String[]{Manifest.permission.READ_SMS});
            }
            return permDenied("READ_SMS", "Grant SMS permission on screen (or run agy-grant-all) and retry.");
        }
        JSONObject out = new JSONObject();
        try {
            String query = payload.optString("query", "").trim().toLowerCase(Locale.US);
            String addressFilter = payload.optString("address", "").trim().toLowerCase(Locale.US);
            long sinceMs = payload.optLong("since_ms", 0L);
            int limit = Math.max(1, Math.min(payload.optInt("limit", 30), 200));

            JSONArray arr = new JSONArray();
            try (Cursor c = appContext.getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    new String[]{"_id", "address", "body", "date", "type", "read"},
                    sinceMs > 0L ? "date >= ?" : null,
                    sinceMs > 0L ? new String[]{String.valueOf(sinceMs)} : null,
                    "date DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String addr = c.getString(1) != null ? c.getString(1) : "";
                        String body = c.getString(2) != null ? c.getString(2) : "";
                        if (!addressFilter.isEmpty() && !addr.toLowerCase(Locale.US).contains(addressFilter)) {
                            continue;
                        }
                        if (!query.isEmpty()
                                && !body.toLowerCase(Locale.US).contains(query)
                                && !addr.toLowerCase(Locale.US).contains(query)) {
                            continue;
                        }
                        JSONObject item = new JSONObject();
                        item.put("id", c.getLong(0));
                        item.put("address", addr);
                        item.put("body", body);
                        item.put("date_ms", c.getLong(3));
                        int t = c.getInt(4);
                        item.put("direction", t == 1 ? "inbox" : (t == 2 ? "sent" : "other"));
                        item.put("read", c.getInt(5) != 0);
                        arr.put(item);
                        if (arr.length() >= limit) break;
                    }
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("messages", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private JSONObject queryCallLog(JSONObject payload) {
        if (!hasPerm(Manifest.permission.READ_CALL_LOG)) {
            if (payload.optBoolean("prompt_if_missing", true)) {
                PermissionRequestActivity.launchForMissing(appContext, new String[]{Manifest.permission.READ_CALL_LOG});
            }
            return permDenied("READ_CALL_LOG", "Grant Call Logs permission on screen (or run agy-grant-all) and retry.");
        }
        JSONObject out = new JSONObject();
        try {
            boolean missedOnly = payload.optBoolean("missed_only", false);
            long sinceMs = payload.optLong("since_ms", 0L);
            int limit = Math.max(1, Math.min(payload.optInt("limit", 30), 200));

            JSONArray arr = new JSONArray();
            try (Cursor c = appContext.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION
                    },
                    sinceMs > 0L ? CallLog.Calls.DATE + " >= ?" : null,
                    sinceMs > 0L ? new String[]{String.valueOf(sinceMs)} : null,
                    CallLog.Calls.DATE + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        int type = c.getInt(2);
                        if (missedOnly && type != CallLog.Calls.MISSED_TYPE) {
                            continue;
                        }
                        JSONObject item = new JSONObject();
                        item.put("number", c.getString(0) != null ? c.getString(0) : "");
                        if (c.getString(1) != null) item.put("name", c.getString(1));
                        String typeName = "other";
                        if (type == CallLog.Calls.INCOMING_TYPE) typeName = "incoming";
                        else if (type == CallLog.Calls.OUTGOING_TYPE) typeName = "outgoing";
                        else if (type == CallLog.Calls.MISSED_TYPE) typeName = "missed";
                        else if (type == CallLog.Calls.VOICEMAIL_TYPE) typeName = "voicemail";
                        else if (type == CallLog.Calls.REJECTED_TYPE) typeName = "rejected";
                        item.put("type", typeName);
                        item.put("date_ms", c.getLong(3));
                        item.put("duration_sec", c.getLong(4));
                        arr.put(item);
                        if (arr.length() >= limit) break;
                    }
                }
            }
            out.put("ok", true);
            out.put("count", arr.length());
            out.put("calls", arr);
        } catch (Throwable t) {
            putError(out, t.getMessage());
        }
        return out;
    }

    private static JSONObject permDenied(String perm, String hint) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("permission_required", perm);
            out.put("error", "Permission not yet granted: " + perm);
            out.put("hint", hint);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void putError(JSONObject out, String msg) {
        try {
            out.put("ok", false);
            out.put("error", msg != null ? msg : "unknown error");
        } catch (Exception ignored) {
        }
    }

    private static final class ContactRecord {
        final String id;
        final String name;
        final List<String> phones = new ArrayList<>();
        final List<String> emails = new ArrayList<>();
        String company = "";
        String jobTitle = "";

        ContactRecord(String id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean matches(String q) {
            if (name.toLowerCase(Locale.US).contains(q)) return true;
            if (company.toLowerCase(Locale.US).contains(q)) return true;
            if (jobTitle.toLowerCase(Locale.US).contains(q)) return true;
            for (String p : phones) {
                if (p.toLowerCase(Locale.US).contains(q)) return true;
            }
            for (String e : emails) {
                if (e.toLowerCase(Locale.US).contains(q)) return true;
            }
            return false;
        }

        JSONObject toJson() throws Exception {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            if (!phones.isEmpty()) obj.put("phones", new JSONArray(phones));
            if (!emails.isEmpty()) obj.put("emails", new JSONArray(emails));
            if (!company.isEmpty()) obj.put("company", company);
            if (!jobTitle.isEmpty()) obj.put("job_title", jobTitle);
            return obj;
        }
    }
}
