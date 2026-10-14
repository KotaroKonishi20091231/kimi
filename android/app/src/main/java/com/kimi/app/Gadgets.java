package com.kimi.app;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.provider.MediaStore;

import org.json.JSONObject;

/** 音楽・ライト・音量・Google Keep など、そのほかのアプリやスマホの機能 */
final class Gadgets {

    static String music(MainActivity a, JSONObject args) {
        String q = args.optString("query").trim();
        String app = args.optString("app");
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, q)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        if ("spotify".equals(app)) i.setPackage("com.spotify.music");
        else if ("youtube_music".equals(app)) i.setPackage("com.google.android.apps.youtube.music");
        try {
            a.startActivity(i);
            return "OK: 「" + q + "」を再生します";
        } catch (ActivityNotFoundException e) {
            // 音楽アプリが対応していなければ、YouTube で探す
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/search?q=" + android.net.Uri.encode(q))));
                return "OK: YouTube Music で「" + q + "」を探しました。再生ボタンを押してください";
            } catch (ActivityNotFoundException e2) {
                return "ERROR: 音楽を再生できるアプリが見つかりませんでした";
            }
        }
    }

    private static boolean torchOn = false;

    static String torch(Context c, JSONObject args) {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    boolean on = args.has("on") ? args.optBoolean("on") : !torchOn;
                    cm.setTorchMode(id, on);
                    torchOn = on;
                    return on ? "OK: ライトをつけました" : "OK: ライトを消しました";
                }
            }
            return "ERROR: このスマホにはライトがありません";
        } catch (Exception e) {
            return "ERROR: ライトを操作できませんでした（カメラを使っているアプリがあるかもしれません）";
        }
    }

    static String volume(Context c, JSONObject args) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        String action = args.optString("action", "set");
        int next;
        switch (action) {
            case "up": next = Math.min(max, cur + Math.max(1, max / 5)); break;
            case "down": next = Math.max(0, cur - Math.max(1, max / 5)); break;
            case "mute": next = 0; break;
            case "max": next = max; break;
            default:
                int percent = Math.max(0, Math.min(100, args.optInt("percent", 50)));
                next = Math.round(max * percent / 100f);
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI);
        return "OK: 音量を " + Math.round(next * 100f / max) + "% にしました";
    }

    static String keep(MainActivity a, JSONObject args) {
        String text = args.optString("text");
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage("com.google.android.keep");
        if (!args.optString("title").isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, args.optString("title"));
        try {
            a.startActivity(i);
            return "OK: Google Keep を開きました。内容を確かめて、戻るボタンで保存してください";
        } catch (ActivityNotFoundException e) {
            return "ERROR: Google Keep が入っていないようです";
        }
    }
}
