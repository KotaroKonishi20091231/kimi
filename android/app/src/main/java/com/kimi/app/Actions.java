package com.kimi.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KIMI がスマホを操作する窓口。電話やメッセージは「画面を開くところまで」で、
 * 最後の発信・送信ボタンは必ず本人が押す。
 */
final class Actions {
    interface Done { void done(String text); }

    private static final String LINE_PACKAGE = "jp.naver.line.android";

    static void run(MainActivity a, String kind, JSONObject args, Done done) throws Exception {
        switch (kind) {
            case "reminder_set": reminderSet(a, args, done); return;
            case "reminder_list": done.done(Reminders.list(a).toString()); return;
            case "reminder_cancel": done.done(Reminders.cancel(a, args.optString("id")) ? "取り消しました" : "ERROR: そのリマインダーは見つかりません"); return;
            case "alarm_set": alarmSet(a, args, done); return;
            case "timer_set": timerSet(a, args, done); return;
            case "vision": Vision.read(args.optString("data"), done); return;
            case "alarm_show": done.done(start(a, new Intent(AlarmClock.ACTION_SHOW_ALARMS), "時計アプリのアラーム一覧を開きました")); return;
            case "open_app": done.done(openApp(a, args.optString("name"))); return;
            case "open_map": done.done(openMap(a, args.optString("destination"), args.optString("mode"))); return;
            case "youtube": done.done(open(a, "https://www.youtube.com/results?search_query=" + Uri.encode(args.optString("query")), "YouTube で「" + args.optString("query") + "」を開きました")); return;
            case "call": call(a, args.optString("to"), done); return;
            case "message": message(a, args.optString("to"), args.optString("text"), args.optString("app", "sms"), done); return;
            default: done.done("ERROR: unknown action " + kind);
        }
    }

    // ---------- リマインダー ----------
    private static void reminderSet(MainActivity a, JSONObject args, Done done) {
        long when = args.optLong("when");
        String text = args.optString("message").trim();
        String id = args.optString("id");
        if (text.isEmpty()) { done.done("ERROR: 知らせる内容が空です"); return; }
        if (when < System.currentTimeMillis() + 5_000) { done.done("ERROR: その時刻はもう過ぎています"); return; }
        Runnable schedule = () -> {
            Reminders.schedule(a, id, when, text);
            done.done("OK");
        };
        if (Build.VERSION.SDK_INT >= 33) {
            a.withPermission(Manifest.permission.POST_NOTIFICATIONS, schedule,
                    () -> done.done("ERROR: 通知が許可されていないので、時間になっても知らせられません。スマホの設定 → アプリ → KIMI → 通知 をオンにしてください"));
        } else {
            schedule.run();
        }
    }

    // ---------- 時計アプリのアラーム・タイマー ----------
    private static void alarmSet(MainActivity a, JSONObject args, Done done) {
        int hour = args.optInt("hour", -1), minute = args.optInt("minute", 0);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) { done.done("ERROR: 時刻が正しくありません"); return; }
        String msg = args.optString("message").trim();
        // KIMI の画面から離れずに済むよう、時計アプリの画面は出さずに設定する
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!msg.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, msg);
        ArrayList<Integer> list = new ArrayList<>();
        JSONArray days = args.optJSONArray("days");
        if (days != null) {
            for (int d = 0; d < days.length(); d++) {
                int day = days.optInt(d); // 1=日曜 … 7=土曜（Calendar と同じ）
                if (day >= 1 && day <= 7 && !list.contains(day)) list.add(day);
            }
        }
        if (!list.isEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, list);
        String r = start(a, i, "OK");
        if (r.startsWith("ERROR")) { done.done(r); return; }
        a.bringBack(1500); // 端末によっては時計アプリが開くので、KIMI に戻ってくる

        // 鳴る時刻に、KIMI からも用件を知らせる（繰り返しのアラームは毎回）
        String what = msg.isEmpty() ? "アラーム" : msg;
        long when = Reminders.nextOccurrence(hour, minute, list);
        String head = String.format(Locale.JAPAN, "OK: 時計アプリに %d時%02d分のアラームを設定しました", hour, minute);
        Runnable schedule = () -> {
            Reminders.schedule(a, "alarm-" + hour + "-" + minute, when, "アラーム: " + what, list);
            done.done(head + "。鳴るときに用件「" + what + "」も知らせます");
        };
        if (Build.VERSION.SDK_INT >= 33) {
            a.withPermission(android.Manifest.permission.POST_NOTIFICATIONS, schedule,
                    () -> done.done(head + "。通知が許可されていないので、用件のお知らせはできません"));
        } else {
            schedule.run();
        }
    }

    private static void timerSet(MainActivity a, JSONObject args, Done done) {
        int seconds = args.optInt("seconds", 0);
        if (seconds <= 0 || seconds > 24 * 3600) { done.done("ERROR: タイマーの長さが正しくありません"); return; }
        String msg = args.optString("message").trim();
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!msg.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, msg);
        String r = start(a, i, "OK");
        if (r.startsWith("ERROR")) { done.done(r); return; }
        a.bringBack(1500);
        if (!msg.isEmpty()) {
            Runnable schedule = () -> Reminders.schedule(a, "timer-" + System.currentTimeMillis(), System.currentTimeMillis() + seconds * 1000L, "タイマー: " + msg, new ArrayList<>());
            if (Build.VERSION.SDK_INT >= 33) a.withPermission(android.Manifest.permission.POST_NOTIFICATIONS, schedule, () -> { });
            else schedule.run();
        }
        done.done("OK: 時計アプリのタイマーを始めました" + (msg.isEmpty() ? "" : "。終わったら用件「" + msg + "」も知らせます"));
    }

    private static String start(MainActivity a, Intent i, String ok) {
        try {
            a.startActivity(i);
            return ok;
        } catch (ActivityNotFoundException e) {
            return "ERROR: 時計アプリが見つかりませんでした";
        } catch (SecurityException e) {
            return "ERROR: 時計アプリを操作する許可がありません";
        }
    }

    // ---------- アプリ・地図 ----------
    private static final Map<String, String> APP_ALIASES = new LinkedHashMap<>();
    static {
        APP_ALIASES.put("ユーチューブ", "youtube");
        APP_ALIASES.put("ライン", "line");
        APP_ALIASES.put("インスタ", "instagram");
        APP_ALIASES.put("インスタグラム", "instagram");
        APP_ALIASES.put("ツイッター", "x");
        APP_ALIASES.put("エックス", "x");
        APP_ALIASES.put("グーグルマップ", "マップ");
        APP_ALIASES.put("地図", "マップ");
        APP_ALIASES.put("カメラ", "カメラ");
        APP_ALIASES.put("ティックトック", "tiktok");
        APP_ALIASES.put("スポティファイ", "spotify");
        APP_ALIASES.put("ジーメール", "gmail");
        APP_ALIASES.put("クローム", "chrome");
        APP_ALIASES.put("電卓", "電卓");
        APP_ALIASES.put("時計", "時計");
    }

    private static String openApp(MainActivity a, String name) {
        String want = name.trim().toLowerCase(Locale.ROOT).replaceAll("(アプリ|を開いて|開いて)$", "").trim();
        if (want.isEmpty()) return "ERROR: アプリの名前が空です";
        String alias = APP_ALIASES.get(want);
        if (alias != null) want = alias.toLowerCase(Locale.ROOT);

        PackageManager pm = a.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = label.equals(want) ? 3 : label.contains(want) ? 2 : pkg.contains(want) ? 1 : 0;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        if (best == null) return "ERROR: 「" + name + "」というアプリが見つかりませんでした";
        Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (i == null) return "ERROR: そのアプリは開けませんでした";
        a.startActivity(i);
        return "「" + best.loadLabel(pm) + "」を開きました";
    }

    private static String openMap(MainActivity a, String destination, String mode) {
        if (destination.trim().isEmpty()) return "ERROR: 行き先が空です";
        String url;
        String travel;
        switch (mode) {
            case "walking": travel = "walking"; break;
            case "driving": travel = "driving"; break;
            case "bicycling": travel = "bicycling"; break;
            case "search": travel = null; break;
            default: travel = "transit";
        }
        if (travel == null) url = "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(destination);
        else url = "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination) + "&travelmode=" + travel;
        return open(a, url, travel == null ? "地図で「" + destination + "」を開きました" : "「" + destination + "」までの道案内を開きました");
    }

    private static String open(MainActivity a, String url, String ok) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return ok;
        } catch (ActivityNotFoundException e) {
            return "ERROR: 開けるアプリがありませんでした";
        }
    }

    // ---------- 電話・メッセージ ----------
    private static void call(MainActivity a, String to, Done done) {
        if (to.replaceAll("[\\s-]", "").matches("\\+?\\d{3,}")) { dial(a, to, to, done); return; }
        a.withPermission(Manifest.permission.READ_CONTACTS, () -> {
            try {
                List<String[]> found = findContacts(a, to, false);
                String pick = pickOne(found, to);
                if (pick != null) { done.done(pick); return; }
                String[] c = found.get(0);
                dial(a, c[0], c[1], done);
            } catch (Exception e) { done.done("ERROR: 連絡先を調べられませんでした"); }
        }, () -> done.done("ERROR: 連絡先を見る許可がないので、名前から電話番号を探せません。スマホの設定 → アプリ → KIMI → 権限 で連絡先を許可してください"));
    }

    private static void dial(MainActivity a, String name, String number, Done done) {
        try {
            a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))));
            done.done("OK: " + name + "（" + number + "）の電話の画面を開きました。発信ボタンを押すと電話がかかります");
        } catch (ActivityNotFoundException e) { done.done("ERROR: 電話アプリが見つかりませんでした"); }
    }

    private static void message(MainActivity a, String to, String text, String app, Done done) {
        if ("line".equals(app)) {
            // LINE は相手を指定して開けないので、文章を入れた状態で送り先を選ぶ画面を開く
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(LINE_PACKAGE);
            try {
                a.startActivity(i);
                done.done("OK: LINE を開きました。送り先に" + (to.isEmpty() ? "" : "「" + to + "」を") + "選んで送信してください");
            } catch (ActivityNotFoundException e) { done.done("ERROR: LINE が入っていないようです"); }
            return;
        }
        boolean email = "email".equals(app);
        if (!email && to.replaceAll("[\\s-]", "").matches("\\+?\\d{3,}")) { sms(a, to, to, text, done); return; }
        a.withPermission(Manifest.permission.READ_CONTACTS, () -> {
            try {
                List<String[]> found = findContacts(a, to, email);
                String pick = pickOne(found, to);
                if (pick != null) { done.done(pick); return; }
                String[] c = found.get(0);
                if (email) {
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c[1])).putExtra(Intent.EXTRA_TEXT, text);
                    a.startActivity(i);
                    done.done("OK: " + c[0] + " さんへのメールの画面を開きました。送信ボタンを押してください");
                } else {
                    sms(a, c[0], c[1], text, done);
                }
            } catch (ActivityNotFoundException e) {
                done.done("ERROR: 送信に使えるアプリが見つかりませんでした");
            } catch (Exception e) { done.done("ERROR: 連絡先を調べられませんでした"); }
        }, () -> done.done("ERROR: 連絡先を見る許可がないので、名前から送り先を探せません。スマホの設定 → アプリ → KIMI → 権限 で連絡先を許可してください"));
    }

    private static void sms(MainActivity a, String name, String number, String text, Done done) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text);
            a.startActivity(i);
            done.done("OK: " + name + " さんへのメッセージの画面を開きました。送信ボタンを押してください");
        } catch (ActivityNotFoundException e) { done.done("ERROR: メッセージアプリが見つかりませんでした"); }
    }

    /** 見つからない・候補が複数あるときは、KIMI が聞き返せるように文章を返す。1人に決まれば null */
    private static String pickOne(List<String[]> found, String to) throws Exception {
        if (found.isEmpty()) return "ERROR: 連絡先に「" + to + "」が見つかりませんでした";
        List<String> names = new ArrayList<>();
        for (String[] c : found) if (!names.contains(c[0])) names.add(c[0]);
        if (names.size() == 1) return null;
        for (String n : names) if (n.equals(to)) { found.removeIf(c -> !c[0].equals(n)); return null; }
        JSONObject o = new JSONObject();
        o.put("候補が複数あります。どの人か聞き返してください", new JSONArray(names.subList(0, Math.min(names.size(), 6))));
        return o.toString();
    }

    private static final Map<String, String[]> FAMILY = new LinkedHashMap<>();
    static {
        FAMILY.put("お母さん", new String[]{"お母さん", "母", "ママ", "おかあさん", "かあさん"});
        FAMILY.put("お父さん", new String[]{"お父さん", "父", "パパ", "おとうさん", "とうさん"});
        FAMILY.put("おばあちゃん", new String[]{"おばあちゃん", "祖母", "ばあちゃん"});
        FAMILY.put("おじいちゃん", new String[]{"おじいちゃん", "祖父", "じいちゃん"});
    }

    /** 名前の一部で連絡先を探す。[名前, 電話番号 or メール] のリスト */
    private static List<String[]> findContacts(MainActivity a, String name, boolean email) {
        String q = name.trim().replaceAll("(さん|くん|ちゃん|様)$", "");
        String[] words = FAMILY.containsKey(name.trim()) ? FAMILY.get(name.trim()) : new String[]{q};
        List<String[]> out = new ArrayList<>();
        Uri uri = email ? ContactsContract.CommonDataKinds.Email.CONTENT_URI : ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String nameCol = email ? ContactsContract.CommonDataKinds.Email.DISPLAY_NAME : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME;
        String dataCol = email ? ContactsContract.CommonDataKinds.Email.ADDRESS : ContactsContract.CommonDataKinds.Phone.NUMBER;
        for (String w : words) {
            if (w.isEmpty()) continue;
            try (Cursor c = a.getContentResolver().query(uri, new String[]{nameCol, dataCol}, nameCol + " LIKE ?", new String[]{"%" + w + "%"}, nameCol)) {
                if (c == null) continue;
                while (c.moveToNext() && out.size() < 20) {
                    String n = c.getString(0), d = c.getString(1);
                    if (n == null || d == null) continue;
                    boolean dup = false;
                    for (String[] e : out) if (e[0].equals(n) && e[1].equals(d)) dup = true;
                    if (!dup) out.add(new String[]{n, d});
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }
}
