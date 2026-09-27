package com.kimi.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** KIMI の「調べる」機能。キーのいらない公開サービスだけを使う。 */
final class Web {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";
    private static final int FLAGS = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;

    static String run(String kind, String arg) throws Exception {
        String a = arg == null ? "" : arg.trim();
        switch (kind) {
            case "search": return search(a);
            case "page": return page(a);
            case "weather": return weather(a);
            case "news": return news(a);
            default: throw new Exception("unknown kind: " + kind);
        }
    }

    // ---------- 検索 ----------
    static String search(String q) throws Exception {
        if (q.isEmpty()) throw new Exception("検索する言葉が空です");
        List<JSONObject> results = new ArrayList<>();
        try { results = duckduckgo(q); } catch (Exception ignored) { }
        if (results.isEmpty()) {
            try { results = bing(q); } catch (Exception ignored) { }
        }
        if (results.isEmpty()) throw new Exception("検索結果を取得できませんでした");
        JSONArray arr = new JSONArray();
        for (JSONObject r : results) { if (arr.length() >= 6) break; arr.put(r); }
        return arr.toString();
    }

    private static List<JSONObject> duckduckgo(String q) throws Exception {
        String html = get("https://html.duckduckgo.com/html/?kl=jp-jp&q=" + enc(q));
        List<JSONObject> out = new ArrayList<>();
        Matcher titles = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", FLAGS).matcher(html);
        Matcher snippets = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", FLAGS).matcher(html);
        List<String> snips = new ArrayList<>();
        while (snippets.find()) snips.add(clean(snippets.group(1)));
        int i = 0;
        while (titles.find()) {
            String href = unescape(titles.group(1));
            Matcher u = Pattern.compile("[?&]uddg=([^&]+)").matcher(href);
            if (u.find()) href = URLDecoder.decode(u.group(1), "UTF-8");
            if (href.startsWith("//")) href = "https:" + href;
            if (href.contains("duckduckgo.com/y.js")) { i++; continue; } // 広告
            JSONObject r = new JSONObject();
            r.put("title", clean(titles.group(2)));
            r.put("url", href);
            r.put("snippet", i < snips.size() ? snips.get(i) : "");
            out.add(r);
            i++;
        }
        return out;
    }

    private static List<JSONObject> bing(String q) throws Exception {
        String html = get("https://www.bing.com/search?setlang=ja&cc=JP&q=" + enc(q));
        List<JSONObject> out = new ArrayList<>();
        String[] blocks = html.split("<li class=\"b_algo\"");
        for (int i = 1; i < blocks.length; i++) {
            String b = blocks[i];
            Matcher t = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", FLAGS).matcher(b);
            if (!t.find()) continue;
            Matcher p = Pattern.compile("<p[^>]*>(.*?)</p>", FLAGS).matcher(b);
            JSONObject r = new JSONObject();
            r.put("title", clean(t.group(2)));
            r.put("url", unescape(t.group(1)));
            r.put("snippet", p.find() ? clean(p.group(1)) : "");
            out.add(r);
        }
        return out;
    }

    // ---------- ページを読む ----------
    static String page(String url) throws Exception {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new Exception("URL は http か https で始まる必要があります");
        String html = get(url);
        Matcher tm = Pattern.compile("<title[^>]*>(.*?)</title>", FLAGS).matcher(html);
        String title = tm.find() ? clean(tm.group(1)) : "";
        String body = html
                .replaceAll("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|section|article)>", "\n");
        body = unescape(body.replaceAll("(?s)<[^>]+>", " "));
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            String l = line.replaceAll("[ \\t\\u00A0\\u3000]+", " ").trim();
            if (l.length() >= 2) sb.append(l).append('\n');
            if (sb.length() > 4000) break;
        }
        String text = sb.length() > 4000 ? sb.substring(0, 4000) + "…" : sb.toString();
        JSONObject o = new JSONObject();
        o.put("title", title);
        o.put("url", url);
        o.put("text", text);
        return o.toString();
    }

    // ---------- 天気 ----------
    static String weather(String place) throws Exception {
        if (place.isEmpty()) place = "東京";
        JSONObject p = geocode(place);
        if (p == null) throw new Exception("「" + place + "」という場所が見つかりませんでした。市区町村名で試してください");
        String url = "https://api.open-meteo.com/v1/forecast?timezone=auto&forecast_days=3"
                + "&latitude=" + p.getDouble("latitude") + "&longitude=" + p.getDouble("longitude")
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max";
        JSONObject f = new JSONObject(get(url));
        JSONObject cur = f.getJSONObject("current");
        JSONObject daily = f.getJSONObject("daily");
        JSONObject out = new JSONObject();
        out.put("場所", p.optString("name") + (p.has("admin1") ? "（" + p.optString("admin1") + "）" : ""));
        JSONObject now = new JSONObject();
        now.put("天気", wmo(cur.optInt("weather_code")));
        now.put("気温", cur.optDouble("temperature_2m") + "℃");
        now.put("体感", cur.optDouble("apparent_temperature") + "℃");
        now.put("湿度", cur.optInt("relative_humidity_2m") + "%");
        now.put("風速", cur.optDouble("wind_speed_10m") + "km/h");
        out.put("今", now);
        JSONArray days = new JSONArray();
        JSONArray dates = daily.getJSONArray("time");
        for (int i = 0; i < dates.length(); i++) {
            JSONObject d = new JSONObject();
            d.put("日付", dates.getString(i));
            d.put("天気", wmo(daily.getJSONArray("weather_code").optInt(i)));
            d.put("最高", daily.getJSONArray("temperature_2m_max").optDouble(i) + "℃");
            d.put("最低", daily.getJSONArray("temperature_2m_min").optDouble(i) + "℃");
            d.put("降水確率", daily.getJSONArray("precipitation_probability_max").optInt(i) + "%");
            days.put(d);
        }
        out.put("予報", days);
        out.put("出典", "Open-Meteo");
        return out.toString();
    }

    /** 地名の書き方で見つかったり見つからなかったりするので、いくつかの形で探す。日本の場所を優先する */
    private static JSONObject geocode(String place) throws Exception {
        String base = place.replaceAll("(の天気|付近|周辺)$", "").trim();
        String stem = base.replaceAll("[都道府県市区町村]$", "");
        List<String> tries = new ArrayList<>();
        for (String t : new String[]{base, stem + "市", stem, stem + "都", stem + "府", stem + "県", stem + "区", stem + "町"}) {
            if (!t.isEmpty() && !tries.contains(t)) tries.add(t);
        }
        JSONObject fallback = null;
        for (String t : tries) {
            JSONObject geo = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=ja&format=json&name=" + enc(t)));
            JSONArray found = geo.optJSONArray("results");
            if (found == null) continue;
            for (int i = 0; i < found.length(); i++) {
                JSONObject r = found.getJSONObject(i);
                if ("JP".equals(r.optString("country_code"))) return r;
                if (fallback == null) fallback = r;
            }
        }
        return fallback;
    }

    private static String wmo(int c) {
        if (c == 0) return "快晴";
        if (c == 1) return "晴れ";
        if (c == 2) return "晴れ時々くもり";
        if (c == 3) return "くもり";
        if (c == 45 || c == 48) return "霧";
        if (c >= 51 && c <= 57) return "霧雨";
        if (c >= 61 && c <= 67) return c >= 65 ? "強い雨" : "雨";
        if (c >= 71 && c <= 77) return "雪";
        if (c >= 80 && c <= 82) return "にわか雨";
        if (c == 85 || c == 86) return "にわか雪";
        if (c >= 95) return "雷雨";
        return "不明";
    }

    // ---------- ニュース ----------
    static String news(String q) throws Exception {
        String url = q.isEmpty()
                ? "https://news.google.com/rss?hl=ja&gl=JP&ceid=JP:ja"
                : "https://news.google.com/rss/search?hl=ja&gl=JP&ceid=JP:ja&q=" + enc(q);
        String xml = get(url);
        JSONArray arr = new JSONArray();
        Matcher items = Pattern.compile("<item>(.*?)</item>", FLAGS).matcher(xml);
        while (items.find() && arr.length() < 8) {
            String it = items.group(1);
            JSONObject o = new JSONObject();
            o.put("見出し", clean(tag(it, "title")));
            o.put("日時", clean(tag(it, "pubDate")));
            o.put("配信元", clean(tag(it, "source")));
            arr.put(o);
        }
        if (arr.length() == 0) throw new Exception("ニュースが見つかりませんでした");
        return arr.toString();
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", FLAGS).matcher(xml);
        if (!m.find()) return "";
        return m.group(1).replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1");
    }

    // ---------- 共通 ----------
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "ja,en;q=0.7");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8");
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("サイトにつながりませんでした (HTTP " + code + ")");
        byte[] bytes = readAll(c.getInputStream(), 3_000_000);
        String cs = charset(c.getContentType(), bytes);
        c.disconnect();
        return new String(bytes, cs);
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < limit) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String charset(String contentType, byte[] bytes) {
        Matcher m = Pattern.compile("charset=\"?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType == null ? "" : contentType);
        String cs = m.find() ? m.group(1) : null;
        if (cs == null) {
            String head = new String(bytes, 0, Math.min(bytes.length, 3000), Charset.forName("ISO-8859-1"));
            Matcher meta = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            if (meta.find()) cs = meta.group(1);
        }
        try { if (cs != null && Charset.isSupported(cs)) return cs; } catch (Exception ignored) { }
        return "UTF-8";
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String clean(String html) {
        return unescape(html.replaceAll("(?s)<[^>]+>", "")).replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (Exception e) { cp = '?'; }
            if (cp <= 0 || cp > 0x10FFFF) cp = '?';
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
    }
}
