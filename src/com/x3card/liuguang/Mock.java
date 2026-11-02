package com.x3card.liuguang;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器里用的假数据：车上录下来的「多媒体」播放队列、几首的整首歌词和封面（仓库里的 _apk/liuguang/mock，不提交、不导出、不打进发布的 APK），
 * 用 adb 推到应用自己的目录（/sdcard/Android/data/com.x3card.liuguang/files/mock/）。那里有 playlist.json、又不在车上（没有车机媒体控制器）时才用：
 * 冒充宿主（媒体快照、整首歌词、封面、播放控制；Uiplus 转过来）和车机的媒体控制器（歌单、正在放哪首、红心；BydMedia 转过来），
 * 点歌、收藏、跳到某一句（Player 那几样）也在这里演。照车上录到的规律演：点了歌 0.6 秒后宿主换歌、再过 0.2 秒控制器推新的 metadata；
 * 宿主每换一句推一次快照，positionMs 只在开始、暂停、跳的时候变；一首放完接下一首。歌单里的封面照常联网取（同车上）。全在主线程里
 */
final class Mock {
    private static final String TAG = Uiplus.TAG;

    static Mock open(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) return null;
        File d = new File(base, "mock");
        if (!new File(d, "playlist.json").isFile()) return null;
        try { c.getPackageManager().getPackageInfo("com.byd.mediacontroller", 0); return null; } // 在车上：不用
        catch (PackageManager.NameNotFoundException ok) { /* 不在车上 */ }
        try { return new Mock(d); }
        catch (Exception e) { Log.w(TAG, "mock 读不出来", e); return null; }
    }

    private static final class Ln { final String t; final long at; Ln(String t, long at) { this.t = t; this.at = at; } }

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<BydMedia.Song> all; // 录到的整个队列（认封面用）
    private List<BydMedia.Song> songs; // 现在的播放队列（曲库换一批歌会换掉）
    private final Map<String, String> coverKey = new HashMap<>(); // 歌单里的封面网址 → 本地文件名
    private final Map<String, Boolean> fav = new HashMap<>();
    private int cur;
    private boolean playing = true;
    private long posBase, posAt, snapPos;
    private List<Ln> lines = Collections.emptyList();
    private int lineIdx = -1;
    private Uiplus.Listener host;
    private String mediaSub;
    private BydMedia byd;

    private Mock(File dir) throws Exception {
        this.dir = dir;
        JSONArray a = new JSONArray(read(new File(dir, "playlist.json")));
        List<BydMedia.Song> list = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String id = o.optString("id");
            list.add(new BydMedia.Song(id, o.optString("title").trim(), o.optString("artist").trim(), o.optString("coverUrl").trim(), o.optLong("duration")));
            fav.put(id, o.optBoolean("isFavorite"));
        }
        songs = all = Collections.unmodifiableList(list);
        File idx = new File(dir, "covers/index.json");
        if (idx.isFile()) { JSONObject m = new JSONObject(read(idx)); for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) { String k = it.next(); coverKey.put(k, m.getString(k)); } }
        cur = 0;
        for (int i = 0; i < songs.size(); i++) if (new File(dir, "songs/" + songs.get(i).id + ".json").isFile()) { cur = i; break; } // 从第一首有歌词的开始
        posAt = now();
        loadLines();
        Log.w(TAG, "用模拟数据：" + songs.size() + " 首，从第 " + cur + " 首开始");
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static long now() { return SystemClock.elapsedRealtime(); }
    private BydMedia.Song song() { return songs.get(cur); }
    private long pos() { long d = song().dur; long p = posBase + (playing ? now() - posAt : 0); return d > 0 ? Math.min(p, d) : p; }

    private void loadLines() {
        lines = new ArrayList<>();
        lineIdx = -1;
        File f = new File(dir, "songs/" + song().id + ".json");
        if (!f.isFile()) return;
        try {
            JSONArray a = new JSONObject(read(f)).optJSONArray("lyrics");
            for (int i = 0; a != null && i < a.length(); i++) { JSONObject o = a.getJSONObject(i); lines.add(new Ln(o.optString("text"), o.optLong("timeMs"))); }
        } catch (Exception e) { Log.w(TAG, "mock 歌词", e); }
    }

    // ================= 宿主那头（Uiplus 转过来） =================
    void attachHost(Uiplus.Listener l) { host = l; schedule(); }

    private JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            if (songs.isEmpty()) return o.put("status", "ok").put("packageName", "com.byd.mediacenter").put("playing", false).put("title", "").put("updatedAt", System.currentTimeMillis());
            BydMedia.Song s = song();
            o.put("status", "ok").put("trackId", s.id).put("title", s.title).put("artist", s.artist).put("packageName", "com.byd.mediacenter")
                .put("durationMs", s.dur).put("positionMs", snapPos).put("playing", playing)
                .put("lyric", lineIdx >= 0 && lineIdx < lines.size() ? lines.get(lineIdx).t : "")
                .put("coverUrl", "https://media.uiplus.invalid/mk" + s.id).put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return o;
    }

    private void push() {
        if (host == null || mediaSub == null) return;
        try { host.onEvent(new JSONObject().put("subscriptionId", mediaSub).put("data", snapshot())); } catch (Exception ignored) { }
    }

    void request(final String method, final JSONObject params, final Uiplus.Reply r) {
        main.post(() -> {
            JSONObject res = new JSONObject();
            try {
                boolean none = songs.isEmpty(); // 这个音源没有歌：播放控制什么都不做
                switch (method) {
                    case "media.subscribe": mediaSub = params == null ? null : params.optString("subscriptionId", null); break;
                    case "media.get": res = snapshot(); break;
                    case "media.getLyrics": {
                        if (none) break;
                        JSONArray a = new JSONArray();
                        for (Ln l : lines) a.put(new JSONObject().put("text", l.t).put("timeMs", l.at));
                        res.put("trackId", song().id).put("lines", a);
                        break;
                    }
                    case "capabilities": res.put("fields", new JSONArray()); break; // 没有日夜模式：按钟点
                    case "media.togglePlayPause": if (!none) setPlaying(!playing); break;
                    case "media.play": if (!none) setPlaying(true); break;
                    case "media.pause": if (!none) setPlaying(false); break;
                    case "media.next": if (!none) track((cur + 1) % songs.size(), 300); break;
                    case "media.previous": if (!none) track((cur - 1 + songs.size()) % songs.size(), 300); break;
                    case "vehicle.subscribe": case "authorization.request": break;
                    default: if (r != null) r.done(null, "UNSUPPORTED", "模拟数据没有 " + method); return;
                }
            } catch (Exception e) { if (r != null) r.done(null, "DEVICE_ERROR", String.valueOf(e)); return; }
            if (r != null) r.done(res, null, null);
        });
    }

    /** 封面：令牌 mk<歌的 id> → 录到的那首的 300 封面，没有就用歌单里那张的 500 / 300 / 120 */
    ParcelFileDescriptor cover(String url) {
        if (url == null) return null;
        String id = url.substring(url.lastIndexOf('/') + 1).replaceFirst("^mk", "");
        File f = new File(dir, "art/" + id + ".png");
        if (!f.isFile()) {
            String key = null;
            for (BydMedia.Song s : all) if (s.id.equals(id)) { key = coverKey.get(s.cover); break; }
            if (key != null) for (String sz : new String[] { "500", "300", "120" }) { f = new File(dir, "covers/" + sz + "/" + key + ".jpg"); if (f.isFile()) break; }
        }
        try { return f.isFile() ? ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) : null; } catch (Exception e) { return null; }
    }

    // ================= 车机媒体控制器那头（BydMedia 转过来） =================
    private static final int[] SOURCES = { 10, 12, 5, 6, 7, 8, 4, 2, 3, 1 }; // 车上录到的音源列表
    private static final String[] SRC_NAME = { "", "本地音乐", "U盘", "SD卡", "蓝牙音乐", "酷狗音乐", "酷我音乐", "喜马拉雅", "云听", "收音机", "QQ音乐", "", "网易云音乐" };
    private int src = 10, mode = 1; // 车上录的时候：QQ 音乐、随机播放

    void attachByd(BydMedia b) {
        byd = b;
        if (b == null) return;
        main.post(() -> {
            if (byd == null) return;
            byd.onSources(SOURCES);
            byd.onIcons(icons());
            byd.onSource("com.byd.mediacenter", src, SRC_NAME[src]);
            byd.onList(songs, cur);
            meta();
        });
    }

    /** 车上录到的音源图标：icons/0xx-srcicon-<音源号>.png */
    private android.util.SparseArray<android.graphics.Bitmap> icons() {
        android.util.SparseArray<android.graphics.Bitmap> m = new android.util.SparseArray<>();
        File[] fs = new File(dir, "icons").listFiles();
        if (fs != null) for (File f : fs) {
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("srcicon-(\\d+)\\.png$").matcher(f.getName());
            if (!x.find()) continue;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (bm != null) m.put(Integer.parseInt(x.group(1)), bm);
        }
        return m;
    }

    private void meta() {
        if (byd == null) return;
        if (songs.isEmpty()) { byd.onMetadata("", null, null, null, "", 0, -1); return; }
        byd.onMetadata(song().title, song().id, fav.get(song().id), null, song().artist, song().dur, mode);
    }

    void playMode(final int m) { main.postDelayed(() -> { mode = m; meta(); }, 300); }

    /** 切音源：QQ 音乐回到录到的队列；蓝牙、本地这类没有歌；别的在线音源随便挑一批 */
    void changeSource(final int s) {
        main.postDelayed(() -> {
            src = s;
            if (byd != null) byd.onSource("com.byd.mediacenter", s, s < SRC_NAME.length ? SRC_NAME[s] : "");
            if (s == 10) newQueue(all);
            else if (s == 1 || s == 2 || s == 3 || s == 4 || s == 9) newQueue(Collections.<BydMedia.Song>emptyList());
            else newQueue(pick("src" + s, 40));
        }, 500);
    }

    /** 曲库发给「多媒体」语音入口的命令：演成换一批歌（搜不到的、没有的就什么都不推，同车上） */
    void voice(String method, JSONObject p) {
        List<BydMedia.Song> q = null;
        if ("playFavorite".equals(method)) {
            q = new ArrayList<>();
            for (BydMedia.Song s : all) if (Boolean.TRUE.equals(fav.get(s.id))) q.add(s);
        } else if ("searchMusic".equals(method)) {
            String artist = p == null ? "" : p.optString("artist");
            if (!artist.isEmpty()) {
                q = new ArrayList<>();
                for (BydMedia.Song s : all) if (s.artist.contains(artist)) q.add(s);
            } else q = pick(String.valueOf(p), 30);
        } else if (method.startsWith("play")) q = pick(method, 50);
        if (q == null || q.isEmpty()) return;
        final List<BydMedia.Song> nq = q;
        main.postDelayed(() -> newQueue(nq), 600);
    }

    /** 从录到的队列里按 seed 挑 n 首（同一个 seed 每次一样） */
    private List<BydMedia.Song> pick(String seed, int n) {
        List<BydMedia.Song> l = new ArrayList<>(all);
        Collections.shuffle(l, new java.util.Random(seed.hashCode()));
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    private void newQueue(List<BydMedia.Song> q) {
        songs = Collections.unmodifiableList(new ArrayList<>(q));
        cur = 0;
        if (byd != null) byd.onList(songs, songs.isEmpty() ? -1 : 0);
        if (songs.isEmpty()) { playing = false; main.removeCallbacks(tick); push(); meta(); return; }
        track(0, 0);
    }

    // ================= 「多媒体」那几样（Player 转过来） =================
    void playIndex(int i) { if (i >= 0 && i < songs.size()) track(i, 600); }

    void favorite(final boolean on) {
        final String id = song().id;
        main.postDelayed(() -> { fav.put(id, on); if (song().id.equals(id)) meta(); }, 200);
    }

    void seek(long ms) {
        posBase = Math.max(0, ms); posAt = now(); snapPos = posBase;
        lineIdx = lineAt(posBase);
        push();
        schedule();
    }

    // ================= 放歌 =================
    private final Runnable tick = this::onTick;

    private void track(final int i, long delay) {
        main.postDelayed(() -> {
            cur = i;
            posBase = 0; posAt = now(); snapPos = 0; playing = true;
            loadLines();
            push();
            main.postDelayed(this::meta, 200);
            schedule();
        }, delay);
    }

    private void setPlaying(boolean on) {
        if (on == playing) return;
        posBase = pos(); posAt = now(); snapPos = posBase;
        playing = on;
        push();
        schedule();
    }

    private int lineAt(long p) { int k = -1; for (int i = 0; i < lines.size() && lines.get(i).at <= p; i++) k = i; return k; }

    private void schedule() {
        main.removeCallbacks(tick);
        if (!playing) return;
        long p = pos(), next = song().dur > 0 ? song().dur : Long.MAX_VALUE;
        if (lineIdx + 1 < lines.size()) next = Math.min(next, lines.get(lineIdx + 1).at);
        if (next == Long.MAX_VALUE) return;
        main.postDelayed(tick, Math.max(0, next - p) + 5);
    }

    private void onTick() {
        if (songs.isEmpty()) return;
        long p = pos();
        if (song().dur > 0 && p >= song().dur - 20) { track((cur + 1) % songs.size(), 0); return; }
        int k = lineAt(p);
        if (k != lineIdx) { lineIdx = k; push(); }
        schedule();
    }

    void quit() { main.removeCallbacksAndMessages(null); host = null; byd = null; }
}
