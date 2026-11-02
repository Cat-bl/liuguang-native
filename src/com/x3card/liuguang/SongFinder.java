package com.x3card.liuguang;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 曲库的"搜歌"：按当前（或者要切到的）在线音乐，用那一家自己的网页搜索接口搜歌（不用登录；拼音全拼、首字母、英文都认，搜出来的和在那个音源里搜的是一套），一页 PAGE 首：
 * - QQ 音乐（10）：u.y.qq.com 的 musicu.fcg，手机版的搜索（DoSearchForQQMusicMobile，POST JSON，page_num 从 1 起；c.y.qq.com 的 client_search_cp 在车机的网络上回 HTTP 500，
 *   桌面版的 DoSearchForQQMusicDesktop 不带 comm 回 2001、带了回空的），
 *   封面按专辑 mid 拼 y.gtimg.cn 的地址；id 是数字的歌曲 id（「多媒体」按它插放，见 Player.insertSong）。
 * - 网易云（12）：music.163.com 的网页搜索（offset），封面另外按歌曲 id 批量查一次详情（搜索结果里只有图片编号）。
 * - 酷狗（5）：mobilecdn.kugou.com（只有 http，网络安全配置里单独放开；https 的那个接口不认拼音），封面是 trans_param.union_cover（{size} 换成 240）。
 * - 酷我（6）：search.kuwo.cn（单引号的 JSON，org.json 认），封面是 web_albumpic_short 拼 img1.kuwo.cn。
 * 网易云、酷狗、酷我点了以后是按"歌名 + 歌手 + 专辑"让「多媒体」在那个音源里搜一次插放（见 MainActivity.sendSong），所以这里要把专辑带上。
 * 搜歌一个后台线程，一次只做一个；做完叫 done（后台线程），带着请求时的 seq（主线程按它扔掉过时的）
 */
final class SongFinder {
    static final int PAGE = 20;

    static final class Hit {
        final int src;
        final String id, title, artist, album, cover;
        Hit(int src, String id, String title, String artist, String album, String cover) {
            this.src = src; this.id = id; this.title = title; this.artist = artist; this.album = album; this.cover = cover;
        }
    }

    /** 一页结果：err 不是空的就是没搜成（说给人听的原因） */
    static final class Page {
        final int seq, page, src;
        final String query, err;
        final List<Hit> hits;
        final boolean more;
        Page(int seq, int page, int src, String query, List<Hit> hits, boolean more, String err) {
            this.seq = seq; this.page = page; this.src = src; this.query = query; this.hits = hits; this.more = more; this.err = err;
        }
    }

    interface Done { void got(Page p); }

    private static final ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lg-find"); t.setDaemon(true); return t; });
    private static volatile int latest;

    /** 搜 query 的第 page 页（从 0 起）；后面又搜了别的，排着还没做的这一次就不做 */
    static void search(final int src, final String query, final int page, final int seq, final Done done) {
        latest = seq;
        ex.execute(() -> {
            if (seq != latest) return;
            Page p;
            try { p = run(src, query, page, seq); }
            catch (Exception e) {
                Log.w(Uiplus.TAG, "搜歌失败：" + query + "（" + e + "）");
                p = new Page(seq, page, src, query, Collections.<Hit>emptyList(), false, e instanceof Busy ? "QQ音乐没回结果，过一会儿再搜" : "搜不了：检查一下网络");
            }
            done.got(p);
        });
    }

    /** QQ 一直回 2001 */
    private static final class Busy extends Exception { Busy() { super("QQ 回 2001"); } }

    private static Page run(int src, String q, int page, int seq) throws Exception {
        String k = URLEncoder.encode(q, "UTF-8");
        List<Hit> l = new ArrayList<>();
        boolean more;
        switch (src) {
            case 10: {
                JSONObject req0 = new JSONObject().put("comm", new JSONObject().put("ct", 11).put("cv", "1003006").put("tmeAppID", "qqmusic"))
                    .put("req", new JSONObject().put("module", "music.search.SearchCgiService").put("method", "DoSearchForQQMusicMobile")
                    .put("param", new JSONObject().put("query", q).put("page_num", page + 1).put("num_per_page", PAGE).put("search_type", 0)));
                JSONObject r = new JSONObject(post("https://u.y.qq.com/cgi-bin/musicu.fcg", req0.toString(), "https://y.qq.com/")).optJSONObject("req");
                for (int t = 1; r != null && r.optInt("code") == 2001; t++) { // QQ 时不时回 2001（空的）：隔一会儿再试，最多三次
                    if (t >= 3) throw new Busy();
                    if (seq != latest) throw new Busy(); // 又搜了别的
                    Thread.sleep(400L * t);
                    r = new JSONObject(post("https://u.y.qq.com/cgi-bin/musicu.fcg", req0.toString(), "https://y.qq.com/")).optJSONObject("req");
                }
                JSONObject data = r == null ? null : r.optJSONObject("data"), body = data == null ? null : data.optJSONObject("body"), meta = data == null ? null : data.optJSONObject("meta");
                JSONArray a = body == null ? null : body.optJSONArray("item_song");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optLong("id") <= 0) continue;
                    JSONObject al = o.optJSONObject("album");
                    String mid = al == null ? "" : al.optString("mid", "");
                    l.add(new Hit(src, String.valueOf(o.optLong("id")), o.optString("name"), names(o.optJSONArray("singer"), "name"), al == null ? "" : al.optString("name"),
                        mid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + mid + ".jpg"));
                }
                more = meta != null && meta.optInt("nextpage", -1) > page + 1 && (page + 1) * PAGE < meta.optInt("sum");
                break;
            }
            case 12: {
                JSONObject r = new JSONObject(get("https://music.163.com/api/search/get/web?type=1&limit=" + PAGE + "&offset=" + page * PAGE + "&s=" + k, "https://music.163.com/")).optJSONObject("result");
                JSONArray a = r == null ? null : r.optJSONArray("songs");
                StringBuilder ids = new StringBuilder();
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    JSONObject al = o.optJSONObject("album");
                    l.add(new Hit(src, String.valueOf(o.optLong("id")), o.optString("name"), names(o.optJSONArray("artists"), "name"), al == null ? "" : al.optString("name"), ""));
                    ids.append(ids.length() > 0 ? "," : "").append(o.optLong("id"));
                }
                more = r != null && (page + 1) * PAGE < r.optInt("songCount");
                if (ids.length() > 0) l = neteaseCovers(l, ids.toString());
                break;
            }
            case 5: {
                JSONObject d = new JSONObject(get("http://mobilecdn.kugou.com/api/v3/search/song?format=json&pagesize=" + PAGE + "&page=" + (page + 1) + "&keyword=" + k, null)).optJSONObject("data");
                JSONArray a = d == null ? null : d.optJSONArray("info");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    JSONObject tp = o.optJSONObject("trans_param");
                    String cv = tp == null ? "" : tp.optString("union_cover", "").replace("{size}", "240");
                    l.add(new Hit(src, String.valueOf(o.optLong("album_audio_id")), o.optString("songname"), o.optString("singername"), o.optString("album_name"), cv));
                }
                more = d != null && (page + 1) * PAGE < d.optInt("total");
                break;
            }
            case 6: {
                JSONObject d = new JSONObject(get("https://search.kuwo.cn/r.s?ft=music&rformat=json&encoding=utf8&vipver=1&rn=" + PAGE + "&pn=" + page + "&all=" + k, null));
                JSONArray a = d.optJSONArray("abslist");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String pic = o.optString("web_albumpic_short", "");
                    l.add(new Hit(src, o.optString("DC_TARGETID"), kw(o.optString("SONGNAME")), kw(o.optString("ARTIST")), kw(o.optString("ALBUM")),
                        pic.isEmpty() ? "" : "https://img1.kuwo.cn/star/albumcover/" + pic.replaceFirst("^\\d+/", "300/")));
                }
                more = (page + 1) * PAGE < d.optInt("TOTAL");
                break;
            }
            default:
                return new Page(seq, page, src, q, Collections.<Hit>emptyList(), false, "这个音源搜不了歌");
        }
        return new Page(seq, page, src, q, l, more && !l.isEmpty(), null);
    }

    /** 网易云：按 id 批量查详情拿封面（查不到照样用，封面按歌名生成） */
    private static List<Hit> neteaseCovers(List<Hit> l, String ids) {
        try {
            JSONArray a = new JSONObject(get("https://music.163.com/api/song/detail/?ids=" + URLEncoder.encode("[" + ids + "]", "UTF-8"), "https://music.163.com/")).optJSONArray("songs");
            java.util.Map<String, String> pic = new java.util.HashMap<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i), al = o == null ? null : o.optJSONObject("album");
                if (al != null) pic.put(String.valueOf(o.optLong("id")), al.optString("picUrl", ""));
            }
            List<Hit> out = new ArrayList<>();
            for (Hit h : l) { String p = pic.get(h.id); out.add(p == null ? h : new Hit(h.src, h.id, h.title, h.artist, h.album, p)); }
            return out;
        } catch (Exception e) {
            return l;
        }
    }

    private static String names(JSONArray a, String key) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String n = o == null ? "" : o.optString(key, "");
            if (!n.isEmpty()) b.append(b.length() > 0 ? "/" : "").append(n);
        }
        return b.toString();
    }

    /** 酷我的字：HTML 实体，& 还会再转义一层成字面的 & */
    private static String kw(String s) { return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("\\u0026", "&").trim(); }

    private static String get(String url, String referer) throws Exception { return fetch(url, null, referer); }

    private static String post(String url, String json, String referer) throws Exception { return fetch(url, json, referer); }

    private static String fetch(String url, String json, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)");
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < 2 * 1024 * 1024) bo.write(b, 0, n);
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
