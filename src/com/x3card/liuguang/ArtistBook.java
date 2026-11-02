package com.x3card.liuguang;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 曲库"歌手"那一排：从推来过的每份播放队列里攒歌手（合唱的按 / 、 & 拆开），记出现过几次、最近一首的封面地址。
 * 存在 lg 的 artists（JSON，最多 MAX 个，读的时候校验），按次数排，次数一样的最近见过的在前。只在主线程里用
 */
final class ArtistBook {
    static final int MAX = 300;

    static final class Artist {
        final String name;
        int count;
        String cover; // 他最近一首歌的封面地址（可能是空的）
        long seen;
        Artist(String name) { this.name = name; }
    }

    private final SharedPreferences prefs;
    private final Map<String, Artist> map = new HashMap<>();
    private List<Artist> sorted = Collections.emptyList();
    private int seq; // 内容变了加一

    ArtistBook(SharedPreferences prefs) {
        this.prefs = prefs;
        try {
            JSONArray a = new JSONArray(prefs.getString("artists", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String n = o.optString("n").trim();
                if (n.isEmpty() || n.length() > 40 || map.containsKey(n)) continue;
                Artist x = new Artist(n);
                x.count = Math.max(1, o.optInt("c", 1));
                x.cover = o.optString("u", "");
                x.seen = o.optLong("t");
                map.put(n, x);
            }
        } catch (Exception ignored) { /* 坏了就从头攒 */ }
        sort();
    }

    /** 一份新推来的播放队列：里面的歌手各记一次（同一份里重复出现只算一次） */
    void add(List<BydMedia.Song> songs) {
        if (songs.isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, String> once = new HashMap<>();
        for (BydMedia.Song s : songs) for (String n : split(s.artist)) if (!once.containsKey(n) || once.get(n).isEmpty()) once.put(n, s.cover);
        for (Map.Entry<String, String> e : once.entrySet()) {
            Artist x = map.get(e.getKey());
            if (x == null) map.put(e.getKey(), x = new Artist(e.getKey()));
            x.count++;
            x.seen = now;
            if (!e.getValue().isEmpty()) x.cover = e.getValue();
        }
        sort();
        if (map.size() > MAX) { for (int i = MAX; i < sorted.size(); i++) map.remove(sorted.get(i).name); sort(); }
        save();
    }

    /** 按次数排好的歌手（不要改它） */
    List<Artist> list() { return sorted; }

    int seq() { return seq; }

    private void sort() {
        List<Artist> l = new ArrayList<>(map.values());
        Collections.sort(l, (a, b) -> a.count != b.count ? b.count - a.count : Long.compare(b.seen, a.seen));
        sorted = Collections.unmodifiableList(l);
        seq++;
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Artist x : sorted) a.put(new JSONObject().put("n", x.name).put("c", x.count).put("u", x.cover == null ? "" : x.cover).put("t", x.seen));
        } catch (Exception ignored) { }
        prefs.edit().putString("artists", a.toString()).apply();
    }

    /** "周杰伦/费玉清"、"A & B"、"甲、乙" 拆成一个个；不认识的（未知歌手、群星）不要 */
    static List<String> split(String artist) {
        List<String> out = new ArrayList<>();
        if (artist == null) return out;
        for (String p : artist.split("\\s*(?:/|、|&|＆|,|，|;|；| feat\\. | ft\\. )\\s*")) {
            String n = p.trim();
            if (n.isEmpty() || n.length() > 40 || n.contains("未知") || n.equals("群星") || n.equalsIgnoreCase("Various Artists") || n.equalsIgnoreCase("unknown")) continue;
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }
}
