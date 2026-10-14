package com.kimi.app;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.TimeZone;

/**
 * スマホのカレンダー（Google アカウントと同期しているもの）を読み書きする。
 * ここに書いた予定は、Google カレンダーにも同期される。
 */
final class PhoneCalendar {

    /** 書き込み先: Google アカウントのメインのカレンダー。なければ書き込めるカレンダーのうち最初のもの */
    static long writableCalendarId(Context c) {
        String[] cols = {CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        long fallback = -1;
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, cols,
                CalendarContract.Calendars.VISIBLE + "=1", null, null)) {
            if (cur == null) return -1;
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                String type = cur.getString(1);
                boolean primary = cur.getInt(2) == 1;
                int access = cur.getInt(3);
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                if ("com.google".equals(type) && primary) return id;
                if (fallback < 0 || "com.google".equals(type)) fallback = id;
            }
        }
        return fallback;
    }

    /** start/end はミリ秒。allDay なら start は その日の 0 時（端末の時間帯） */
    static String add(Context c, JSONObject a) throws Exception {
        long calId = writableCalendarId(c);
        if (calId < 0) return "ERROR: 書き込めるカレンダーがありません。スマホに Google アカウントを追加して、カレンダーの同期をオンにしてください";
        boolean allDay = a.optBoolean("allDay");
        long start = a.getLong("start");
        long end = a.optLong("end", 0);
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, a.optString("title"));
        if (!a.optString("location").isEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, a.optString("location"));
        if (!a.optString("note").isEmpty()) v.put(CalendarContract.Events.DESCRIPTION, a.optString("note"));
        if (allDay) {
            // 終日の予定は UTC の 0 時で表す決まり
            java.util.Calendar local = java.util.Calendar.getInstance();
            local.setTimeInMillis(start);
            java.util.Calendar utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.clear();
            utc.set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH));
            v.put(CalendarContract.Events.DTSTART, utc.getTimeInMillis());
            v.put(CalendarContract.Events.DTEND, utc.getTimeInMillis() + 86_400_000L);
            v.put(CalendarContract.Events.ALL_DAY, 1);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, end > start ? end : start + 3_600_000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        }
        Uri uri = c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
        if (uri == null) return "ERROR: カレンダーに書き込めませんでした";
        long eventId = ContentUris.parseId(uri);
        int remind = a.optInt("remindMinutes", -1);
        if (remind >= 0) {
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.MINUTES, remind);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            try { c.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r); } catch (Exception ignored) { }
        }
        JSONObject o = new JSONObject();
        o.put("id", String.valueOf(eventId));
        return o.toString();
    }

    /** from〜to（ミリ秒）にある予定。繰り返しの予定も1回ずつ展開して返す */
    static String list(Context c, long from, long to) throws Exception {
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] cols = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME};
        JSONArray out = new JSONArray();
        try (Cursor cur = c.getContentResolver().query(b.build(), cols, CalendarContract.Instances.VISIBLE + "=1", null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cur == null) return out.toString();
            while (cur.moveToNext() && out.length() < 60) {
                JSONObject o = new JSONObject();
                o.put("id", String.valueOf(cur.getLong(0)));
                o.put("title", cur.getString(1) == null ? "（無題）" : cur.getString(1));
                o.put("begin", cur.getLong(2));
                o.put("end", cur.getLong(3));
                o.put("allDay", cur.getInt(4) == 1);
                if (cur.getString(5) != null && !cur.getString(5).isEmpty()) o.put("location", cur.getString(5));
                o.put("calendar", cur.getString(6));
                out.put(o);
            }
        }
        return out.toString();
    }

    static String delete(Context c, String id) {
        try {
            int n = c.getContentResolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, Long.parseLong(id)), null, null);
            return n > 0 ? "OK" : "ERROR: その予定は見つかりませんでした";
        } catch (Exception e) {
            return "ERROR: その予定は消せませんでした（読み取り専用のカレンダーかもしれません）";
        }
    }
}
