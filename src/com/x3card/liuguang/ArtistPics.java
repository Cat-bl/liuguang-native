package com.x3card.liuguang;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曲库"歌手"那一排砖上的歌手照片：按名字查 QQ 音乐的搜索提示接口（smartbox，不用登录），取名字对得上的那位歌手的编号，
 * 照片是 https://y.gtimg.cn/music/photo_new/T001R300x300M000 + 编号 + .jpg（SongCovers 照常按 QQ 的图床取小图）。
 * 查到的存在 lg 的 artistPics（JSON，名字 → 地址；查不到的记成空的，砖上用他最近一首歌的封面）；连不上不记，过一会儿再查。
 * 一个后台线程，一次查一个，查完叫 done
 */
final class ArtistPics {
    private static final int MAX = 400;
    private static final Map<String, String> map = new ConcurrentHashMap<>();
    private static final Set<String> asking = ConcurrentHashMap.newKeySet();
    private static SharedPreferences sp;
    private static ExecutorService ex;
    private static volatile long failAt;

    static synchronized void init(Context c) {
        if (sp != null) return;
        sp = c.getApplicationContext().getSharedPreferences("lg", Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(sp.getString("artistPics", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next(), v = o.optString(k, "");
                if (!k.isEmpty() && (v.isEmpty() || v.startsWith("https://y.gtimg.cn/"))) map.put(k, v);
            }
        } catch (Exception ignored) { /* 坏了就重新查 */ }
    }

    /** 查过了：照片地址（查不到是空的）；还没查过返回 null，在后台查（查完叫 done）；刚连不上时直接返回空的（先用封面） */
    static String get(final String name, final Runnable done) {
        String u = map.get(name);
        if (u != null) return u;
        if (sp == null || SystemClock.uptimeMillis() - failAt < 60000) return "";
        if (asking.add(name)) pool().execute(() -> {
            try {
                String r = ask(name);
                if (r == null) failAt = SystemClock.uptimeMillis(); else put(name, r);
            } finally { asking.remove(name); }
            done.run();
        });
        return null;
    }

    private static synchronized ExecutorService pool() {
        if (ex == null) ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lg-artist"); t.setPriority(Thread.MIN_PRIORITY); return t; });
        return ex;
    }

    private static synchronized void put(String name, String url) {
        if (map.size() >= MAX) map.clear(); // 攒太多了：清掉重查
        map.put(name, url);
        sp.edit().putString("artistPics", new JSONObject(map).toString()).apply();
    }

    private static String norm(String s) { return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    /** 问一次：名字对得上的歌手照片地址；没有这位是空的；连不上、回的不对是 null */
    private static String ask(String name) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&inCharset=utf8&outCharset=utf-8&key=" + URLEncoder.encode(name, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Referer", "https://y.qq.com/");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < 512 * 1024) bo.write(b, 0, n);
            }
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            if (o.optInt("code", -1) != 0) return null;
            JSONObject data = o.optJSONObject("data"), singer = data == null ? null : data.optJSONObject("singer");
            JSONArray l = singer == null ? null : singer.optJSONArray("itemlist");
            String want = norm(name);
            if (l != null) for (int i = 0; i < l.length(); i++) {
                JSONObject s = l.optJSONObject(i);
                if (s == null) continue;
                String mid = s.optString("mid", "");
                if (norm(s.optString("name", "")).equals(want) && mid.matches("[0-9A-Za-z]{6,32}")) return "https://y.gtimg.cn/music/photo_new/T001R300x300M000" + mid + ".jpg";
            }
            return "";
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "歌手照片没查到：" + name + "（" + e + "）");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
