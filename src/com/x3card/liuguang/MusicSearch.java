package com.x3card.liuguang;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜歌（第一个用 MusicApi 的功能，也是"加新功能/新接口"的范例）：
 * 每家给 MusicApi.callRaw 传自己的 endpoint + 业务参数，拿回文本后用 org.json 按这家的结构解析成统一的 Song。
 * 加别的功能（歌单、歌手页…）照这个写：endpoint/参数见抓包，解析按各家返回结构。
 */
final class MusicSearch {
    static final int PAGE = 20;

    static final class Song {
        final int src;
        final String id, name, artist, album, cover, mid;
        Song(int src, String id, String name, String artist, String album, String cover, String mid) {
            this.src = src; this.id = id; this.name = name; this.artist = artist; this.album = album; this.cover = cover; this.mid = mid;
        }
    }

    interface Cb { void done(List<Song> songs, String err); }

    /** 搜第 page 页（从 0 起）。结果回到主线程 */
    static void search(int code, String kw, int page, Cb cb) {
        MusicApi.exec().execute(() -> {
            List<Song> out = null;
            String err = null;
            try { out = searchSync(code, kw, page); }
            catch (Exception e) { err = "搜不了：" + e.getMessage(); }
            final List<Song> fo = out == null ? new ArrayList<>() : out;
            final String fe = err;
            new Handler(Looper.getMainLooper()).post(() -> cb.done(fo, fe));
        });
    }

    /** 同步搜（放后台线程用；SongFinder 的 QQ 兜底也用它）。失败抛异常 */
    static List<Song> searchSync(int code, String kw, int page) throws Exception {
        List<Song> out = new ArrayList<>();
        parse(code, MusicApi.callRaw(code, endpoint(code), bizOf(code, kw, page)), out);
        return out;
    }

    private static String endpoint(int code) {
        switch (code) {
            case 5: return "search/song";
            case 6: return "search_music";
            case 10: return "fcg_music_custom_search.fcg";
            case 12: return "search/song/get/v2";
            default: return "";
        }
    }

    private static java.util.Map<String, Object> bizOf(int code, String kw, int page) {
        switch (code) {
            case 5: return MusicApi.biz("keyword", kw, "page", page + 1, "size", PAGE);
            case 6: return MusicApi.biz("key", kw, "pn", page, "rn", PAGE);
            case 10: return MusicApi.biz("num", PAGE, "exclude_types", "", "file_format", "265",
                "w", kw, "max_file_type", "50", "p", page + 1, "source", "0", "support_dolby", "0", "t", "0");
            case 12: return MusicApi.biz("keyword", kw, "limit", PAGE, "offset", page * PAGE, "qualityFlag", "true");
            default: return MusicApi.biz();
        }
    }

    private static void parse(int code, String text, List<Song> out) throws Exception {
        JSONObject root = new JSONObject(text);
        switch (code) {
            case 5: { // 酷狗 data.songs[]
                JSONArray a = root.optJSONObject("data") == null ? null : root.optJSONObject("data").optJSONArray("songs");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    out.add(new Song(code, o.optString("song_id"), o.optString("song_name"),
                        o.optString("singer_name"), o.optString("album_name"), o.optString("album_img"), ""));
                }
                break;
            }
            case 6: { // 酷我 data[]
                JSONArray a = root.optJSONArray("data");
                if (a == null && root.optJSONObject("data") != null) a = root.optJSONObject("data").optJSONArray("list");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String rid = o.optString("musicrid", o.optString("rid", ""));
                    if (rid.startsWith("MUSIC_")) rid = rid.substring(6);
                    out.add(new Song(code, rid, o.optString("name"), o.optString("artist"),
                        o.optString("album"), o.optString("pic", o.optString("albumpic", "")), ""));
                }
                break;
            }
            case 10: { // QQ 顶层 list[]（车机 SDK 格式：字段都是扁平的）
                JSONArray a = root.optJSONArray("list");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    out.add(new Song(code, String.valueOf(o.optLong("song_id")), o.optString("song_name"),
                        o.optString("singer_name"), o.optString("album_name"),
                        o.optString("album_pic_300x300", o.optString("album_pic", "")), o.optString("song_mid")));
                }
                break;
            }
            case 12: { // 网易云 data.records[]
                JSONObject d = root.optJSONObject("data");
                JSONArray a = d == null ? null : d.optJSONArray("records");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    JSONObject al = o.optJSONObject("album");
                    out.add(new Song(code, o.optString("id"), o.optString("name"),
                        joinNames(o.optJSONArray("artists"), "name", "name"),
                        al == null ? "" : al.optString("name"), "", ""));
                }
                break;
            }
            default: break;
        }
    }

    private static String joinNames(JSONArray a, String k1, String k2) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString(k1, o.optString(k2, ""));
            if (!n.isEmpty()) b.append(b.length() > 0 ? "/" : "").append(n);
        }
        return b.toString();
    }
}
