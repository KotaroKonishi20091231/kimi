package com.kimi.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.app.Notification;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * リマインダー・タイマー。時刻になると通知を出す（KIMI を閉じていても届く）。
 * 予定は端末に保存し、スマホを再起動したときにも入れ直す。
 */
final class Reminders {
    static final String CHANNEL = "kimi_reminders";
    private static final String PREFS = "kimi_reminders";

    static void schedule(Context c, String id, long when, String text) {
        JSONArray all = load(c);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && !id.equals(r.optString("id"))) kept.put(r);
        }
        try {
            JSONObject r = new JSONObject();
            r.put("id", id);
            r.put("when", when);
            r.put("message", text);
            kept.put(r);
        } catch (Exception ignored) { }
        save(c, kept);
        arm(c, id, when, text);
    }

    static boolean cancel(Context c, String id) {
        JSONArray all = load(c);
        JSONArray kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null) continue;
            if (id.equals(r.optString("id"))) found = true; else kept.put(r);
        }
        save(c, kept);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c, id, ""));
        return found;
    }

    /** これから鳴るものだけを、時刻の早い順に返す */
    static JSONArray list(Context c) {
        JSONArray all = load(c);
        java.util.List<JSONObject> items = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && r.optLong("when") > now) items.add(r);
        }
        items.sort((x, y) -> Long.compare(x.optLong("when"), y.optLong("when")));
        JSONArray out = new JSONArray();
        for (JSONObject r : items) out.put(r);
        return out;
    }

    static void rearmAll(Context c) {
        JSONArray all = list(c);
        save(c, all); // 過ぎたものは捨てる
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            arm(c, r.optString("id"), r.optLong("when"), r.optString("message"));
        }
    }

    private static void arm(Context c, String id, long when, String text) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c, id, text);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private static PendingIntent pending(Context c, String id, String text) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction("com.kimi.app.REMIND." + id)
                .putExtra("id", id).putExtra("message", text);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void fire(Context c, String id, String text) {
        cancelStored(c, id);
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("KIMI のリマインダー")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(id.hashCode(), n); } catch (SecurityException ignored) { }
        MainActivity open2 = MainActivity.current;
        if (open2 != null) open2.announce(text);
    }

    private static void cancelStored(Context c, String id) {
        JSONArray all = load(c);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r != null && !id.equals(r.optString("id"))) kept.put(r);
        }
        save(c, kept);
    }

    private static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "リマインダー", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("KIMI に頼んだリマインダーとタイマー");
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    private static JSONArray load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try { return new JSONArray(p.getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static void save(Context c, JSONArray items) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", items.toString()).apply();
    }

    /** 時刻が来たとき・スマホを再起動したときに呼ばれる */
    public static class ReminderReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction() == null ? "" : intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED) || action.equals("android.intent.action.MY_PACKAGE_REPLACED")) {
                rearmAll(c);
                return;
            }
            String id = intent.getStringExtra("id");
            String text = intent.getStringExtra("message");
            if (id != null && text != null) fire(c, id, text);
        }
    }
}
