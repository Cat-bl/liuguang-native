package com.x3card.liuguang;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 键盘（Keyboard）的拼音输入法（不联网）。词库是 assets 的 pinyin.bin（AOSP 拼音输入法的 6.5 万个词和词频，格式见 assets.mjs 的 pinyinBin），
 * 另加两类自己的词：放过的歌名、歌手名（安卓自带的 ICU 转拼音，names），打字时选过的词、逐字拼出来的词（learn，存 lg 的 ime）。
 * 打的字母先切成音节（split）：每段是一个完整的音节，或者只打了开头（声母，sjzx；最后一段还可以是没打完的音节，zho），
 * 只留"只打了开头"的段最少的那几种切法（xian：xian、xi'an）。候选（query）：从头起对得上的词，用掉的字母多的在前，然后"只打了开头"的段少的、词频高的；
 * 没有一个词能把整串用完时，第一个是按词频拼出来的整句（shijiezhongxin → 世界中心）。
 * 词库、歌名在后台线程里读（第一次打开键盘时），读好以前没有候选。query 在 GL 线程里调，词库读好以后不变，自己的词换整个数组
 */
final class Pinyin {
    private static final int MAX_PATHS = 24, MAX_CANDS = 80, MAX_LEARN = 300, MAX_NAMES = 1500, MAX_WORD = 10;
    private static final float NAME_F = 9.5f, LEARN_F = 10f, LEARN_STEP = 0.7f; // 自己的词的 ln(词频)：歌名歌手、选过的（选一次加一点，最多六次）
    private static final String INIT = "bpmfdtnlgkhjqxrzcsyw";
    private static final HashSet<String> ODD = new HashSet<>(java.util.Arrays.asList("m", "n", "ng", "hm", "hng")); // 嗯、呣这类：当声母（n 开头的简拼）用，不当成一个完整的音节

    static final class Cand {
        final String text;
        final short[] ids; // 每个字的音节编号
        final int used;    // 用掉了打的前几个字母
        Cand(String text, short[] ids, int used) { this.text = text; this.ids = ids; this.used = used; }
    }

    /** cands：候选（最多 MAX_CANDS 个，可能是空的）；shown：切好的拼音，音节之间加 '（xi'an） */
    static final class Result {
        final Cand[] cands;
        final String shown;
        Result(Cand[] cands, String shown) { this.cands = cands; this.shown = shown; }
    }

    // ================= 词库（读好以后不变） =================
    private static final class Dict {
        String[] syl;
        HashMap<String, Integer> id;
        int n;
        int[] off;
        byte[] len;
        short[] sy, fq;
        char[] ch;
        float lnTotal;
    }

    /** 自己的词：歌名歌手（learned 假）、选过的（真） */
    private static final class Uw {
        final String w;
        final short[] ids;
        final float f;
        final int n;
        final long t;
        final boolean learned;
        Uw(String w, short[] ids, float f, int n, long t, boolean learned) { this.w = w; this.ids = ids; this.f = f; this.n = n; this.t = t; this.learned = learned; }
    }

    private static volatile Dict D;
    private static volatile Uw[] users = new Uw[0];
    private static final Object LOCK = new Object();
    private static final List<Uw> learned = new ArrayList<>(), names = new ArrayList<>(); // 改的时候拿着 LOCK
    private static Context app;
    private static boolean started;
    private static int namesSig;
    private static Object han; // ICU 的 Han-Latin（只在后台线程里用）
    private static final ThreadPoolExecutor EX = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
        Thread t = new Thread(r, "lg-pinyin");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    static boolean ready() { return D != null; }

    /** 第 id 个音节（词库没读好是空的） */
    static String syl(int id) { Dict d = D; return d == null || id < 0 || id >= d.syl.length ? "" : d.syl[id]; }

    /** 打开键盘时（主线程）：第一次先读词库和记住的词，再把这些歌名、歌手名（没变就不重做）加进词库 */
    static void load(Context ctx, List<String> nameList) {
        if (!started) {
            started = true;
            app = ctx.getApplicationContext();
            EX.execute(() -> {
                Dict d = readDict();
                if (d == null) return;
                D = d;
                readLearned(d);
            });
        }
        final List<String> copy = new ArrayList<>(nameList);
        EX.execute(() -> addNames(copy));
    }

    private static Dict readDict() {
        try (InputStream in = app.getAssets().open("pinyin.bin")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(800 * 1024);
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            ByteBuffer b = ByteBuffer.wrap(bo.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != 0x5950474c || b.getShort() != 1) throw new IllegalStateException("文件头不对");
            Dict d = new Dict();
            int s = b.getShort() & 0xffff;
            d.syl = new String[s];
            d.id = new HashMap<>(s * 2);
            for (int i = 0; i < s; i++) {
                byte[] x = new byte[b.get() & 0xff];
                b.get(x);
                d.syl[i] = new String(x, StandardCharsets.US_ASCII);
                d.id.put(d.syl[i], i);
            }
            int n = b.getInt(), t = b.getInt();
            d.lnTotal = b.getFloat();
            d.n = n;
            d.len = new byte[n];
            b.get(d.len);
            if ((n & 1) != 0) b.get();
            d.sy = new short[t];
            b.asShortBuffer().get(d.sy);
            b.position(b.position() + t * 2);
            d.ch = new char[t];
            b.asCharBuffer().get(d.ch);
            b.position(b.position() + t * 2);
            d.fq = new short[n];
            b.asShortBuffer().get(d.fq);
            d.off = new int[n];
            int o = 0;
            for (int i = 0; i < n; i++) { d.off[i] = o; o += d.len[i] & 0xff; }
            if (o != t) throw new IllegalStateException("字数对不上");
            for (short v : d.sy) if (v < 0 || v >= s) throw new IllegalStateException("音节编号越界");
            return d;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "拼音词库读不出来：" + e);
            return null;
        }
    }

    // ================= 切音节 =================
    private static final class Seg {
        final int start, end, a, b; // 打的第 start～end 个字母；对得上的音节编号 [a, b)
        final boolean exact;
        Seg(int start, int end, int a, int b, boolean exact) { this.start = start; this.end = end; this.a = a; this.b = b; this.exact = exact; }
    }

    /** 以 p 开头的音节的编号范围（音节按字母排，所以是连着的一段） */
    private static int[] range(Dict d, String p) {
        int lo = 0, hi = d.syl.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (d.syl[m].compareTo(p) < 0) lo = m + 1; else hi = m; }
        int e = lo;
        while (e < d.syl.length && d.syl[e].startsWith(p)) e++;
        return new int[] { lo, e };
    }

    /** 切法：只打了开头的段最少的那几种（最多 MAX_PATHS 种，长的段先试）；切不到底的切到能切的最远处（end 里是切到了第几个字母） */
    private static List<Seg[]> split(Dict d, String s, int[] end) {
        int L = s.length();
        List<List<Seg>> at = new ArrayList<>(L);
        for (int i = 0; i < L; i++) {
            List<Seg> l = new ArrayList<>();
            for (int k = Math.min(6, L - i); k >= 1; k--) {
                String t = s.substring(i, i + k);
                Integer id = d.id.get(t);
                if (id != null && !ODD.contains(t)) l.add(new Seg(i, i + k, id, id + 1, true));
            }
            boolean zh = i + 2 <= L && s.charAt(i + 1) == 'h' && "zcs".indexOf(s.charAt(i)) >= 0, ini = INIT.indexOf(s.charAt(i)) >= 0, whole = false;
            for (Seg g : l) if (g.end == L) whole = true;
            if (!whole && !(zh && i + 2 == L) && !(ini && i + 1 == L)) { // 最后一段没打完（zho）：排在 zh'o 前面
                int[] r = range(d, s.substring(i));
                if (r[1] > r[0]) l.add(new Seg(i, L, r[0], r[1], false));
            }
            if (zh) { int[] r = range(d, s.substring(i, i + 2)); l.add(new Seg(i, i + 2, r[0], r[1], false)); }
            if (ini) { int[] r = range(d, s.substring(i, i + 1)); l.add(new Seg(i, i + 1, r[0], r[1], false)); }
            at.add(l);
        }
        boolean[] reach = new boolean[L + 1];
        reach[0] = true;
        int E = 0;
        for (int i = 0; i < L; i++) { if (!reach[i]) continue; for (Seg g : at.get(i)) { reach[g.end] = true; E = Math.max(E, g.end); } }
        end[0] = E;
        List<Seg[]> out = new ArrayList<>();
        if (E == 0) return out;
        final int INF = 1 << 20;
        int[] best = new int[E + 1];
        java.util.Arrays.fill(best, INF);
        best[E] = 0;
        for (int i = E - 1; i >= 0; i--) for (Seg g : at.get(i)) if (g.end <= E && best[g.end] < INF) best[i] = Math.min(best[i], (g.exact ? 0 : 1) + best[g.end]);
        if (best[0] >= INF) return out;
        enumerate(at, best, 0, E, new ArrayList<Seg>(), out);
        return out;
    }

    private static void enumerate(List<List<Seg>> at, int[] best, int i, int E, List<Seg> cur, List<Seg[]> out) {
        if (out.size() >= MAX_PATHS) return;
        if (i == E) { out.add(cur.toArray(new Seg[0])); return; }
        for (Seg g : at.get(i)) {
            if (g.end > E || (g.exact ? 0 : 1) + best[g.end] != best[i]) continue;
            cur.add(g);
            enumerate(at, best, g.end, E, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    // ================= 查词 =================
    private interface Hit { void word(int w, int k); }

    /** 第 dd 个音节 ≥ v 的第一个（[lo, hi) 里的词前 dd 个音节都一样、都比 dd 长） */
    private static int lower(Dict d, int lo, int hi, int dd, int v) {
        while (lo < hi) { int m = (lo + hi) >>> 1; if (d.sy[d.off[m] + dd] < v) lo = m + 1; else hi = m; }
        return lo;
    }

    /** 从 p[from] 起对得上的词库里的词：[lo, hi) 是前 dd 个音节都对得上的 */
    private static void walk(Dict d, Seg[] p, int from, int lo, int hi, int dd, Hit h) {
        int i = lo;
        if (dd > 0) for (; i < hi && (d.len[i] & 0xff) == dd; i++) h.word(i, dd);
        if (from + dd >= p.length || i >= hi) return;
        Seg s = p[from + dd];
        int l2 = lower(d, i, hi, dd, s.a), h2 = lower(d, l2, hi, dd, s.b);
        for (int j = l2; j < h2; ) {
            int v = d.sy[d.off[j] + dd], e = lower(d, j, h2, dd, v + 1);
            walk(d, p, from, j, e, dd + 1, h);
            j = e;
        }
    }

    /** 自己的词从 p[from] 起整个对得上没有 */
    private static boolean fits(Uw u, Seg[] p, int from) {
        if (from + u.ids.length > p.length) return false;
        for (int j = 0; j < u.ids.length; j++) { Seg s = p[from + j]; if (u.ids[j] < s.a || u.ids[j] >= s.b) return false; }
        return true;
    }

    private static final class M { int w, k, path, used, abbr; float f; } // w ≥ 0 词库第几个，< 0 是自己的词 -1-w

    /** 打的这串字母（只有 a～z）的候选；词库还没读好返回空 */
    static Result query(String s) {
        final Dict d = D;
        if (d == null || s.isEmpty()) return null;
        int[] endA = new int[1];
        final List<Seg[]> paths = split(d, s, endA);
        int E = endA[0];
        if (paths.isEmpty()) return new Result(new Cand[0], s);
        final Uw[] us = users;
        final List<M> ms = new ArrayList<>();
        for (int pi = 0; pi < paths.size(); pi++) {
            final Seg[] p = paths.get(pi);
            final int fp = pi;
            walk(d, p, 0, 0, d.n, 0, (w, k) -> ms.add(m(p, fp, w, k, d.fq[w] / 1000f)));
            for (int u = 0; u < us.length; u++) if (fits(us[u], p, 0)) ms.add(m(p, pi, -1 - u, us[u].ids.length, us[u].f));
        }
        Collections.sort(ms, (x, y) -> x.used != y.used ? y.used - x.used : x.abbr != y.abbr ? x.abbr - y.abbr : Float.compare(y.f, x.f));
        List<Cand> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int[] top = { ms.isEmpty() ? 0 : ms.get(0).path };
        if (ms.isEmpty() || ms.get(0).used < E) { // 没有一个词能用完：整句
            Cand c = sentence(d, paths, us, E, top);
            if (c != null) { out.add(c); seen.add(c.text); }
        }
        for (M m : ms) {
            if (out.size() >= MAX_CANDS) break;
            String t;
            short[] ids;
            if (m.w >= 0) { t = new String(d.ch, d.off[m.w], m.k); ids = new short[m.k]; System.arraycopy(d.sy, d.off[m.w], ids, 0, m.k); }
            else { Uw u = us[-1 - m.w]; t = u.w; ids = u.ids; }
            if (seen.add(t)) out.add(new Cand(t, ids, m.used));
        }
        StringBuilder sb = new StringBuilder();
        for (Seg g : paths.get(top[0])) { if (sb.length() > 0) sb.append('\''); sb.append(s, g.start, g.end); }
        sb.append(s, E, s.length());
        return new Result(out.toArray(new Cand[0]), sb.toString());
    }

    private static M m(Seg[] p, int path, int w, int k, float f) {
        M m = new M();
        m.w = w; m.k = k; m.path = path; m.f = f;
        m.used = p[k - 1].end;
        for (int i = 0; i < k; i++) if (!p[i].exact) m.abbr++;
        return m;
    }

    /** 整句：在前几种切法上按词频（每个词 ln(词频 / 总词频)，加起来最大）把整串用完；只拼出一个词的不算。用的是第几种切法写进 path[0] */
    private static Cand sentence(final Dict d, List<Seg[]> paths, Uw[] us, int E, int[] path) {
        float bestS = Float.NEGATIVE_INFINITY;
        Cand best = null;
        for (int pi = 0; pi < Math.min(4, paths.size()); pi++) {
            final Seg[] p = paths.get(pi);
            final int n = p.length;
            if (n < 2) continue;
            final float[] dp = new float[n + 1];
            final int[] bw = new int[n + 1], bk = new int[n + 1];
            java.util.Arrays.fill(dp, Float.NEGATIVE_INFINITY);
            dp[0] = 0;
            for (int i = 0; i < n; i++) {
                if (dp[i] == Float.NEGATIVE_INFINITY) continue;
                final int fi = i;
                walk(d, p, i, 0, d.n, 0, (w, k) -> {
                    float v = dp[fi] + d.fq[w] / 1000f - d.lnTotal;
                    if (v > dp[fi + k]) { dp[fi + k] = v; bw[fi + k] = w; bk[fi + k] = k; }
                });
                for (int u = 0; u < us.length; u++) {
                    if (!fits(us[u], p, i)) continue;
                    int k = us[u].ids.length;
                    float v = dp[i] + us[u].f - d.lnTotal;
                    if (v > dp[i + k]) { dp[i + k] = v; bw[i + k] = -1 - u; bk[i + k] = k; }
                }
            }
            if (dp[n] == Float.NEGATIVE_INFINITY || dp[n] <= bestS) continue;
            List<String> parts = new ArrayList<>();
            List<short[]> ids = new ArrayList<>();
            for (int j = n; j > 0; j -= bk[j]) {
                int w = bw[j], k = bk[j];
                if (w >= 0) { parts.add(new String(d.ch, d.off[w], k)); short[] x = new short[k]; System.arraycopy(d.sy, d.off[w], x, 0, k); ids.add(x); }
                else { parts.add(us[-1 - w].w); ids.add(us[-1 - w].ids); }
            }
            if (parts.size() < 2) continue;
            Collections.reverse(parts);
            Collections.reverse(ids);
            StringBuilder t = new StringBuilder();
            int total = 0;
            for (short[] x : ids) total += x.length;
            short[] all = new short[total];
            int o = 0;
            for (int i = 0; i < parts.size(); i++) { t.append(parts.get(i)); System.arraycopy(ids.get(i), 0, all, o, ids.get(i).length); o += ids.get(i).length; }
            bestS = dp[n];
            best = new Cand(t.toString(), all, E);
            path[0] = pi;
        }
        return best;
    }

    // ================= 自己的词 =================
    private static void rebuild() {
        List<Uw> l = new ArrayList<>(learned.size() + names.size());
        l.addAll(learned);
        l.addAll(names);
        users = l.toArray(new Uw[0]);
    }

    /** 歌名、歌手名里连着的汉字（2～MAX_WORD 个）按 ICU 的拼音加进词库（后台线程） */
    private static void addNames(List<String> list) {
        Dict d = D;
        if (d == null || list.hashCode() == namesSig) return;
        namesSig = list.hashCode();
        if (han == null) { try { han = android.icu.text.Transliterator.getInstance("Han-Latin"); } catch (Throwable e) { return; } }
        LinkedHashMap<String, Uw> out = new LinkedHashMap<>();
        for (String s : list) {
            if (s == null) continue;
            for (int i = 0, n = s.length(); i < n && out.size() < MAX_NAMES; ) {
                if (!isHan(s.charAt(i))) { i++; continue; }
                int j = i;
                while (j < n && isHan(s.charAt(j))) j++;
                String w = s.substring(i, j);
                i = j;
                if (w.length() < 2 || w.length() > MAX_WORD || out.containsKey(w)) continue;
                short[] ids;
                try { ids = ids(d, ((android.icu.text.Transliterator) han).transliterate(w), w.length()); } catch (Throwable e) { ids = null; }
                if (ids != null) out.put(w, new Uw(w, ids, NAME_F, 0, 0, false));
            }
        }
        synchronized (LOCK) {
            names.clear();
            names.addAll(out.values());
            rebuild();
        }
    }

    private static boolean isHan(char c) { return c >= 0x4e00 && c <= 0x9fff || c >= 0x3400 && c <= 0x4dbf; }

    /** ICU 转出来的拼音（带声调，空格隔开）→ 音节编号；ü 写成 v。个数对不上、有不认识的音节返回空 */
    private static short[] ids(Dict d, String py, int n) {
        String t = Normalizer.normalize(py, Normalizer.Form.NFD);
        List<String> toks = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = Character.toLowerCase(t.charAt(i));
            if (c >= 'a' && c <= 'z') {
                if (c == 'u' && i + 1 < t.length() && t.charAt(i + 1) == (char) 0x308) c = 'v';
                b.append(c);
            } else if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            } else if (b.length() > 0) { toks.add(b.toString()); b.setLength(0); }
        }
        if (b.length() > 0) toks.add(b.toString());
        if (toks.size() != n) return null;
        short[] r = new short[n];
        for (int i = 0; i < n; i++) { Integer id = d.id.get(toks.get(i)); if (id == null) return null; r[i] = (short) (int) id; }
        return r;
    }

    /** 选了一个词（一串拼音逐字选完的，把这几个字连起来记一个词）：以后排在前面（GL 线程） */
    static void learn(String w, short[] ids) {
        if (D == null || w == null || w.isEmpty() || w.length() > MAX_WORD || ids == null || ids.length != w.length()) return;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            int n = 1;
            for (int i = 0; i < learned.size(); i++) {
                Uw u = learned.get(i);
                if (u.w.equals(w) && java.util.Arrays.equals(u.ids, ids)) { n = u.n + 1; learned.remove(i); break; }
            }
            learned.add(0, new Uw(w, ids, LEARN_F + LEARN_STEP * Math.min(n, 6), n, now, true));
            while (learned.size() > MAX_LEARN) learned.remove(learned.size() - 1);
            rebuild();
        }
        EX.execute(Pinyin::saveLearned);
    }

    private static void readLearned(Dict d) {
        try {
            JSONArray a = new JSONArray(app.getSharedPreferences("lg", Context.MODE_PRIVATE).getString("ime", "[]"));
            List<Uw> l = new ArrayList<>();
            Map<String, Boolean> seen = new HashMap<>();
            for (int i = 0; i < a.length() && l.size() < MAX_LEARN; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String w = o.optString("w", ""), p = o.optString("p", "");
                int n = Math.max(1, Math.min(1000, o.optInt("n", 1)));
                if (w.isEmpty() || w.length() > MAX_WORD) continue;
                String[] ps = p.trim().split(" ");
                if (ps.length != w.length()) continue;
                short[] ids = new short[ps.length];
                boolean ok = true;
                for (int j = 0; j < ps.length && ok; j++) { Integer id = d.id.get(ps[j]); if (id == null) ok = false; else ids[j] = (short) (int) id; }
                if (!ok || seen.put(w + "|" + p, true) != null) continue;
                l.add(new Uw(w, ids, LEARN_F + LEARN_STEP * Math.min(n, 6), n, o.optLong("t", 0), true));
            }
            synchronized (LOCK) { learned.clear(); learned.addAll(l); rebuild(); }
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "记住的词读不出来：" + e);
        }
    }

    private static void saveLearned() {
        Dict d = D;
        if (d == null) return;
        JSONArray a = new JSONArray();
        synchronized (LOCK) {
            try {
                for (Uw u : learned) {
                    StringBuilder p = new StringBuilder();
                    for (short id : u.ids) { if (p.length() > 0) p.append(' '); p.append(d.syl[id]); }
                    a.put(new JSONObject().put("w", u.w).put("p", p.toString()).put("n", u.n).put("t", u.t));
                }
            } catch (Exception ignored) { }
        }
        SharedPreferences.Editor e = app.getSharedPreferences("lg", Context.MODE_PRIVATE).edit();
        e.putString("ime", a.toString()).apply();
    }
}
