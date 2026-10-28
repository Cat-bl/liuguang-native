package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 琉光的媒体数据：把宿主的媒体快照整理成画面要的样子。照网页版 lyric3d/media.js 搬过来的（那套在实车上验证过）：
 * - 当前句用快照里的 lyric（宿主每换一句推一次）；positionMs 只在开始、暂停、拖进度时更新，平时不走——所以播放位置自己记起点：
 *   positionMs / 播放状态变了、或者换句（用这句在整首里的时间，最准）时重定，之后按经过的时间往后推。
 * - 整首歌词每首取一次，核对 trackId；刚换歌时宿主常常还没准备好（返回空），隔 1.5 秒再取，最多 3 次，之后隔 10 秒、下次换句时再试。
 *   播放器不给整首歌词时，用听到过的句子排成一列。
 * - 换歌时宿主头几条快照的歌词靠不住（上一首最后那句、或者按上一首进度在新歌里算出来的后面某句）：
 *   亲眼看到换歌后，句子要在整首里、时间和"换歌后过了多久"对得上（差 10 秒内）或者连着两句按顺序往下走才算；整首还没拿到先压着（最多 6 秒）。
 * - 连着两句一模一样时宿主推来的歌词没变化，认不出换了句：下一句和这句一样就按时间自己往下走一句。
 * - 停下来先等 3.5 秒再算暂停（一首放完自动接下一首时宿主中间会短暂报一次没在放；刚在这里按过暂停的不等）。
 * - 封面按"地址 + 曲目"认：换歌时宿主常常还是上一首的地址、或者封面文件还没换好，所以地址和上一首一样时 2 秒后再取一次，内容变了才换。
 *   换歌的空档宿主先推空封面，不收；取不到用按歌名生成的封面。
 * - 播放控制：按下去先按按下去的显示（最多 4 秒），宿主推来真实状态再对齐，失败变回去。
 * - 收藏红心、跳到某一句宿主没有，直接连播放器做（Player）：红心由 MainActivity 填进 heart；跳过去先按跳到的位置显示（seekLocal）。
 * 全在主线程里用；读封面在调用方给的线程里。
 */
final class MediaState {
    interface Listener { void changed(); }

    // ---------- 画面读的 ----------
    boolean on, playing, full;
    String track, title = "", artist = "";
    String[] lines = new String[0];
    long[] times; // 每句开始的时间，和 lines 对齐（宿主给了整首歌词才有；歌词定位线用）
    int cur = -1;
    long dur;
    Bitmap cover;
    String coverTrack;
    int revTrack, revLines, revCover, revCur;
    String lastCmd = "", trackCmd = ""; // trackCmd：换歌前最后按的是什么（封面往哪边翻）
    String pkg; // 宿主说的播放器包名
    int heart = Player.HIDE; // 红心（Player.heart）

    private final Uiplus host;
    private final Handler main = new Handler(Looper.getMainLooper()), io;
    private final Listener listener;
    private JSONObject media;
    private Lyr lyr;
    private String posTrack;
    private long posAt, posVal;
    private boolean posSet;
    private Runnable pauseTimer, lyrRetry, repeat;
    private Boolean pressed;
    private final Runnable pressedTimer = () -> { pressed = null; playing = effPlaying(); emit(); };
    private long userPauseAt;
    private int gen;
    private long seekedAt = -1; // 刚在这里跳到某一句（seekLocal）的时候：之后几秒宿主推来的歌词、进度可能还是跳之前的

    private static final class Line { final String t; final long at; Line(String t, long at) { this.t = t; this.at = at; } }

    private static final class Lyr {
        String track, line = "", stale = "", hold = "";
        List<Line> lines;
        int idx = -1, tries, holdIdx = -2;
        long asked, start = -1;
        final List<String> heard = new ArrayList<>();
    }

    MediaState(Uiplus host, Handler io, Listener listener) {
        this.host = host;
        this.io = io;
        this.listener = listener;
    }

    private static long now() { return SystemClock.elapsedRealtime(); }
    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    private static String str(JSONObject d, String k) { return d == null || d.isNull(k) ? null : d.optString(k, null); }
    private void emit() { listener.changed(); }

    /** 换会话（重连）时：订阅推来的第一条当初始快照 */
    void reset() {
        gen++;
        media = null;
    }

    // ================= 播放位置 =================
    private boolean rawPlaying() { return media != null && media.optBoolean("playing"); }
    private void anchor(long p) { posTrack = track; posVal = p; posAt = now(); posSet = true; }

    long pos() {
        if (media == null || !posSet || posTrack == null || !posTrack.equals(track)) return 0;
        long p = posVal + (rawPlaying() ? now() - posAt : 0);
        return Math.max(0, dur > 0 ? Math.min(p, dur) : p);
    }

    // ================= 歌词 =================
    private void newTrack(String id, String stale, boolean live) {
        if (lyrRetry != null) main.removeCallbacks(lyrRetry);
        stopRepeat();
        seekedAt = -1;
        lyr = new Lyr();
        lyr.track = id;
        lyr.stale = stale == null ? "" : stale;
        lyr.start = live ? now() : -1;
        lines = new String[0];
        times = null;
        full = false;
        cur = -1;
        revLines++;
        revCur++;
    }

    /** 这句能不能显示（见开头） */
    private boolean lyricOk(String t) {
        if (lyr.start < 0) {
            if (lyr.lines == null) return true;
            for (Line l : lyr.lines) if (l.t.equals(t)) return true;
            return false;
        }
        if (lyr.lines == null) {
            if (now() - lyr.start < 6000) { lyr.hold = t; return false; }
            lyr.start = -1;
            return true;
        }
        long due = now() - lyr.start + 10000;
        int last = -1;
        for (int i = 0; i < lyr.lines.size(); i++) {
            Line l = lyr.lines.get(i);
            if (!l.t.equals(t)) continue;
            if (l.at <= due || i == lyr.holdIdx + 1) { lyr.start = -1; lyr.idx = i - 1; lyr.hold = ""; return true; }
            if (last < 0) last = i;
        }
        if (last >= 0) { lyr.hold = t; lyr.holdIdx = last; }
        return false;
    }

    private boolean justSeeked() { return seekedAt >= 0 && now() - seekedAt < 4000; }

    /** 当前这句在整首里是第几句：从上一句往后找（副歌重复的按顺序对上），找不到再从头找（拖了进度、单曲循环）。
     *  刚跳过（4 秒内）：正在唱的那句是在现在的位置之前开始的，只认这样的（往前 12 秒、往后差 1.5 秒以内，取最晚的那处）；
     *  对不上的是跳之前那句（宿主先推一次旧的），不认——不然同样的句子唱了几遍时会认到后面那遍 */
    private int locate(String t) {
        List<Line> L = lyr.lines;
        if (justSeeked()) {
            long p = pos();
            int best = -1;
            for (int k = 0; k < L.size(); k++) {
                long at = L.get(k).at;
                if (L.get(k).t.equals(t) && at <= p + 1500 && at >= p - 12000 && (best < 0 || at > L.get(best).at)) best = k;
            }
            return best;
        }
        int from = lyr.idx, i;
        for (i = from + 1; i < L.size(); i++) if (L.get(i).t.equals(t)) return i;
        for (i = 0; i <= from && i < L.size(); i++) if (L.get(i).t.equals(t)) return i;
        return -1;
    }

    private void setLine(String t) {
        lyr.line = t;
        if (lyr.lines != null) {
            int i = locate(t);
            if (i < 0) return;
            lyr.idx = i;
            if (!justSeeked()) anchor(lyr.lines.get(i).at); // 刚跳过：播放器不一定正好落在句首（常常早一点、报的还是上一句），进度以跳到的位置为准
            cur = i;
            chainRepeat();
        } else { // 没有整首：听到的新句子接在后面
            if (lyr.heard.isEmpty() || !lyr.heard.get(lyr.heard.size() - 1).equals(t)) {
                lyr.heard.add(t);
                if (lyr.heard.size() > 200) lyr.heard.remove(0);
                lines = lyr.heard.toArray(new String[0]);
                revLines++;
            }
            cur = lines.length - 1;
        }
        revCur++;
    }

    /** 下一句和这句一模一样：宿主推来的歌词没变化、认不出换了句，到下一句的时间自己往下走 */
    private void chainRepeat() {
        stopRepeat();
        final Lyr mine = lyr;
        if (mine == null || mine.lines == null || !rawPlaying()) return;
        final int i = mine.idx;
        if (i < 0 || i + 1 >= mine.lines.size() || !mine.lines.get(i + 1).t.equals(mine.lines.get(i).t)) return;
        long wait = Math.max(0, mine.lines.get(i + 1).at - pos());
        repeat = () -> {
            repeat = null;
            if (lyr != mine || mine.idx != i || !rawPlaying()) return;
            mine.idx = i + 1;
            anchor(mine.lines.get(i + 1).at);
            cur = i + 1;
            revCur++;
            emit();
            chainRepeat();
        };
        main.postDelayed(repeat, wait);
    }

    private void stopRepeat() { if (repeat != null) { main.removeCallbacks(repeat); repeat = null; } }

    private void loadLyrics() {
        if (media == null || str(media, "trackId") == null || lyr == null || lyr.lines != null || now() - lyr.asked < 10000 || !host.ready()) return;
        lyr.asked = now();
        final int g = gen;
        final String id = str(media, "trackId");
        final Lyr mine = lyr;
        host.request("media.getLyrics", null, (r, code, msg) -> {
            if (g != gen || lyr != mine) return;
            JSONArray arr = r == null ? null : r.optJSONArray("lines");
            if (r == null || !id.equals(str(r, "trackId")) || arr == null || arr.length() == 0) {
                if (++mine.tries <= 3) {
                    if (lyrRetry != null) main.removeCallbacks(lyrRetry);
                    lyrRetry = () -> { if (lyr == mine) { mine.asked = 0; loadLyrics(); } };
                    main.postDelayed(lyrRetry, 1500);
                }
                return;
            }
            List<Line> L = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String t = clean(str(o, "text"));
                if (!t.isEmpty()) L.add(new Line(t, o.optLong("timeMs")));
            }
            if (L.isEmpty()) return;
            mine.lines = L;
            String[] s = new String[L.size()];
            for (int i = 0; i < s.length; i++) s[i] = L.get(i).t;
            lines = s;
            long[] tm = new long[L.size()];
            for (int i = 0; i < tm.length; i++) tm[i] = L.get(i).at;
            times = tm;
            full = true;
            cur = -1;
            revLines++;
            if (!mine.line.isEmpty()) {
                mine.idx = -1;
                int i = locate(mine.line);
                if (i >= 0) { mine.idx = i; cur = i; anchor(L.get(i).at); chainRepeat(); } else mine.line = ""; // 正在显示的那句不在这首里：不是这首的
            } else if (!mine.hold.isEmpty() && rawPlaying()) {
                String h = mine.hold;
                mine.hold = "";
                if (lyricOk(h)) setLine(h);
            }
            revCur++;
            emit();
        });
    }

    // ================= 封面 =================
    // 换歌后宿主头一两秒给的封面常常还是上一首的（同一个封面缓存文件、内容还没换，地址也一样）：
    // 取到的和上一首的封面一模一样时先不用，每 0.3 秒再取一次，换成新图了才算这首的；3 秒还一样就当真是同一张（同一张专辑）。
    // 没封面地址时也先等 3 秒（宿主封面没对上这首时不给地址），还没有才按歌名生成。场景等这首的封面认准了才翻面（coverTrack 等于 track）
    private static final long COVER_WAIT = 3000;
    private String coverKey = "", coverUrl = "", coverTrackSeen, hashTrack;
    private int coverSeq;
    private long coverHash = Long.MIN_VALUE, trackAt; // 认准的那张封面的内容指纹（生成的是 MIN_VALUE）
    private Runnable coverRetry;

    private static long hashOf(byte[] b) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(b);
        return (c.getValue() << 24) ^ b.length;
    }

    private void setCover(Bitmap b, String key) {
        if (!key.equals(coverKey)) return;
        cover = b;
        coverTrack = track;
        revCover++;
        emit();
    }

    /** onlyIfChanged：认准以后再看一眼，内容变了才换 */
    private void fetchCover(final String url, final String key, final boolean onlyIfChanged) {
        final int seq = ++coverSeq;
        final String t = title, a = artist;
        if (coverRetry != null) main.removeCallbacks(coverRetry);
        io.post(() -> {
            byte[] bytes = read(host.openCover(url));
            final long h = bytes == null ? 0 : hashOf(bytes);
            final boolean stale = bytes != null && !onlyIfChanged && h == coverHash; // 和认准的那张一模一样
            final boolean skip = bytes != null && onlyIfChanged && h == coverHash;
            // 一样的就不用解码了
            final Bitmap b = bytes == null || stale || skip ? null : square(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
            final int size = bytes == null ? -1 : bytes.length;
            main.post(() -> {
                if (seq != coverSeq || !coverKey.equals(key)) return;
                Log.w(Uiplus.TAG, "cover " + url + (onlyIfChanged ? "（再取）" : "") + " → " + (bytes == null ? "没取到" : size + " 字节" + (stale || skip ? "（和认准的一样）" : "")));
                if (skip) return; // 再取一次还是同一张：不动
                if (stale) {
                    if (!java.util.Objects.equals(track, hashTrack) && SystemClock.uptimeMillis() - trackAt < COVER_WAIT) { // 换了歌还是上一首那张：多半还没换过来，过一会儿再取
                        coverRetry = () -> { if (coverKey.equals(key)) fetchCover(url, key, false); };
                        main.postDelayed(coverRetry, 300);
                        return;
                    }
                    hashTrack = track; // 等够了还一样：真是同一张（同一张专辑），沿用
                    if (cover != null) setCover(cover, key);
                    return;
                }
                if (b == null) { if (!onlyIfChanged) { coverHash = Long.MIN_VALUE; hashTrack = track; setCover(makeCover(t, a), key); } return; }
                coverHash = h;
                hashTrack = track;
                setCover(b, key);
                coverRetry = () -> { if (coverKey.equals(key)) fetchCover(url, key, true); }; // 认准以后再看一眼（宿主有时过一会儿才换内容）
                main.postDelayed(coverRetry, 2000);
            });
        });
    }

    private static byte[] read(ParcelFileDescriptor fd) {
        if (fd == null) return null;
        try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "读封面", e);
            return null;
        } finally {
            try { fd.close(); } catch (Exception ignored) { }
        }
    }

    /** 画成 512 的方形（不是方的居中裁） */
    private static Bitmap square(Bitmap src) {
        if (src == null) return null;
        int n = 512, w = src.getWidth(), h = src.getHeight();
        float s = Math.max(n / (float) w, n / (float) h);
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int dw = Math.round(w * s), dh = Math.round(h * s);
        c.drawBitmap(src, null, new Rect((n - dw) / 2, (n - dh) / 2, (n + dw) / 2, (n + dh) / 2), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    /** 没有封面时：按歌名生成一张（渐变 + 光斑 + 音符 + 歌名），同网页版 */
    static Bitmap makeCover(String title, String artist) {
        int n = 512;
        String key = (title == null ? "" : title) + "|" + (artist == null ? "" : artist);
        int h = 7;
        for (int i = 0; i < key.length(); i++) h = h * 31 + key.charAt(i);
        float hue = Math.abs(h) % 360;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, n, n, hsl(hue, 0.62f, 0.46f, 1), hsl((hue + 50) % 360, 0.58f, 0.22f, 1), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(new RadialGradient(n * 0.7f, n * 0.3f, n * 0.6f, hsl((hue + 20) % 360, 0.8f, 0.75f, 0.55f), hsl(hue, 0.8f, 0.6f, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(null);
        p.setColor(0xd9ffffff);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(200);
        c.drawText("♪", n * 0.5f, n * 0.46f + 70, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(38);
        String t = title == null || title.isEmpty() ? "没有在放的歌" : title;
        while (t.length() > 1 && p.measureText(t) > n - 68) t = t.substring(0, t.length() - 1);
        c.drawText(t, 34, n - 40, p);
        return b;
    }

    private static int hsl(float h, float s, float l, float a) {
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        float r = hue(p, q, h / 360f + 1f / 3), g = hue(p, q, h / 360f), bl = hue(p, q, h / 360f - 1f / 3);
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (bl * 255);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private void syncCover() {
        if (media == null) { if (!"none".equals(coverKey)) { coverKey = "none"; setCover(makeCover("", ""), "none"); } return; }
        String url = str(media, "coverUrl");
        if (url == null && pauseTimer != null) return; // 换歌的空档：先别收
        if (url == null) url = "";
        final String key = (url.isEmpty() ? "gen" : url) + "#" + (track == null ? "" : track);
        if (key.equals(coverKey)) return;
        if (track != null && !track.equals(coverTrackSeen)) { coverTrackSeen = track; trackAt = SystemClock.uptimeMillis(); }
        coverKey = key;
        coverUrl = url;
        if (coverRetry != null) main.removeCallbacks(coverRetry);
        if (url.isEmpty()) { // 没地址：刚换歌时先等等宿主给地址
            final String t = title, a = artist;
            Runnable gen = () -> { if (coverKey.equals(key)) { coverHash = Long.MIN_VALUE; hashTrack = track; setCover(makeCover(t, a), key); } };
            long wait = hashTrack != null && !hashTrack.equals(track) ? COVER_WAIT - (SystemClock.uptimeMillis() - trackAt) : 0;
            if (wait > 0) { coverRetry = gen; main.postDelayed(gen, wait); } else gen.run();
            return;
        }
        fetchCover(url, key, false);
    }

    // ================= 媒体快照 =================
    private boolean effPlaying() { return pressed != null ? pressed : rawPlaying() || pauseTimer != null; }

    /** 宿主推来的媒体快照（订阅推送的 data，或者 media.get 的结果）；initial：这个会话的第一条 */
    void onMedia(JSONObject m, boolean initial) {
        if (m == null) return;
        JSONObject prev = media;
        if (!initial && prev != null && m.optLong("updatedAt") > 0 && prev.optLong("updatedAt") > 0 && m.optLong("updatedAt") < prev.optLong("updatedAt")) return; // 晚到的旧快照
        media = "ok".equals(m.optString("status")) ? m : null;
        boolean was = prev != null && prev.optBoolean("playing"), onNow = rawPlaying();
        String tr = media == null ? null : str(media, "trackId") != null ? str(media, "trackId") : str(media, "title");
        String prevTr = prev == null ? null : str(prev, "trackId") != null ? str(prev, "trackId") : str(prev, "title");
        on = media != null;
        if (!eq(tr, track) || lyr == null) {
            track = tr;
            newTrack(tr, prev == null ? null : clean(str(prev, "lyric")), !initial && prev != null);
            revTrack++;
            trackCmd = lastCmd;
            lastCmd = "";
        }
        title = media == null ? "" : clean(str(media, "title")).isEmpty() ? "未知歌曲" : clean(str(media, "title"));
        artist = media == null ? "" : clean(str(media, "artist"));
        pkg = media == null ? null : str(media, "packageName");
        dur = media != null && media.optLong("durationMs") > 0 ? media.optLong("durationMs") : 0;
        String raw = media == null ? "" : clean(str(media, "lyric"));
        if (!lyr.stale.isEmpty() && !raw.equals(lyr.stale)) lyr.stale = ""; // 歌词变了：上一首的那句过去了
        boolean hasPos = media != null && media.has("positionMs") && !media.isNull("positionMs");
        long mp = hasPos ? media.optLong("positionMs") : 0;
        boolean posChanged = prev == null || !hasPos || prev.isNull("positionMs") || prev.optLong("positionMs") != mp;
        if (media != null && (!eq(tr, prevTr) || prev == null || posChanged || onNow != was)) {
            if (prev != null && eq(tr, prevTr) && was && !onNow && posSet) anchor(pos()); // 暂停：停在当前位置
            else if (hasPos && (!eq(tr, prevTr) || prev == null || posChanged || !posSet)) {
                if (!(eq(tr, prevTr) && justSeeked() && Math.abs(mp - pos()) > 4000)) anchor(mp); // 刚跳过、推来的还是跳之前的进度：不认
            }
            else if (posSet) posAt = now(); // 接着放
        }
        if (pressed != null && media != null && onNow == pressed) { pressed = null; main.removeCallbacks(pressedTimer); } // 宿主推来的和按下去的对上了
        if (onNow) { if (pauseTimer != null) { main.removeCallbacks(pauseTimer); pauseTimer = null; } }
        else if (!initial && was && pauseTimer == null && now() - userPauseAt > 5000) {
            pauseTimer = () -> { pauseTimer = null; playing = effPlaying(); syncCover(); emit(); };
            main.postDelayed(pauseTimer, 3500);
        }
        if (onNow) {
            loadLyrics();
            String t = raw.equals(lyr.stale) ? "" : raw; // 上一首留下的那句不显示
            if (!t.isEmpty() && !t.equals(lyr.line) && lyricOk(t)) setLine(t);
            else chainRepeat(); // 接着放、拖了进度：重新算什么时候往下走
        } else stopRepeat();
        playing = effPlaying();
        syncCover();
        emit();
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** 交给绘制线程的一份（不会再变）：主线程每次变了就做一份新的 */
    static final class Snap {
        boolean on, playing, full, rawPlaying, canPlay;
        String track, title = "", artist = "", trackCmd = "";
        String[] lines = new String[0];
        long[] times;
        int cur = -1, revTrack, revLines, revCover, revCur, heart = Player.HIDE;
        long dur, posBase, posAt;
        Bitmap cover;
        String coverTrack;

        /** 播放位置：做这份快照时的位置 + 之后经过的时间（在放才走） */
        long pos() {
            long p = posBase + (rawPlaying ? SystemClock.elapsedRealtime() - posAt : 0);
            return Math.max(0, dur > 0 ? Math.min(p, dur) : p);
        }
    }

    Snap snap() {
        Snap s = new Snap();
        s.on = on; s.playing = playing; s.full = full; s.rawPlaying = rawPlaying(); s.canPlay = host.canExec();
        s.track = track; s.title = title; s.artist = artist; s.trackCmd = trackCmd;
        s.lines = lines; s.times = times; s.cur = cur;
        s.revTrack = revTrack; s.revLines = revLines; s.revCover = revCover; s.revCur = revCur;
        s.dur = dur; s.posBase = pos(); s.posAt = now();
        s.cover = cover; s.coverTrack = coverTrack;
        s.heart = heart;
        return s;
    }

    // ================= 跳到某一句（歌词定位线） =================
    /** 跳过去：先按跳到的位置显示，歌词滚到那一句（宿主随后推来新的 positionMs、新的那句再对齐） */
    void seekLocal(long ms) {
        if (media == null) return;
        anchor(ms);
        seekedAt = now();
        if (lyr != null && lyr.lines != null) {
            int i = -1;
            for (int k = 0; k < lyr.lines.size() && lyr.lines.get(k).at <= ms; k++) i = k;
            if (i >= 0) { lyr.idx = i; lyr.line = lyr.lines.get(i).t; cur = i; revCur++; }
            chainRepeat();
        }
        emit();
    }

    // ================= 播放控制 =================
    /** cmd：toggle / next / previous；失败时 reply 带错误码 */
    void press(String cmd, Uiplus.Reply reply) {
        String m = "toggle".equals(cmd) ? "media.togglePlayPause" : "next".equals(cmd) ? "media.next" : "media.previous";
        lastCmd = cmd;
        final boolean toggle = "toggle".equals(cmd);
        if (toggle) {
            boolean want = !playing;
            if (!want) userPauseAt = now(); // 自己按的暂停：不等宽限
            pressed = want;
            main.removeCallbacks(pressedTimer);
            if (pauseTimer != null) { main.removeCallbacks(pauseTimer); pauseTimer = null; }
            main.postDelayed(pressedTimer, 4000);
            playing = want;
            emit();
        }
        host.request(m, null, (r, code, msg) -> {
            if (code != null && toggle) { pressed = null; main.removeCallbacks(pressedTimer); playing = effPlaying(); emit(); } // 失败：变回去
            if (reply != null) reply.done(r, code, msg);
        });
    }
}
