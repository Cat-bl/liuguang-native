package com.x3card.liuguang;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.media.Rating;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 车机自带的媒体控制器（com.byd.mediacontroller，系统应用）：比亚迪给第三方做媒体卡片的 SDK，零权限——服务导出、不校验签名和调用方。
 * 绑定 com.byd.mediacontroller/.service.MainService（action com.byd.mediacontroller.thirdapp.controller）拿到 IMediaControllerSdkInterface，
 * setMediaControllerListener(名字, 监听器)（事务码 1）注册以后，它把「多媒体」的数据推过来（监听器 IMediaControllerSdkListener 的事务码）：
 * 3 setPlaybackState（放歌时每 0.5 秒一次，不看）、4 setMediaMetadata（当前这首；MediaMetadataCompat 在 parcel 里就是一个 Bundle）、
 * 8 setPlayListNew（整个播放队列：参数包里 PlayList 是 JSON 的字节，每首 id、title、artist、coverUrl（QQ 音乐 CDN 的 120×120 小图）、duration、isFavorite、isPlaying）。
 * 注册时马上推一次当前 metadata 和歌单，之后有变化再推（同一份歌单会重复推好几遍，内容没变不重新解析）。
 * - 收藏（宿主没有）：metadata 的 USER_RATING 是心形评分（RATING_HEART）；点了收藏 / 取消（Player.favorite）0.2 秒内重推。
 *   歌单里的 isFavorite 点了收藏不跟着变，红心只认 metadata。
 * - 歌单：顺序就是「多媒体」播放队列的序号，点一首按序号放（Player.playIndex）；正在放哪首按 metadata 的 MEDIA_ID 对歌单里的 id。
 * 反编译 MediaController.apk 看的（MainService、ThirdAppDataDispatcher、sdk/IMediaControllerSdk*）；没有 support 库，用裸 Binder 按事务码收发。
 * 注册的名字是控制器监听表的键：全屏页、小窗口各用一个（同一个名字后注册的会顶掉先注册的）；绑定的 intent 带同样的 identifier，
 * 解绑时控制器按它把我们从监听表里删掉（不然藏起来以后它还每 0.5 秒调一次）。对方进程拿着我们的监听器，所以监听器只弱引用这里（不把页面钉住）。
 * 注册后马上推当前 metadata 和歌单；点收藏后 metadata 很快跟着变。没有这个应用（别的车机）或者绑不上：红心、歌单都没有。
 * - 音源：2 setMediaSourceInfo（MEDIA_SOURCE 控制器的音源号、APP_NAME、PACKAGENAME；切歌、切源时推）、6 setMediaCenterSourceIcon（各音源的 42px 图标，键 "1"…"12"）、
 *   7 setCurrentVehicleMediaCenterSourceList（这台车有哪些音源）。音源号：1 本地 2 U盘 3 SD 4 蓝牙 5 酷狗 6 酷我 7 喜马拉雅 8 云听 9 收音机 10 QQ音乐 12 网易云。
 *   切源用它的 changeMediaSource（事务码 14，不开「多媒体」的界面）。
 * - 播放模式：metadata 的 com.byd.mediacenter.metadata.MEDIA_PLAY_MODE（0 列表循环 / 1 随机 / 2 单曲循环，和 Player.playMode 的编码一样）。
 * 全在主线程里用（Binder 回调转到主线程）
 */
final class BydMedia {
    interface Listener {
        void changed();
        void heartFailed();
        void modeFailed();
        /** 播放状态（放歌时每 0.5 秒）：p 是 at（elapsedRealtime）那一刻的位置，speed 播放速度，on 在放 */
        void position(long p, long at, float speed, boolean on);
    }

    /** 能切播放模式的音源（本地、U盘、SD、酷狗、酷我、QQ音乐、网易云） */
    static boolean hasModes(int src) { return src == 1 || src == 2 || src == 3 || src == 5 || src == 6 || src == 10 || src == 12; }

    /** 在线音乐（曲库的内容都在它们的账号、曲库里）：QQ音乐、网易云、酷狗、酷我 */
    static boolean online(int src) { return src == 10 || src == 12 || src == 5 || src == 6; }

    static String onlineName(int src) { return src == 10 ? "QQ音乐" : src == 12 ? "网易云音乐" : src == 5 ? "酷狗音乐" : src == 6 ? "酷我音乐" : ""; }

    /** 红心：不显示（不知道这首收没收藏、不是「多媒体」在放）/ 没收藏 / 收藏了 */
    static final int HIDE = -1, OFF = 0, ON = 1;

    /** 歌单里的一首（cover：封面图的地址，可能是空的；art：歌单长廊认封面用的，没封面地址的按歌名歌手认） */
    static final class Song {
        final String id, title, artist, cover, art;
        final long dur;
        Song(String id, String title, String artist, String cover, long dur) {
            this.id = id; this.title = title; this.artist = artist; this.cover = cover; this.dur = dur;
            art = cover.isEmpty() ? "gen:" + title + "|" + artist : cover;
        }
    }

    private static final String PKG = "com.byd.mediacontroller";
    private static final String SVC = "com.byd.mediacontroller.service.MainService";
    private static final String ACTION = "com.byd.mediacontroller.thirdapp.controller";
    private static final String IFACE = "com.byd.mediacontroller.sdk.IMediaControllerSdkInterface";
    private static final String LISTENER = "com.byd.mediacontroller.sdk.IMediaControllerSdkListener";
    private static final int T_SET_LISTENER = 1, T_CHANGE_SOURCE = 14;
    private static final int ON_SOURCE_INFO = 2, ON_STATE = 3, ON_METADATA = 4, ON_SOURCE_ICONS = 6, ON_SOURCE_LIST = 7, ON_PLAYLIST = 8;
    private static final String MC = "com.byd.mediacenter";

    private final Context ctx;
    private final Handler main;
    private final Listener l;
    private final String key;
    private final Sink sink = new Sink(this);
    private boolean on, bound;
    private IBinder remote;
    private String mdTitle, mdId, mdArt, mdArtist;
    private long mdDur;
    private Boolean mdHeart, pending; // mdHeart：这首收没收藏（null：没给心形评分）；pending：刚点了、等「多媒体」改过来
    private String pendingTitle;
    private List<Song> songs = Collections.emptyList();
    private int playingIdx = -1; // 歌单里标着 isPlaying 的那首（metadata 没给 MEDIA_ID 时用）
    private volatile String rawList; // 上一份歌单的原文（Binder 线程里比：一样就不解析）
    private int listSeq; // 推来一份内容不一样的歌单加一（曲库等新队列用）
    private int source = -1; // 「多媒体」现在的音源（控制器的音源号；-1：不知道，或者在放的不是「多媒体」）
    private String sourceName = "";
    private int[] sources = new int[0];
    private final android.util.SparseArray<Bitmap> icons = new android.util.SparseArray<>();
    private int iconSeq; // 图标换了加一
    private int mdMode = -1; // metadata 里的播放模式（-1：没给）
    private int modeWant = -1; // 刚点了、等「多媒体」改过来的播放模式
    private final Runnable pendingOff, modeOff;
    private final Runnable rebind = this::bind;

    BydMedia(Context ctx, Handler main, Listener l) {
        this.ctx = ctx;
        this.main = main;
        this.l = l;
        this.key = ctx.getPackageName() + "/" + ctx.getClass().getSimpleName();
        pendingOff = () -> { if (pending != null) { pending = null; l.heartFailed(); l.changed(); } };
        modeOff = () -> { if (modeWant >= 0) { modeWant = -1; l.modeFailed(); l.changed(); } };
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    /** JSON 里的字符串（null 不当成 "null"） */
    private static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k); }

    // ================= 连接 =================
    void start() {
        if (on) return;
        on = true;
        if (mock != null) { mock.attachByd(this); return; }
        bind();
    }

    private Mock mock;

    /** 模拟器里用假数据（见 Mock）：不连控制器，歌单、正在放哪首、红心都由它推 */
    void useMock(Mock m) { mock = m; }

    void stop() {
        on = false;
        if (mock != null) mock.attachByd(null);
        main.removeCallbacks(rebind);
        if (bound) try { ctx.unbindService(conn); } catch (Exception ignored) { }
        bound = false;
        remote = null;
        drop();
    }

    private void bind() {
        if (!on || bound) return;
        Intent it = new Intent(ACTION).setClassName(PKG, SVC);
        if (Build.VERSION.SDK_INT >= 29) it.setIdentifier(key);
        try { bound = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE); } catch (Exception e) { bound = false; }
        if (!bound) { Log.w(Uiplus.TAG, "车机媒体控制器绑不上（没有这个应用？）：红心、歌单都没有"); try { ctx.unbindService(conn); } catch (Exception ignored) { } }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            remote = b;
            Parcel d = Parcel.obtain(), r = Parcel.obtain();
            try {
                d.writeInterfaceToken(IFACE);
                d.writeString(key);
                d.writeStrongBinder(sink);
                b.transact(T_SET_LISTENER, d, r, 0);
                r.readException();
                Log.w(Uiplus.TAG, "车机媒体控制器连上了");
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机媒体控制器注册失败", e);
            } finally { d.recycle(); r.recycle(); }
        }

        @Override public void onServiceDisconnected(ComponentName n) { remote = null; drop(); l.changed(); } // 控制器重启：系统会重新连上，到时再注册

        @Override public void onBindingDied(ComponentName n) { // 控制器被更新 / 卸载：过一会儿重新绑
            remote = null;
            drop();
            l.changed();
            try { ctx.unbindService(this); } catch (Exception ignored) { }
            bound = false;
            main.removeCallbacks(rebind);
            main.postDelayed(rebind, 3000);
        }

        @Override public void onNullBinding(ComponentName n) { Log.w(Uiplus.TAG, "车机媒体控制器不给接（它自己的权限没开？）：红心、歌单都没有"); }
    };

    private void drop() {
        mdTitle = null;
        mdId = null;
        mdArt = null;
        mdHeart = null;
        pending = null;
        main.removeCallbacks(pendingOff);
        mdMode = modeWant = -1;
        main.removeCallbacks(modeOff);
        source = -1;
        songs = Collections.emptyList();
        playingIdx = -1;
        rawList = null; // 重新注册时控制器再推一遍，要重新解析
    }

    /** 当前这首（在主线程里）；mode：播放模式（-1：没给） */
    void onMetadata(String title, String id, Boolean heart, String art, String artist, long dur, int mode) {
        if (!on) return;
        mdTitle = title;
        mdId = id;
        mdArt = art;
        mdArtist = artist;
        mdDur = dur;
        mdHeart = heart;
        mdMode = mode;
        if (pending != null && (pending.equals(mdHeart) || !java.util.Objects.equals(mdTitle, pendingTitle))) { // 改过来了、或者换了歌
            pending = null;
            main.removeCallbacks(pendingOff);
        }
        if (modeWant >= 0 && modeWant == mdMode) { modeWant = -1; main.removeCallbacks(modeOff); }
        l.changed();
    }

    /** 整个播放队列（在主线程里；内容和上一份不一样才会到这里） */
    void onList(List<Song> list, int playing) {
        if (!on) return;
        songs = list;
        playingIdx = playing;
        listSeq++;
        l.changed();
    }

    /** 现在的音源（在主线程里）：pkg 不是「多媒体」时当不知道 */
    void onSource(String pkg, int src, String name) {
        if (!on) return;
        source = MC.equals(pkg) ? src : -1;
        sourceName = name == null ? "" : name;
        l.changed();
    }

    /** 这台车有哪些音源（在主线程里） */
    void onSources(int[] list) {
        if (!on) return;
        sources = list == null ? new int[0] : list;
        l.changed();
    }

    /** 各音源的图标（在主线程里；旧的不回收：可能已经交给场景在用，没人用了由垃圾回收） */
    void onIcons(android.util.SparseArray<Bitmap> m) {
        if (!on || m == null) return;
        for (int i = 0; i < m.size(); i++) icons.put(m.keyAt(i), m.valueAt(i));
        iconSeq++;
        l.changed();
    }

    // ================= 收藏 =================
    /** 宿主正在放的这首（title）该画成什么样的红心：按歌名对上了才显示（换歌时两边先后到，对不上先藏起来） */
    /** 媒体控制器说正在放的这首的歌名（对不上宿主正在放的就别用它的东西） */
    String title() { return mdTitle; }

    int heart(String title) {
        if (remote == null && mock == null || mdTitle == null || mdTitle.isEmpty() || !mdTitle.equals(clean(title))) return HIDE;
        Boolean h = pending != null ? pending : mdHeart;
        return h == null ? HIDE : h ? ON : OFF;
    }

    /** 点红心：先按点下去的显示，「多媒体」改过来了对齐；8 秒没改过来变回去（heartFailed） */
    boolean toggleHeart(String title) {
        int h = heart(title);
        if (h != ON && h != OFF) return false;
        boolean want = h == OFF;
        if (mock != null) mock.favorite(want);
        else if (!Player.favorite(ctx, want)) return false;
        pending = want;
        pendingTitle = mdTitle;
        main.removeCallbacks(pendingOff);
        main.postDelayed(pendingOff, 8000);
        l.changed();
        return true;
    }

    // ================= 歌单 =================
    /** 「多媒体」的播放队列（顺序 = 序号；不会再改，换新的是整个换掉） */
    List<Song> songs() { return songs; }

    /** 正在放的是第几首（-1：不知道） */
    /** 当前这首的本地封面文件（蓝牙音乐：手机传过来的图），连同它是哪首的（歌手栏、时长），没有是 null */
    String artPath() { return mdArt; }
    String artArtist() { return mdArtist; }
    long artDur() { return mdDur; }

    int current() {
        if (mdId != null) for (int i = 0; i < songs.size(); i++) if (mdId.equals(songs.get(i).id)) return i;
        return playingIdx;
    }

    /** 推来过几份不一样的歌单（曲库发了命令以后看它变没变） */
    int listSeq() { return listSeq; }

    // ================= 音源 =================
    int source() { return source; }
    String sourceName() { return sourceName; }
    int[] sources() { return sources; }
    Bitmap icon(int src) { return icons.get(src); }
    int iconSeq() { return iconSeq; }

    /** 切到音源 src（控制器的音源号）。发出去返回真；切过去了 onSource 会推 */
    boolean changeSource(int src) {
        if (mock != null) { mock.changeSource(src); return true; }
        IBinder b = remote;
        if (b == null) return false;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(IFACE);
            d.writeInt(src);
            b.transact(T_CHANGE_SOURCE, d, r, 0);
            r.readException();
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "切音源失败", e);
            return false;
        } finally { d.recycle(); r.recycle(); }
    }

    // ================= 播放模式 =================
    /** 现在的播放模式（刚点了的先按点的显示）：0 列表循环 / 1 随机 / 2 单曲循环；-1：不显示（不知道、或者这个音源切不了） */
    int mode(String title) {
        if (!hasModes(source) || mdTitle == null || !mdTitle.equals(clean(title))) return -1;
        return modeWant >= 0 ? modeWant : mdMode >= 0 && mdMode <= 2 ? mdMode : -1;
    }

    /** 换到下一个播放模式：先按点的显示，「多媒体」改过来了对齐；4 秒没改过来变回去（modeFailed）。返回换成的模式，-1：现在切不了 */
    int nextMode(String title) {
        int m = mode(title);
        if (m < 0) return -1;
        int want = m == 0 ? 1 : m == 1 ? 2 : 0; // 列表循环 → 随机 → 单曲循环
        if (mock != null) mock.playMode(want);
        else if (!Player.playMode(ctx, want)) return -1;
        modeWant = want;
        main.removeCallbacks(modeOff);
        main.postDelayed(modeOff, 4000);
        l.changed();
        return want;
    }

    // ================= 监听器（控制器推过来的；在 Binder 线程里） =================
    private static final class Sink extends Binder {
        private final WeakReference<BydMedia> ref;

        Sink(BydMedia m) { ref = new WeakReference<>(m); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(LISTENER); return true; }
            final BydMedia m = ref.get();
            if (m != null && code == ON_METADATA) metadata(m, data);
            else if (m != null && code == ON_PLAYLIST) playlist(m, data);
            else if (m != null && code == ON_SOURCE_INFO) sourceInfo(m, data);
            else if (m != null && code == ON_SOURCE_ICONS) sourceIcons(m, data);
            else if (m != null && code == ON_SOURCE_LIST) sourceList(m, data);
            else if (m != null && code == ON_STATE) state(m, data);
            if (reply != null) reply.writeNoException(); // 别的不看
            return true;
        }

        /** 3：播放状态（系统的 PlaybackState，不是 support 库那个） */
        private static void state(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                if (data.readInt() == 0) return;
                android.media.session.PlaybackState ps = android.media.session.PlaybackState.CREATOR.createFromParcel(data);
                final long p = ps.getPosition(), at = ps.getLastPositionUpdateTime();
                final float sp = ps.getPlaybackSpeed();
                final boolean on = ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                m.main.post(() -> m.l.position(p, at, sp, on));
            } catch (Exception ignored) { /* 读不出来就不校正 */ }
        }

        private static void sourceInfo(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                Bundle b = data.readInt() != 0 ? data.readBundle(Sink.class.getClassLoader()) : null;
                if (b == null) return;
                final String pkg = b.getString("PACKAGENAME"), name = clean(b.getString("APP_NAME"));
                final int src = b.getInt("MEDIA_SOURCE", -1); // APP_ICON 不要：同 6 推的那份
                m.main.post(() -> m.onSource(pkg, src, name));
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机音源信息读不出来", e);
            }
        }

        private static void sourceIcons(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                Bundle b = data.readInt() != 0 ? data.readBundle(Sink.class.getClassLoader()) : null;
                if (b == null) return;
                final android.util.SparseArray<Bitmap> out = new android.util.SparseArray<>();
                for (String k : b.keySet()) {
                    byte[] png = b.getByteArray(k);
                    int src;
                    try { src = Integer.parseInt(k); } catch (NumberFormatException e) { continue; }
                    if (png == null || png.length == 0) continue;
                    Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bm != null) out.put(src, bm);
                }
                m.main.post(() -> m.onIcons(out));
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机音源图标读不出来", e);
            }
        }

        private static void sourceList(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                final int[] list = data.createIntArray();
                m.main.post(() -> m.onSources(list));
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机音源列表读不出来", e);
            }
        }

        private static void metadata(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                Bundle b = data.readInt() != 0 ? data.readBundle(Sink.class.getClassLoader()) : null;
                String title = null, id = null, art = null, artist = null;
                Boolean heart = null;
                long dur = 0;
                int mode = -1;
                if (b != null) {
                    title = clean(b.getString("android.media.metadata.TITLE"));
                    if (title.isEmpty()) title = clean(b.getString("android.media.metadata.DISPLAY_TITLE"));
                    id = b.getString("android.media.metadata.MEDIA_ID");
                    Object r = b.get("android.media.metadata.USER_RATING");
                    if (r instanceof Rating && ((Rating) r).getRatingStyle() == Rating.RATING_HEART) heart = ((Rating) r).hasHeart();
                    Object bm = b.get("android.media.metadata.ALBUM_ART"); // 封面走宿主，这张用不着：马上还掉
                    if (bm instanceof Bitmap) ((Bitmap) bm).recycle();
                    String u = b.getString("android.media.metadata.ALBUM_ART_URI");
                    if (u != null && u.startsWith("/")) art = u; // 本地文件（蓝牙音乐）；网上的、content:// 的宿主会给
                    artist = clean(b.getString("android.media.metadata.ARTIST"));
                    dur = b.getLong("android.media.metadata.DURATION");
                    Object pm = b.get("com.byd.mediacenter.metadata.MEDIA_PLAY_MODE");
                    if (pm instanceof Number) mode = ((Number) pm).intValue();
                }
                final String t = title, i = id, a = art, ar = artist;
                final Boolean h = heart;
                final long d = dur;
                final int md = mode;
                m.main.post(() -> m.onMetadata(t, i, h, a, ar, d, md));
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机媒体控制器的 metadata 读不出来", e);
            }
        }

        private static void playlist(final BydMedia m, Parcel data) {
            try {
                data.enforceInterface(LISTENER);
                data.readInt(); // 音源
                Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                byte[] raw = b == null ? null : b.getByteArray("PlayList");
                String json = raw == null ? "[]" : new String(raw, "UTF-8");
                if (json.equals(m.rawList)) return; // 同一份又推了一遍
                m.rawList = json;
                JSONArray a = new JSONArray(json);
                final List<Song> list = new ArrayList<>(a.length());
                int playing = -1;
                for (int k = 0; k < a.length(); k++) { // 坏的也占一个位置：序号要和「多媒体」的队列对得上
                    JSONObject o = a.optJSONObject(k);
                    if (o == null) o = new JSONObject();
                    list.add(new Song(str(o, "id"), clean(str(o, "title")), clean(str(o, "artist")), str(o, "coverUrl").trim(), o.optLong("duration")));
                    if (o.optBoolean("isPlaying")) playing = k;
                }
                final int p = playing;
                final List<Song> ro = Collections.unmodifiableList(list);
                m.main.post(() -> m.onList(ro, p));
            } catch (Exception e) {
                Log.w(Uiplus.TAG, "车机歌单读不出来", e);
            }
        }
    }
}
