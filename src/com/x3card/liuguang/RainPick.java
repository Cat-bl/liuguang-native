package com.x3card.liuguang;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 雨珠开不开（设置：自动 / 开 / 关；同网页版 main.js 的"雨珠开不开"）。
 * 开：每首歌都下；关：都不下；自动：这首歌的歌名和整首歌词里说到下雨的词（WORDS，同网页版的 RAIN_WORDS）有 HITS 个以上就下（最优先，外面晴天也下）；
 * 不是这样的歌看实时天气（车机自带的天气，见 BydWeather），外面在下雨就下、没下就不下，查不到天气就不下。
 * 一首歌里只会从不下变成下（听到的歌词越来越多），不会下着下着停了；换歌时新歌的整首歌词到了（或者等了 WAIT）再定，没定之前保持原样。
 * 只在主线程里用
 */
final class RainPick {
    static final int HITS = 3;
    static final long WAIT = 6000;
    /** 说到下雨的词：中文、日文的"雨"；英文 rain 一家（rainbow、train、brain 不算）、drizzle、downpour、monsoon、thunderstorm、umbrella；韩文 빗、비가、비 내리、비를 맞、장마、소나기；
     *  西班牙文 lluvia / llueve / llover；葡萄牙文 chuva / chove；法文 pluie / pleut；德文、荷兰文 Regen / regnet；意大利文 pioggia / piove；俄文 дождь / ливень；泰文 ฝน；越南文 mưa；印尼文 hujan */
    private static final Pattern WORDS = Pattern.compile("雨|\\brain(s|y|ing|ed|drops?|fall|storm|coat)?\\b|\\bdrizzl\\w*|\\bdownpours?\\b|\\bmonsoons?\\b|\\bthunderstorms?\\b|\\bumbrellas?\\b|빗|비가|비 ?내리|비를 ?맞|장마|소나기|\\bllu(via|eve)\\w*|\\bllov\\w*|\\bchuv\\w*|\\bchov\\w*|\\bpluies?\\b|\\bpleu(t|voir|vait)\\b|\\bregen\\b|\\bregnet\\b|\\bpiogg\\w*|\\bpiov\\w*|дожд|ливн|ливень|ฝน|\\bmưa\\b|\\bhujan\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREDIT = Pattern.compile("^\\s*(作词|作曲|词|曲|编曲|制作人?|监制|演唱|原唱|混音|和声|录音|母带|出品|发行|OP|SP|lyrics?( by)?|music( by)?|composed by|composer|written by|arranged by|produced by|producer)\\s*[:：].*", Pattern.CASE_INSENSITIVE); // 署名行（人名里的不算）

    // 这首歌
    private String track = "\u0000";
    private Boolean on;
    private long at;
    private String key = "";
    private int hits;

    /** 换歌了没有（换了要过 WAIT 再看一次） */
    boolean newTrack(MediaState s, long now) {
        String t = s.track == null ? "" : s.track;
        if (t.equals(track)) return false;
        track = t; on = null; at = now;
        return true;
    }

    private int hits(MediaState s) {
        String k = s.track + "|" + s.title + "|" + s.revLines;
        if (k.equals(key)) return hits;
        int n = count(s.title);
        for (String l : s.lines) if (l != null && !CREDIT.matcher(l).matches()) n += count(l);
        key = k; hits = n;
        return n;
    }

    private static int count(String t) {
        int n = 0;
        if (t != null) for (Matcher m = WORDS.matcher(t); m.find(); ) n++;
        return n;
    }

    /** 这首歌下不下：true / false，还没定是 null */
    private Boolean song(MediaState s, long now) {
        if (Boolean.TRUE.equals(on)) return true;
        if (hits(s) >= HITS) return on = true;
        if (!s.on || s.full || now - at >= WAIT) on = false;
        return on;
    }

    /** 自动时下不下（sky：外面在不在下雨，查不到是 null）：true / false，还没定（这首歌词还没到）是 null——先保持原样 */
    Boolean auto(MediaState s, long now, Boolean sky) {
        Boolean so = song(s, now);
        if (Boolean.TRUE.equals(so) || Boolean.TRUE.equals(sky)) return true;
        return so == null ? null : false;
    }

}
