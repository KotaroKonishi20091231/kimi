package com.kimi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KIMI のページ(claude.ai)を表示し、ページからは window.KimiNative として
 * 端末の読み上げと音声認識(「きみ」の呼びかけ)を使えるようにする。
 * ページは別オリジンの枠の中で動くので、ネイティブからは呼び出さず、
 * ページが poll() で出来事を取りに来る形にしている。
 */
public class MainActivity extends Activity {
    private static final String START_URL = "https://claude.ai/artifact/DkCrGBvkhkdMQ2TU8aZNQB";
    private static final int REQ_MIC = 1;
    private static final Pattern WAKE = Pattern.compile(
            "(?:ねえ|ねぇ|ねー|おい|ヘイ|hey)?[、,。\\s]*(?:きみ|キミ|君|黄身|気味|kimi|キーミ)(?:ちゃん|さん|くん)?[、,。!！?？\\s]*",
            Pattern.CASE_INSENSITIVE);

    /** claude.ai の上のバーを隠して KIMI を全画面にするスクリプト（assets/fullscreen.js） */
    private String fullscreenJs = null;

    private String fullscreenJs() {
        if (fullscreenJs != null) return fullscreenJs;
        try (InputStream in = getAssets().open("fullscreen.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            fullscreenJs = out.toString("UTF-8");
        } catch (Exception e) {
            fullscreenJs = "";
        }
        return fullscreenJs;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> events = new ArrayDeque<>();

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean speaking = false;
    private int utteranceSeq = 0;

    private SpeechRecognizer recognizer;
    private boolean wakeOn = false;
    // off: 聞いていない / waiting: 「きみ」待ち / command: 用件を聞いている / paused: 考え中・話し中
    private String mode = "off";
    private boolean resumed = false;
    private Runnable afterPermission = null;

    private final Runnable restart = () -> {
        if (("waiting".equals(mode) || "command".equals(mode)) && !ttsBusy()) startListening();
    };

    private boolean ttsBusy() {
        try { return tts != null && tts.isSpeaking(); } catch (Exception e) { return false; }
    }

    /** 「きみ」が文のはじめのほう（6文字以内）にあれば、その一致を返す */
    private static Matcher wakeMatch(String t) {
        Matcher m = WAKE.matcher(t);
        return (m.find() && m.start() <= 6) ? m : null;
    }

    private void setMode(String m) {
        mode = m;
        emitState();
    }

    /** ページに今の状態を知らせる（画面の表示をスマホ側の状態と必ずそろえる） */
    private void emitState() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "state");
            o.put("text", mode);
            o.put("wake", wakeOn);
            synchronized (events) { events.add(o.toString()); }
        } catch (Exception ignored) { }
    }
    private final Runnable commandTimeout = () -> {
        if ("command".equals(mode)) {
            setMode(wakeOn ? "waiting" : "off");
            emit("idle", null);
            if (!wakeOn) stopRecognizer();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#05070D"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "KimiNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    openExternal(uri);
                    return true;
                }
                if (host.equals("accounts.google.com")) {
                    toast("アプリの中では Google でのログインが使えません。メールアドレスでログインしてください。");
                    return true;
                }
                if (!request.isForMainFrame()) return false;
                if (host.endsWith("claude.ai") || host.endsWith("anthropic.com") || host.endsWith("claudeusercontent.com")) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // ログインが終わると claude.ai のトップに戻るので、KIMI の画面へ移す
                Uri u = Uri.parse(url);
                String path = u.getPath() == null ? "/" : u.getPath();
                if ("claude.ai".equals(u.getHost()) && (path.equals("/") || path.equals("/new") || path.startsWith("/recents"))) {
                    view.loadUrl(START_URL);
                    return;
                }
                if ("claude.ai".equals(u.getHost())) view.evaluateJavascript(fullscreenJs(), null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> request.deny());
            }
        });

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.JAPAN);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { speaking = true; }
            @Override public void onDone(String id) { finishedSpeaking(id); }
            @Override public void onError(String id) { finishedSpeaking(id); }
            @Override public void onStop(String id, boolean interrupted) { finishedSpeaking(id); }
        });

        // 「きみ」の待ち受けは、前回オフにしていなければ起動したときから始める
        if (getSharedPreferences("kimi", MODE_PRIVATE).getBoolean("wake", true)) {
            main.postDelayed(() -> doSetWake(true), 1200);
        }

        Uri data = getIntent() != null ? getIntent().getData() : null;
        web.loadUrl(data != null ? data.toString() : START_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) web.loadUrl(intent.getData().toString());
    }

    // ---------- ページから呼ばれる窓口 ----------
    private class Bridge {
        @JavascriptInterface public boolean available() { return true; }
        @JavascriptInterface public void speak(String text, float rate) { main.post(() -> doSpeak(text, rate)); }
        @JavascriptInterface public void stopSpeaking() { main.post(() -> { tts.stop(); speaking = false; }); }
        @JavascriptInterface public boolean isSpeaking() { return speaking; }
        @JavascriptInterface public void setWake(boolean on) { main.post(() -> doSetWake(on)); }
        @JavascriptInterface public boolean isWakeOn() { return wakeOn; }
        @JavascriptInterface public String getMode() { return mode; }
        @JavascriptInterface public void listenOnce() { main.post(MainActivity.this::doListenOnce); }
        @JavascriptInterface public void pauseWake() { main.post(MainActivity.this::doPause); }
        @JavascriptInterface public void resumeWake() { main.post(MainActivity.this::doResume); }
        /** 返事のあと、「きみ」と呼ばなくても続けて話せるように少しのあいだ聞き取る */
        @JavascriptInterface public void followUp() { main.post(MainActivity.this::doFollowUp); }
        /** インターネットで調べる。結果は poll() に {type:"net", id, text} として届く */
        @JavascriptInterface public void fetch(String id, String kind, String arg) {
            net.execute(() -> {
                String out;
                try { out = Web.run(kind, arg); }
                catch (Exception e) { out = "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                emitNet(id, out);
            });
        }
        /** スマホの操作（リマインダー・電話・メッセージ・アプリ・地図）。結果は poll() に {type:"net", id, text} として届く */
        @JavascriptInterface public void act(String id, String kind, String argsJson) {
            main.post(() -> {
                JSONObject args;
                try { args = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson); }
                catch (Exception e) { emitNet(id, "ERROR: 引数が読み取れません"); return; }
                try { Actions.run(MainActivity.this, kind, args, text -> emitNet(id, text)); }
                catch (Exception e) { emitNet(id, "ERROR: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
            });
        }
        @JavascriptInterface public String poll() {
            synchronized (events) { return events.isEmpty() ? "" : events.poll(); }
        }
    }

    private final ExecutorService net = Executors.newFixedThreadPool(3);

    private void emitNet(String id, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "net");
            o.put("id", id);
            o.put("text", text);
            synchronized (events) { events.add(o.toString()); }
        } catch (Exception ignored) { }
    }

    private void emit(String type, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            if (text != null) o.put("text", text);
            synchronized (events) {
                events.add(o.toString());
                while (events.size() > 50) events.poll();
            }
        } catch (Exception ignored) { }
    }

    // ---------- 読み上げ ----------
    private void doSpeak(String text, float rate) {
        stopRecognizer(); // 自分の声を聞き取らないように、話している間は止める
        tts.setSpeechRate(rate > 0 ? rate : 1.0f);
        String id = "kimi" + (++utteranceSeq);
        speaking = true;
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        if (r != TextToSpeech.SUCCESS) { // 読み上げを始められなかったら、すぐに終わったことにする
            speaking = false;
            emit("spoken", null);
        }
    }

    private void finishedSpeaking(String id) {
        if (!("kimi" + utteranceSeq).equals(id)) return;
        speaking = false;
        emit("spoken", null);
    }

    // ---------- 音声認識 ----------
    private boolean ensureMic(Runnable then) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        afterPermission = then;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        return false;
    }

    // 連絡先・通知など、マイク以外の許可
    private static final int REQ_OTHER = 2;
    private Runnable otherGranted = null, otherDenied = null;

    void withPermission(String permission, Runnable granted, Runnable denied) {
        if (permission == null || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { granted.run(); return; }
        if (otherDenied != null) otherDenied.run(); // 前の確認が残っていたら、そちらは諦める
        otherGranted = granted;
        otherDenied = denied;
        requestPermissions(new String[]{permission}, REQ_OTHER);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_OTHER) {
            Runnable g = otherGranted, d = otherDenied;
            otherGranted = otherDenied = null;
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Runnable r = ok ? g : d;
            if (r != null) r.run();
            return;
        }
        if (requestCode != REQ_MIC) return;
        Runnable then = afterPermission;
        afterPermission = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (then != null) then.run();
        } else {
            wakeOn = false;
            getSharedPreferences("kimi", MODE_PRIVATE).edit().putBoolean("wake", false).apply();
            setMode("off");
            emit("wakeoff", null);
            emit("error", "マイクの使用が許可されませんでした。スマホの設定 → アプリ → KIMI → 権限 から、マイクを許可してください。");
        }
    }

    private void doSetWake(boolean on) {
        if (on && !ensureMic(() -> doSetWake(true))) return;
        boolean changed = wakeOn != on;
        wakeOn = on;
        getSharedPreferences("kimi", MODE_PRIVATE).edit().putBoolean("wake", on).apply();
        if (changed) toast(on ? "「きみ」の待ち受けを始めました" : "「きみ」の待ち受けを止めました");
        emitState();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if ("off".equals(mode)) {
                setMode("waiting");
                startListening();
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if ("waiting".equals(mode)) {
                setMode("off");
                stopRecognizer();
            }
        }
    }

    private void doListenOnce() {
        if (!ensureMic(this::doListenOnce)) return;
        tts.stop();
        speaking = false;
        enterCommand();
        stopRecognizer();
        startListening();
    }

    private void doPause() {
        main.removeCallbacks(commandTimeout);
        setMode("paused");
        stopRecognizer();
    }

    private void doFollowUp() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { doResume(); return; }
        setMode("command");
        softBeep();
        main.removeCallbacks(commandTimeout);
        main.postDelayed(commandTimeout, 7000);
        stopRecognizer();
        startListening();
    }

    private void doResume() {
        setMode(wakeOn ? "waiting" : "off");
        if (wakeOn) startListening();
    }

    private void enterCommand() {
        setMode("command");
        beep();
        emit("wake", null);
        main.removeCallbacks(commandTimeout);
        main.postDelayed(commandTimeout, 8000);
    }

    private void startListening() {
        if (!resumed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            emit("error", "この端末では音声認識が使えません。Google アプリが入っているか確認してください。");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            restartSoon(800);
        }
    }

    private void stopRecognizer() {
        main.removeCallbacks(restart);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
    }

    private void restartSoon(long ms) {
        main.removeCallbacks(restart);
        main.postDelayed(restart, ms);
    }

    private void handleCommand(String text) {
        main.removeCallbacks(commandTimeout);
        setMode("paused");
        stopRecognizer();
        emit("command", text);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) { restartSoon(200); return; }
            if ("waiting".equals(mode)) {
                for (String t : list) {
                    Matcher m = wakeMatch(t);
                    if (m != null) {
                        String rest = t.substring(m.end()).trim();
                        if (rest.length() >= 2) { handleCommand(rest); return; }
                        enterCommand();
                        restartSoon(150);
                        return;
                    }
                }
                restartSoon(150);
            } else if ("command".equals(mode)) {
                String t = list.get(0);
                Matcher m = wakeMatch(t);
                String cleaned = m != null ? t.substring(m.end()).trim() : t.trim();
                if (cleaned.isEmpty()) { restartSoon(150); return; }
                handleCommand(cleaned);
            }
        }

        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            String t = list.get(0);
            if ("command".equals(mode) || ("waiting".equals(mode) && wakeMatch(t) != null)) emit("heard", t);
        }

        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wakeOn = false;
                setMode("off");
                emit("wakeoff", null);
                emit("error", "マイクの使用が許可されていません。");
                return;
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                try { recognizer.destroy(); } catch (Exception ignored) { }
                recognizer = null;
                restartSoon(700);
                return;
            }
            restartSoon(300);
        }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }
    };

    /** 続けて話せる合図の、小さく短い音 */
    private void softBeep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90);
            main.postDelayed(tone::release, 300);
        } catch (Exception ignored) { }
    }

    private void beep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180);
            main.postDelayed(tone::release, 400);
        } catch (Exception ignored) { }
    }

    // ---------- 画面の出入り ----------
    /** 開いている KIMI（リマインダーの時刻に読み上げるため） */
    static volatile MainActivity current = null;

    /** KIMI を開いているときにリマインダーの時刻が来たら、通知に加えて読み上げる */
    void announce(String text) {
        main.post(() -> {
            if (!resumed) return;
            stopRecognizer();
            doSpeak("リマインダーです。" + text, 1.0f);
            emit("reminder", text);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        web.onResume();
        if ("waiting".equals(mode) || "command".equals(mode)) startListening();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (current == this) current = null;
        stopRecognizer();
        web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) openCopiedLoginLink();
    }

    /** メールのログイン用リンクをコピーしてからアプリに戻ると、そのリンクをアプリ内で開く */
    private void openCopiedLoginLink() {
        try {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cb == null ? null : cb.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return;
            String link = clip.getItemAt(0).getText().toString().trim();
            if (!link.startsWith("https://claude.ai/") || link.startsWith(START_URL)) return;
            SharedPreferences prefs = getSharedPreferences("kimi", MODE_PRIVATE);
            if (link.equals(prefs.getString("lastLink", ""))) return;
            prefs.edit().putString("lastLink", link).apply();
            web.loadUrl(link);
            toast("コピーしたリンクを開きました");
        } catch (Exception ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        web.destroy();
        super.onDestroy();
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
