package com.x3card.liuguang;

import android.os.Process;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * 歌单搜索：每首的歌名、歌手转成拉丁字母（安卓自带的 ICU 转写：汉字转拼音、假名和韩文转罗马字），
 * 按拼音首字母（qlx → 七里香）、全拼（qili）、英文单词首字母或开头、数字匹配。
 * 建索引在后台线程（几百首零点几秒），匹配在 GL 线程（每打一个字母一次，几百首不到一毫秒）
 */
final class SongIndex {
    interface Done { void built(SongIndex x); }

    final List<BydMedia.Song> songs;
    private final String[] tInit, tFull, aInit, aFull; // 歌名 / 歌手的首字母、全拼（只有小写字母和数字）

    private SongIndex(List<BydMedia.Song> songs) {
        this.songs = songs;
        int n = songs.size();
        tInit = new String[n]; tFull = new String[n]; aInit = new String[n]; aFull = new String[n];
    }

    /** 在后台线程里建好交给 done */
    static void build(final List<BydMedia.Song> songs, final Done done) {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            SongIndex x = new SongIndex(songs);
            StringBuilder ini = new StringBuilder(), full = new StringBuilder();
            synchronized (SongIndex.class) { // 转写器建一次留着（建一个要零点几秒）；它不能几个线程同时用
                if (tr == null) tr = translit();
                for (int i = 0; i < songs.size(); i++) {
                    keys(tr, songs.get(i).title, ini, full); x.tInit[i] = ini.toString(); x.tFull[i] = full.toString();
                    keys(tr, songs.get(i).artist, ini, full); x.aInit[i] = ini.toString(); x.aFull[i] = full.toString();
                }
            }
            done.built(x);
        }, "song-index");
        t.setDaemon(true);
        t.start();
    }

    private static Object tr;

    /** 转写器（安卓 10 起有；拿不到就只按原文里的字母数字搜） */
    private static Object translit() {
        for (String id : new String[] { "Any-Latin; Latin-ASCII; Lower", "Han-Latin; Latin-ASCII; Lower" }) {
            try { return android.icu.text.Transliterator.getInstance(id); }
            catch (Throwable e) { Log.w(Uiplus.TAG, "转写 " + id + " 用不了：" + e); }
        }
        return null;
    }

    /** 转成字母后按非字母数字切词：全拼是词连起来，首字母是每个词的头一个 */
    private static void keys(Object tr, String s, StringBuilder ini, StringBuilder full) {
        ini.setLength(0);
        full.setLength(0);
        String t = s == null ? "" : s;
        if (tr != null) { try { t = ((android.icu.text.Transliterator) tr).transliterate(t); } catch (Throwable ignored) { } }
        t = t.toLowerCase(Locale.ROOT);
        boolean inWord = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean w = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (w) { if (!inWord) ini.append(c); full.append(c); }
            inWord = w;
        }
    }

    /** 搜 q（小写字母数字）：返回搜到的歌在歌单里的序号，好的在前（歌名开头 > 歌手开头 > 歌名中间 > 歌手中间，一样好的按歌单顺序） */
    int[] match(String q) {
        int n = tInit.length;
        int[] score = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int sc = tInit[i].startsWith(q) || tFull[i].startsWith(q) ? 0
                : aInit[i].startsWith(q) || aFull[i].startsWith(q) ? 1
                : tInit[i].contains(q) || tFull[i].contains(q) ? 2
                : aInit[i].contains(q) || aFull[i].contains(q) ? 3 : -1;
            score[i] = sc;
            if (sc >= 0) m++;
        }
        int[] out = new int[m];
        int k = 0;
        for (int sc = 0; sc <= 3; sc++) for (int i = 0; i < n; i++) if (score[i] == sc) out[k++] = i;
        return out;
    }
}
