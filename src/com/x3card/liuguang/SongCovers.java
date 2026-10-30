package com.x3card.liuguang;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 歌单长廊（Shelf）的封面（BydMedia.Song.cover）。两种：
 * - 长廊里的砖：256 的方图，连同 128 / 64 / 32 三级缩小图在后台线程里缩好（GL 线程按格传进图集，不用整张重新生成缩小图）；
 * - 中间那块停下来时的大图：512 的方图 + 取色（天空跟着变色用）。
 * 各音源给的地址不一样：QQ 音乐是 https 的 120×120（T002R120x120M…，换成 R300x300 / R500x500 就是大的）；
 * 网易云（p*.music.126.net）、酷狗（imge.kugou.com/stdmusic/<尺寸>/）、酷我（img*.kuwo.cn/star/albumcover/<尺寸>/）都是 http，网易云、酷狗还是一千多像素的原图。
 * 所以地址一律换成 https（安卓 9 起默认不让应用走明文 http；这几家都支持 https），按要的大小改地址（sized），取不到再取原图；
 * 解码时先看尺寸、按 2 的倍数缩到够用再裁成方的（原图再大也不会把内存撑爆），按 RGB_565 存（JPEG 没有透明，省一半）。
 * 本地音乐（本地、U 盘、SD 卡）给的是媒体库的专辑封面 content://media/external/audio/albumart/<专辑号>（封面在歌曲文件里，没有的读不到），不用权限就能读，直接读不联网。
 * 没有封面地址、取不到的：按歌名生成一张（MediaState.makeCover，同正在放的歌没封面时），不留空白；网上的取不到时先用生成的，过一会儿再取。
 * 下载过的放在应用的缓存目录里（HttpResponseCache，最多 24MB，按 CDN 给的缓存时间），关了歌单再打开、下次开机都不用重新下。
 * 三个低优先级的后台线程（不和画 3D 的线程抢），同一张正在取的不重复取，网上取不到的 20 秒内不再试。
 * 取到了在后台线程里交给 Sink（调用方自己转到要用的线程）。全部线程安全；页面关了 quit
 */
final class SongCovers {
    interface Sink {
        /** key：Song.art；mips：砖用的四级（0 是 256）或者大图（一张 512）；fake：网上的取不到，先给的生成的（过一会儿还能再取） */
        void got(String key, boolean big, Bitmap[] mips, Palette pal, boolean fake);
    }

    static final int TILE = 256, LEVELS = 4, BIG = 512;
    private static final long RETRY = 20000;
    private static final int MAX_BYTES = 3 * 1024 * 1024; // 退回原图时一千多像素的也就几百 KB，超过这么大的不要

    private final Map<String, Long> failedAt = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final ExecutorService pool;
    private final ContentResolver cr;

    SongCovers(Context ctx) {
        cr = ctx.getContentResolver();
        try { if (HttpResponseCache.getInstalled() == null) HttpResponseCache.install(new File(ctx.getCacheDir(), "covers"), 24L * 1024 * 1024); }
        catch (Exception e) { Log.w(Uiplus.TAG, "封面的磁盘缓存装不上", e); }
        ThreadPoolExecutor p = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "song-cover");
            t.setDaemon(true);
            return t;
        });
        p.allowCoreThreadTimeOut(true); // 不取的时候线程自己退掉
        pool = p;
    }

    /** 去取这首的封面（取到了、生成的都交给 sink）；同一张正在取的、网上刚取不到的不取，返回假 */
    boolean load(final BydMedia.Song song, final boolean big, final Sink sink) {
        if (pool.isShutdown()) return false;
        final String url = song.cover, key = (big ? "B" : "T") + song.art;
        synchronized (this) {
            if (loading.contains(key)) return false;
            Long f = failedAt.get(key);
            if (f != null && SystemClock.uptimeMillis() - f < RETRY) return false;
            loading.add(key);
        }
        try {
            pool.execute(() -> {
                Bitmap[] out;
                Palette pal = null;
                int n = big ? BIG : TILE;
                byte[] bytes = url.isEmpty() ? null : fetchAny(url, big ? 500 : 300);
                Bitmap b = bytes == null ? null : square(bytes, n);
                boolean fake = false;
                if (b == null) { b = fit(MediaState.makeCover(song.title, song.artist), n); fake = url.startsWith("http"); }
                if (big) { out = new Bitmap[] { b }; pal = Palette.of(b); }
                else {
                    out = new Bitmap[LEVELS];
                    out[0] = b;
                    for (int l = 1; l < LEVELS; l++) out[l] = Bitmap.createScaledBitmap(out[l - 1], TILE >> l, TILE >> l, true);
                }
                synchronized (this) {
                    loading.remove(key);
                    if (fake) failedAt.put(key, SystemClock.uptimeMillis()); else failedAt.remove(key);
                }
                sink.got(song.art, big, out, pal, fake);
            });
            return true;
        } catch (Exception e) { // 线程池关了
            synchronized (this) { loading.remove(key); }
            return false;
        }
    }

    void quit() { pool.shutdownNow(); }

    private static String https(String url) { return url.startsWith("http://") ? "https://" + url.substring(7) : url; }

    /** 按要的大小改地址（认得的图床，见开头说明；别的只换成 https） */
    static String sized(String url, int px) {
        String u = https(url);
        if (u.matches("https://y\\.gtimg\\.cn/music/photo_new/T\\d+R\\d+x\\d+M.*")) return u.replaceFirst("R\\d+x\\d+M", "R" + px + "x" + px + "M");
        if (u.matches("https://p\\d+\\.music\\.126\\.net/.*") && u.indexOf('?') < 0) return u + "?param=" + px + "y" + px;
        u = u.replaceFirst("^(https://imge\\.kugou\\.com/stdmusic/)\\d+/", "$1" + (px <= 300 ? 240 : 480) + "/");
        return u.replaceFirst("^(https://img\\d*\\.kuwo\\.cn/star/albumcover/)\\d+/", "$1" + (px <= 300 ? 300 : 500) + "/");
    }

    /** 先取改过大小的，取不到再取原图；媒体库的封面直接读 */
    private byte[] fetchAny(String url, int px) {
        if (url.startsWith("content://")) return read(url);
        String a = sized(url, px), b = https(url);
        byte[] r = fetch(a);
        return r != null || a.equals(b) ? r : fetch(b);
    }

    private static byte[] fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(true);
            if (c.getResponseCode() != 200) { Log.w(Uiplus.TAG, "歌单封面 " + c.getResponseCode() + "：" + url); return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null; }
            }
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "歌单封面取不到：" + url + "（" + e + "）");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private byte[] read(String url) {
        try (InputStream in = cr.openInputStream(Uri.parse(url))) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > MAX_BYTES) return null; }
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "歌单封面读不到：" + url + "（" + e + "）");
            return null;
        }
    }

    /** 解码成 n×n 的方图：先只看尺寸，按 2 的倍数缩到短边不小于 n 再解 */
    private static Bitmap square(byte[] bytes, int n) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1, m = Math.min(o.outWidth, o.outHeight);
        while (m / (sample * 2) >= n) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        return src == null ? null : fit(src, n);
    }

    /** 画成 n×n 的方图（不是方的居中裁，RGB_565），src 用完还掉 */
    private static Bitmap fit(Bitmap src, int n) {
        int w = src.getWidth(), h = src.getHeight(), s = Math.min(w, h);
        Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(src, new Rect((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2), new Rect(0, 0, n, n), new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }
}
