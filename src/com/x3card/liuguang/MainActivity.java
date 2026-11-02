package com.x3card.liuguang;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 琉光原生版：3D 场景（Scene，照网页版 main.js；右上角的键、设置页也在场景里）+ 提示条（Ui）+ 上面一层说明（InfoView：连不上、没授权时）。
 * 宿主的数据：媒体（MediaState，照网页版 media.js）、日夜模式（深浅色跟随车机时用，没有就按钟点 6～19 点是白天）。
 * 宿主没有的收藏红心、歌单、跳到某一句（歌词定位线）：收藏状态、歌单从车机的媒体控制器读（BydMedia），点收藏、点歌、跳到某一句都发给「多媒体」的导出入口（Player），全是零权限；显示着才连。
 * 歌单是场景里的 3D 长廊（Shelf），封面由 SongCovers 取（后台线程、磁盘缓存）。
 * 曲库（Library）：换一批歌、按歌手 / 风格 / 语种 / 心情放歌发给「多媒体」的语音入口（Player.voice），切音源发给车机媒体控制器（BydMedia.changeSource）；
 * 发出去以后等媒体控制器推来新的播放队列，到了告诉场景（曲库收起、长廊打开），9 秒没到提示没换成。
 * 曲库的搜歌：按在线音乐自己的网页接口搜（SongFinder），点一首插进播放队列的下一首（QQ 音乐按歌曲 id：Player.insertSong；别的按歌名 + 歌手 + 专辑让「多媒体」在那个音源里搜一首插放）。
 * 模拟器里推了车上录的假数据（见 Mock）时：宿主、媒体控制器、「多媒体」都由它冒充。
 * 主线程把媒体快照、深浅色、设置交给 GL 线程；场景里按的键交回主线程发给宿主。
 * 全屏：宿主给外部应用的接口里没有网页卡片那套窗口切换（windows.*），所以自己在主屏上开一个全屏页（FullActivity，单独的任务）；
 * 在全屏页里点"恢复小屏"就关掉它，回到多窗口里原来那个。全屏页开着时小窗口里的场景停画。设置两边共用一份，回到前台时重新读
 */
public class MainActivity extends Activity implements Uiplus.Listener, Scene.Host, Ui.Actions {
    private static WeakReference<MainActivity> pip = new WeakReference<>(null);

    private Uiplus host;
    private FrameLayout root;
    private Canvas3D view;
    private InfoView info;
    private Ui ui;
    private MediaState song;
    private BydMedia bydMedia;
    private SongCovers covers;
    private ArtistBook artists;
    private int listSeen = -1; // 攒歌手时已经看过的那份歌单（BydMedia.listSeq）
    private Mock mock;
    private Settings st;
    private final HandlerThread io = new HandlerThread("cover");
    private Handler ioH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private String subFor = null; // 在哪个会话上订的
    private String mediaId, dnId; // 现在用的订阅号（null：还没订或者订失败了等着再订）
    private int subSeq, mediaTries, dnTries;
    private final RainPick rp = new RainPick();
    private boolean rainNow; // 现在交给场景的：下不下雨珠
    private boolean first, booted, started, slept;
    private Boolean carNight = null;
    private long fsAt;

    /** 全屏页（FullActivity）是真；多窗口里、从桌面直接打开的是假 */
    boolean isFull() { return false; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isFull()) pip = new WeakReference<>(this);
        prefs = getSharedPreferences("lg", MODE_PRIVATE);
        st = Settings.load(this);
        Res.sharp = "sharp".equals(st.clarity);
        io.start();
        ioH = new Handler(io.getLooper());
        host = new Uiplus(this, this);
        song = new MediaState(host, ioH, this::changed);
        covers = new SongCovers(this);
        artists = new ArtistBook(prefs);
        ArtistPics.init(this);
        bydMedia = new BydMedia(this, main, new BydMedia.Listener() {
            @Override public void changed() { pushSnap(); pushSongs(); localArt(); bookArtists(); noteOnline(); pushLib(); libCheck(); songCheck(); }
            @Override public void heartFailed() { ui.toast("没收藏上，过一会儿再试"); }
            @Override public void modeFailed() { ui.toast("播放模式没切过去，过一会儿再试"); }
            @Override public void position(long p, long at, float speed, boolean on) { song.ctlPos(p, at, speed, on, bydMedia.title()); }
        });
        mock = Mock.open(this);
        if (mock != null) { host.useMock(mock); bydMedia.useMock(mock); }
        root = new FrameLayout(this);
        view = newScene();
        info = new InfoView(this, this::infoPress);
        ui = new Ui(this, this);
        root.addView(view.view(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        pushLib();
        applyTheme();
        applyBar();
        ui.setBlocked(info.covering());
        main.postDelayed(this::boot, 1200); // 宿主一直不推也照常开场
        main.postDelayed(clockTheme, 60000);
    }

    private Canvas3D newScene() {
        Canvas3D v = "vulkan".equals(st.render) && Vk.supported(this) ? new VkSceneView(this, this, st.aa) : new SceneView(this, this, st.aa);
        v.scene().setOpts(st);
        v.scene().setDay(wantDay());
        v.scene().setCovers(covers);
        v.scene().setPrefs(prefsData());
        if (bydMedia != null) v.scene().setSongs(queue(), bydMedia.current());
        libPushed = null;
        return v;
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        reload();
        fsAvail = isFull() || !onMainScreen();
        ui.setFsMode(isFull() ? "small" : fsAvail ? "full" : "");
        pushPrefs();
        if (!slept) { view.onResume(); bydMedia.start(); pushSnap(); }
        host.start();
        weatherWatch("auto".equals(st.rain));
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(true); }
        guideLater(1500);
    }

    @Override
    protected void onStop() {
        started = false;
        main.removeCallbacks(guideTry);
        if (ui.guiding()) { ui.guideHide(); view.scene().guideTrack(Scene.GUIDE_OFF); } // 没看完就收起来了：没过的那几步不算看过，下次再出
        weatherWatch(false);
        host.background(); // 不马上断开：宿主的授权确认页会把这里盖住，授权申请挂在这个会话上
        bydMedia.stop();
        view.onPause();
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(false); }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        bydMedia.stop();
        covers.quit();
        if (mock != null) mock.quit();
        host.quit();
        io.quitSafely();
        super.onDestroy();
    }

    /** 车机媒体控制器给的本地封面文件（蓝牙音乐）交给 song；读不了（没有读存储的权限）就问车主一次（一个进程里只问一次） */
    private static boolean askedStorage;
    private static final int REQ_STORAGE = 7;

    private void localArt() {
        String p = bydMedia.artPath();
        song.artFile(p, bydMedia.artArtist(), bydMedia.artDur());
        if (p == null || askedStorage || checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return;
        askedStorage = true;
        requestPermissions(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_STORAGE && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) song.recheckCover();
    }

    @Override
    public void onBackPressed() {
        if (ui.guiding()) ui.guideOk();
        else if (prefsOn) view.scene().prefs(3);
        else if (libOn) view.scene().library(4); // 曲库开着：先收找歌手的键盘，再关曲库
        else if (ui.shelfOn()) view.scene().shelf(4); // 歌单开着：先收搜索键盘，再关歌单
        else super.onBackPressed();
    }

    /** 全屏页开着：小窗口里的场景停画（盖住了也看不见）；关了接着画，顺便读一下全屏页里改过的设置 */
    void sleep(boolean s) {
        if (s == slept) return;
        slept = s;
        if (s) { view.onPause(); bydMedia.stop(); }
        else if (started) { reload(); view.onResume(); bydMedia.start(); pushSnap(); }
    }

    /** 当前在主屏上（不在宿主多窗口的虚拟屏里） */
    private boolean onMainScreen() {
        Display d = getWindowManager().getDefaultDisplay();
        return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    private void boot() {
        if (booted) return;
        booted = true;
        pushSnap();
        view.scene().boot(true);
        rainApply();
    }

    /** 媒体数据变了：做一份快照交给场景（第一条到了就开场） */
    private void changed() {
        pushSnap();
        if (otherPlayer() != otherPlayer) pushSongs();
        if (!booted && song.on) boot();
        rainApply();
    }

    /** 交给场景一份快照（带上车机媒体控制器给的红心、播放模式、音源） */
    private void pushSnap() {
        song.heart = song.on ? bydMedia.heart(song.title) : BydMedia.HIDE;
        song.mode = song.on && !otherPlayer() ? bydMedia.mode(song.title) : -1;
        song.src = otherPlayer() ? -1 : bydMedia.source();
        view.scene().setSnap(song.snap());
    }

    /** 新推来的歌单：里面的歌手记进曲库的"歌手"那一排 */
    private void bookArtists() {
        if (bydMedia.listSeq() == listSeen) return;
        listSeen = bydMedia.listSeq();
        artists.add(bydMedia.songs());
    }

    // ================= 歌单（场景里的 Shelf）：点了第几首（见 BydMedia、Player） =================
    private void pushSongs() {
        view.scene().setSongs(queue(), bydMedia.current());
    }

    /** 宿主说正在用的不是「多媒体」（单独装的音乐 App、蓝牙音乐……）：歌单、正在放哪首都是「多媒体」的，对不上，当没有歌单 */
    private boolean otherPlayer;

    private boolean otherPlayer() { return song.pkg != null && !song.pkg.isEmpty() && !Player.PKG.equals(song.pkg); }

    private List<BydMedia.Song> queue() {
        otherPlayer = otherPlayer();
        return otherPlayer ? Collections.<BydMedia.Song>emptyList() : bydMedia.songs();
    }

    @Override
    public void playIndex(final int i) {
        main.post(() -> {
            if (mock != null) mock.playIndex(i);
            else if (!Player.playIndex(this, i)) ui.toast("切不过去");
        });
    }

    @Override public void locate() { view.scene().shelfLocate(); }

    @Override public void keys(boolean show, String fs) { if (view != null) view.scene().setKeys(show, fs); }

    @Override public void palette(int accent, int deep) { main.post(() -> ui.setPalette(accent, deep)); }
    @Override public void shelfState(final boolean open) { main.post(() -> ui.setShelfOn(open)); }
    @Override public void locate(final int dir, final float y) { main.post(() -> ui.setLocate(dir, y)); }

    // ================= 曲库（场景里的 Library；见 Player.voice、BydMedia.changeSource） =================
    private boolean libOn;
    private String libPushed; // 上次交给场景的那份（音源、图标、歌手的版本）：没变不重做
    private int libWaitSeq = -1, libWaitSrc = -1; // 在等的：歌单的版本（换一批歌）、音源（切源）
    // 每块换成了以后的那批歌（lg 的 libDone：方法|参数 → 队列的指纹，最多 16 块）：再点同一块时「多媒体」发现正在放的就是它，什么都不做、不推新队列——这时不算没换成
    private String libWaitKey, libDoneKey;
    private int libReq; // 每发一次曲库命令加一：后台线程查到的结果按它对账（不串到后面点的那块上）
    private long libDoneAt;
    private JSONObject libDone;
    private boolean libSame;

    private JSONObject libDone() {
        if (libDone == null) { try { libDone = new JSONObject(prefs.getString("libDone", "{}")); } catch (Exception e) { libDone = new JSONObject(); } }
        return libDone;
    }

    private void noteDone(String key) {
        JSONObject m = libDone();
        if (m.length() >= 16 && !m.has(key)) m = libDone = new JSONObject();
        try { m.put(key, queueSig()); } catch (Exception ignored) { }
        prefs.edit().putString("libDone", m.toString()).apply();
    }

    /** 现在的播放队列是哪些歌：首数 + 按顺序的歌曲 id 的哈希 */
    private String queueSig() {
        List<BydMedia.Song> l = bydMedia.songs();
        int h = 1;
        for (BydMedia.Song s : l) h = h * 31 + s.id.hashCode();
        return l.size() + ":" + Integer.toHexString(h);
    }
    private int libOnline = -2;      // 上一次用的在线音乐（存在 lg 的 libOnline；-2 还没读）
    private int libHopSrc = -1;      // 点砖时当前不是在线音乐：先切到这个，切过去再发 libHop
    private String[] libHop;

    private int lastOnline() { if (libOnline == -2) libOnline = prefs.getInt("libOnline", -1); return libOnline; }

    private void noteOnline() {
        int s = bydMedia.source();
        if (BydMedia.online(s) && s != lastOnline()) { libOnline = s; prefs.edit().putInt("libOnline", s).apply(); }
    }

    /** 当前不是在线音乐时，曲库的内容在哪个在线音乐里放：上一次用的，没用过就是车上音源列表里第一个在线音乐。当前就是在线音乐（或者不是「多媒体」在放）时 -1 */
    private int libTarget() {
        int cur = bydMedia.source();
        if (cur < 0 || BydMedia.online(cur)) return -1;
        int[] src = bydMedia.sources();
        for (int s : src) if (s == lastOnline()) return s;
        for (int s : src) if (BydMedia.online(s)) return s;
        return -1;
    }

    /** 曲库要的数据变了才交给场景 */
    private void pushLib() {
        int[] src = bydMedia.sources();
        int target = libTarget();
        String k = java.util.Arrays.toString(src) + "|" + bydMedia.source() + "|" + target + "|" + bydMedia.iconSeq() + "|" + artists.seq();
        if (k.equals(libPushed)) return;
        libPushed = k;
        List<ArtistBook.Artist> l = artists.list();
        String[] names = new String[l.size()], covers = new String[l.size()];
        for (int i = 0; i < l.size(); i++) { names[i] = l.get(i).name; covers[i] = l.get(i).cover == null ? "" : l.get(i).cover; }
        android.util.SparseArray<android.graphics.Bitmap> icons = new android.util.SparseArray<>();
        for (int s : src) { android.graphics.Bitmap b = bydMedia.icon(s); if (b != null) icons.put(s, b); }
        int cur = bydMedia.source();
        if (cur >= 0 && bydMedia.icon(cur) != null) icons.put(cur, bydMedia.icon(cur));
        view.scene().setLib(new Library.Data(src, cur, target, icons, bydMedia.iconSeq(), names, covers, artists.seq()));
    }

    @Override public void libraryState(final boolean open) { main.post(() -> { libOn = open; ui.setLib(open); }); }

    @Override
    public void libPick(final String method, final String field, final String value) {
        main.post(() -> {
            int hopping = libHopSrc;
            main.removeCallbacks(libTimeout);
            main.removeCallbacks(libSend);
            libWaitSrc = -1;
            libWaitSeq = -1;
            libHop = null;
            libHopSrc = -1;
            int t = libTarget();
            if (t < 0) { sendPick(method, field, value); return; }
            libHop = new String[] { method, field, value }; // 先切到在线音乐，切过去（libCheck）再发
            libHopSrc = t;
            main.postDelayed(libTimeout, 15000);
            if (t == hopping) return; // 已经在切过去了：只换要发的，不再切一次
            if (!bydMedia.changeSource(t)) { libHop = null; libHopSrc = -1; main.removeCallbacks(libTimeout); view.scene().libResult(2); ui.toast("切不到" + BydMedia.onlineName(t)); }
        });
    }

    /** 切到在线音乐以后：等它自己的播放队列先推过来，再发 */
    private final Runnable libSend = () -> {
        String[] h = libHop;
        libHop = null;
        if (h != null) sendPick(h[0], h[1], h[2]);
    };

    private void sendPick(final String method, final String field, final String value) {
        final JSONObject p = new JSONObject();
        try { if (field != null) p.put(field, value); } catch (Exception ignored) { }
        libWaitSeq = bydMedia.listSeq();
        libWaitKey = method + "|" + field + "|" + value;
        libSame = !bydMedia.songs().isEmpty() && queueSig().equals(libDone().optString(libWaitKey));
        main.removeCallbacks(libTimeout);
        main.postDelayed(libTimeout, libSame ? 4000 : 9000); // 点的还是上次那块、队列也没变：多半正在放的就是它，等得短一点
        if (mock != null) { mock.voice(method, p); return; }
        final int req = ++libReq;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> { // 发之前、发之后问「多媒体」在不在前台：3 秒内跑到前台多半是没登录、弹出了登录页（留着让人登录），不再等新歌单
            int before = Player.foreground(app);
            if (!Player.voice(app, method, p)) { main.post(() -> libFailed(req, "这台车机没有「多媒体」，换不了")); return; }
            if (before != 0) return;
            for (int i = 0; i < 8; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                if (Player.foreground(app) == 1) { main.post(() -> libFailed(req, loginName() + "还没登录：在「多媒体」弹出来的页面里登录以后再点")); return; }
            }
        }, "lg-voice").start();
    }

    private void libFailed(int req, String msg) {
        if (req != libReq || libWaitSeq < 0) return; // 后来又点了别的、或者已经有结果了
        libWaitSeq = -1;
        main.removeCallbacks(libTimeout);
        view.scene().libResult(2);
        ui.toast(msg);
    }

    /** 曲库命令交给的那个在线音乐的名字 */
    private String loginName() {
        int s = bydMedia.source();
        return BydMedia.online(s) ? BydMedia.onlineName(s) : "音乐账号";
    }

    @Override
    public void libSource(final int src) {
        main.post(() -> {
            libWaitSeq = -1;
            libWaitSrc = src;
            libHop = null;
            libHopSrc = -1;
            main.removeCallbacks(libSend);
            main.removeCallbacks(libTimeout);
            main.postDelayed(libTimeout, 6000);
            if (!bydMedia.changeSource(src)) { libWaitSrc = -1; main.removeCallbacks(libTimeout); ui.toast("音源切不过去"); }
        });
    }

    /** 媒体控制器推来了新东西：在等的到了没有 */
    private void libCheck() {
        if (libDoneKey != null && SystemClock.uptimeMillis() - libDoneAt < 10000 && !queueSig().equals(libDone().optString(libDoneKey))) noteDone(libDoneKey); // 换成以后队列还在一页页补全：跟着记
        if (libHopSrc >= 0 && bydMedia.source() == libHopSrc) { libHopSrc = -1; main.postDelayed(libSend, 1200); return; } // 切到在线音乐了
        if (libWaitSeq >= 0 && bydMedia.listSeq() != libWaitSeq && !bydMedia.songs().isEmpty()) { // 新的播放队列到了：曲库收起、长廊打开
            libWaitSeq = -1;
            if (libWaitKey != null) { noteDone(libWaitKey); libDoneKey = libWaitKey; libDoneAt = SystemClock.uptimeMillis(); }
            main.removeCallbacks(libTimeout);
            view.scene().libResult(1);
        } else if (libWaitSrc >= 0 && bydMedia.source() == libWaitSrc) { // 切过去了：曲库不关；过一会儿还没有歌就说一声
            final int s = libWaitSrc;
            libWaitSrc = -1;
            main.removeCallbacks(libTimeout);
            main.postDelayed(() -> { if (bydMedia.source() == s && bydMedia.songs().isEmpty() && !song.on) ui.toast(bydMedia.sourceName() + "里还没有可放的歌"); }, 4000);
        }
    }

    private final Runnable libTimeout = () -> {
        if (libHopSrc >= 0) { int t = libHopSrc; libHopSrc = -1; libHop = null; view.scene().libResult(2); ui.toast("没切到" + BydMedia.onlineName(t) + "，过一会儿再试"); }
        else if (libWaitSeq >= 0 && libSame && queueSig().equals(libDone().optString(libWaitKey))) { libWaitSeq = -1; view.scene().libResult(1); ui.toast("正在放的就是这个"); } // 曲库收起、长廊打开
        else if (libWaitSeq >= 0) { libWaitSeq = -1; view.scene().libResult(2); ui.toast("没有换：正在放的可能就是它；也可能要先在「多媒体」里登录，或者这个音源里没有"); }
        else if (libWaitSrc >= 0) { libWaitSrc = -1; ui.toast("音源没切过去，过一会儿再试"); }
    };

    // ================= 搜歌（曲库里的"搜歌"；见 SongFinder、Player.insertSong） =================
    private SongFinder.Hit songWant; // 点了、在等的那首
    private int songWaitSeq = -1;    // 发出去时的播放队列版本（插进去了队列会变）
    private boolean songHop;         // 先切到那首所在的音源，切过去再发
    private int songReq;

    /** 搜哪个音源：当前是在线音乐就是它，不是就是要切到的那个（同曲库） */
    private int songSrc() {
        int t = libTarget();
        if (t >= 0) return t;
        int cur = bydMedia.source();
        return BydMedia.online(cur) ? cur : -1;
    }

    @Override
    public void songSearch(final String q, final int page, final int seq) {
        main.post(() -> {
            int s = songSrc();
            if (s < 0) { view.scene().songPage(new SongFinder.Page(seq, page, s, q, Collections.<SongFinder.Hit>emptyList(), false, "车上没有能搜的在线音乐")); return; }
            SongFinder.search(s, q, page, seq, p -> main.post(() -> view.scene().songPage(p)));
        });
    }

    @Override
    public void imeOpen() {
        main.post(() -> {
            List<String> names = new ArrayList<>();
            for (BydMedia.Song x : bydMedia.songs()) { names.add(x.title); names.add(x.artist); }
            for (ArtistBook.Artist x : artists.list()) names.add(x.name);
            Pinyin.load(getApplicationContext(), names);
        });
    }

    @Override
    public void songPick(final SongFinder.Hit h) {
        main.post(() -> {
            main.removeCallbacks(songTimeout);
            main.removeCallbacks(songSend);
            main.removeCallbacks(songVerify);
            songWant = h;
            songWaitSeq = -1;
            if (bydMedia.source() == h.src || mock != null) { sendSong(); return; }
            songHop = true; // 搜的时候当前不是在线音乐（或者后来换了音源）：先切到搜的那个
            main.postDelayed(songTimeout, 15000);
            if (!bydMedia.changeSource(h.src)) songDone(false, "切不到" + BydMedia.onlineName(h.src));
        });
    }

    private final Runnable songSend = this::sendSong;

    private void sendSong() {
        final SongFinder.Hit h = songWant;
        if (h == null) return;
        songHop = false;
        songWaitSeq = bydMedia.listSeq();
        String now = bydMedia.title();
        boolean playing = now != null && !now.isEmpty() && alike(now, h.title); // 点的就是正在放的：「多媒体」什么都不做、不推新队列，等得短一点
        main.removeCallbacks(songTimeout);
        main.postDelayed(songTimeout, playing ? 4000 : 9000);
        final int req = ++songReq;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> { // 同曲库：发之前、发之后问「多媒体」在不在前台，跑到前台了多半是要登录
            int before = Player.foreground(app);
            boolean ok;
            if (h.src == 10) ok = Player.insertSong(app, h.id);
            else {
                JSONObject p = new JSONObject();
                try { p.put("song", h.title); p.put("artist", h.artist); if (!h.album.isEmpty()) p.put("album", h.album); } catch (Exception ignored) { }
                ok = Player.voice(app, "searchMusic", p); // 「多媒体」把它们拼成"歌名 歌手 专辑"在这个音源里搜，插第一首
            }
            if (!ok) { main.post(() -> { if (req == songReq) songDone(false, "这台车机没有「多媒体」，放不了"); }); return; }
            if (before != 0) return;
            for (int i = 0; i < 8; i++) {
                try { Thread.sleep(400); } catch (InterruptedException e) { return; }
                if (Player.foreground(app) == 1) { main.post(() -> { if (req == songReq && songWaitSeq >= 0) songDone(false, BydMedia.onlineName(h.src) + "要登录：在「多媒体」弹出来的页面里登录以后再点"); }); return; }
            }
        }, "lg-song").start();
    }

    /** 媒体控制器推来了新东西：切过去了就发；队列变了（插进去了）过一会儿核对放的是不是这首 */
    private void songCheck() {
        if (songHop && songWant != null && bydMedia.source() == songWant.src) { songHop = false; main.postDelayed(songSend, 1200); return; }
        if (songWaitSeq >= 0 && bydMedia.listSeq() != songWaitSeq) {
            songWaitSeq = -1;
            main.removeCallbacks(songTimeout);
            main.postDelayed(songVerify, 1500);
        }
    }

    private static String bare(String s) { return s == null ? "" : s.replaceAll("[\\s\\p{P}\\p{S}]", "").toLowerCase(java.util.Locale.ROOT); }

    /** 两个名字算不算一样：去掉空格标点以后相同或者一个包含另一个（合唱的歌手有的用 / 有的用 &） */
    private static boolean alike(String a, String b) {
        String x = bare(a), y = bare(b);
        return x.isEmpty() || y.isEmpty() || x.equals(y) || x.contains(y) || y.contains(x);
    }

    /** 插进去以后在放的是不是点的那首：歌名、歌手有一样对不上就说一声放成了谁的哪首 */
    private final Runnable songVerify = () -> { if (songWant != null) songDone(true, songCheckMsg(songWant, null)); };

    /** 现在在放的和点的那首对不对得上：对得上返回 same（可以是空的），对不上说放成了谁的哪首 */
    private String songCheckMsg(SongFinder.Hit h, String same) {
        String t = bydMedia.title(), a = bydMedia.artArtist();
        if (t == null || t.isEmpty() || alike(t, h.title) && (a == null || alike(a, h.artist))) return same;
        return "「多媒体」放成了" + (a == null || a.isEmpty() ? "" : a + "的") + "《" + t + "》";
    }

    private final Runnable songTimeout = () -> {
        SongFinder.Hit h = songWant;
        if (h == null) return;
        String now = bydMedia.title();
        if (!songHop && now != null && !now.isEmpty() && alike(now, h.title)) { songDone(true, songCheckMsg(h, "正在放的就是这首")); return; } // 没推新队列：放的本来就是它
        songDone(false, songHop ? "没切到" + BydMedia.onlineName(h.src) + "，过一会儿再试" : "没放成：这首在" + BydMedia.onlineName(h.src) + "里可能放不了");
    };

    private void songDone(boolean ok, String msg) {
        main.removeCallbacks(songTimeout);
        main.removeCallbacks(songSend);
        songWant = null;
        songWaitSeq = -1;
        songHop = false;
        view.scene().songResult(ok ? 1 : 2);
        if (msg != null) ui.toast(msg);
    }

    // ================= 收藏红心、播放模式（见 BydMedia） =================
    private void heart() {
        if (!bydMedia.toggleHeart(song.title)) ui.toast("这首歌现在收藏不了");
    }

    private static final String[] MODE_NAME = { "列表循环", "随机播放", "单曲循环" };

    private void mode() {
        int m = bydMedia.nextMode(song.title);
        ui.toast(m < 0 ? "现在切不了播放模式" : MODE_NAME[m]);
    }

    // ================= 歌词定位：跳到某一句（见 Player 开头说明） =================
    @Override
    public void seek(final long ms) {
        main.post(() -> {
            if (mock != null) { mock.seek(ms); song.seekLocal(ms); }
            else if (Player.seek(this, ms)) song.seekLocal(ms); // 发给「多媒体」的导出入口，零权限
            else ui.toast("这台车跳不了歌词");
        });
    }

    // ================= 设置（场景里的 SetPage） =================
    private boolean prefsOn, fsAvail;

    private SetPage.Data prefsData() { return new SetPage.Data(st.copy(), fsAvail, Vk.supported(this)); }

    /** 现在的设置交给设置页（别处改了砖跟着翻过去） */
    private void pushPrefs() { view.scene().setPrefs(prefsData()); }

    @Override public void prefsState(final boolean open) { main.post(() -> { prefsOn = open; ui.setPrefs(open); }); }

    @Override public void prefSet(final String k, final Object v) { main.post(() -> set(k, v)); }

    private void set(String k, Object v) {
        if (("aa".equals(k) || "render".equals(k)) && SystemClock.uptimeMillis() - rebuiltAt < 1500) { pushPrefs(); return; } // 刚换过画布：不接着换（设置页的砖会翻回真正的值）
        st.set(k, v);
        st.save(this);
        apply(k);
        pushPrefs();
    }

    /** 一项设置变了以后（同网页版面板的 click） */
    private void apply(String k) {
        switch (k) {
            case "theme": applyTheme(); break;
            case "aa": case "render": rebuild(); break;
            case "font": final String f = st.font; ioH.post(() -> { Txt.load(this, f); main.post(() -> view.scene().setOpts(st)); }); break; // 后台读好再换
            case "fsBar": applyBar(); break;
            case "rain": weatherWatch(started && "auto".equals(st.rain)); rainApply(); break;
            case "clarity": Res.sharp = "sharp".equals(st.clarity); view.scene().wake(); break;
            default: view.scene().setOpts(st); break;
        }
    }

    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    private void reload() {
        Settings n = Settings.load(this), o = st.copy();
        for (String k : new String[] { "font", "theme", "aa", "render", "clarity", "fps", "water", "art", "ribbons", "rain", "intro", "reveal", "fsBar" }) {
            if (!n.get(k).equals(o.get(k))) { st.set(k, n.get(k)); apply(k); }
        }
        pushPrefs();
    }

    /** Vulkan 起不来（VkSceneView 叫的）：换成 OpenGL 的画布，设置里"渲染方式"那一行也藏起来 */
    void vkFailed() {
        if (view instanceof VkSceneView) rebuild();
        pushPrefs();
    }

    /** 抗锯齿、渲染方式只能在建画布时定：换一块新画布（场景重建，不再放开场动画） */
    private long rebuiltAt;

    private void rebuild() {
        rebuiltAt = SystemClock.uptimeMillis();
        Canvas3D old = view;
        view = newScene();
        root.addView(view.view(), 0, new FrameLayout.LayoutParams(-1, -1));
        root.removeView(old.view());
        pushLib();
        if (!started || slept) view.onPause();
        ui.setShelfOn(false); // 新画布：歌单、曲库关着
        libOn = false;
        ui.setLib(false);
        if (prefsOn) view.scene().prefs(2); // 在设置页里改的（抗锯齿、渲染方式）：新画布直接开着设置页
        ui.pushKeys();
        ui.setLocate(0, 0);
        if (booted) { pushSnap(); view.scene().boot(false); }
        view.scene().setRain(rainNow);
    }

    /** 全屏页里按设置收起 / 显示顶端状态栏 */
    private void applyBar() {
        if (!isFull()) return;
        if (st.fsBar) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    // ================= 全屏 =================
    private void fullscreen() {
        long t = SystemClock.uptimeMillis();
        if (t - fsAt < 2500) return; // 点一次 2.5 秒内不再切
        fsAt = t;
        if (isFull()) { finishAndRemoveTask(); return; }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(new Intent(this, FullActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle());
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "全屏打开失败", e);
            fsAt = 0;
            ui.toast("这台车机不让全屏打开");
        }
    }

    // ================= 深浅色：设置里选了就按设置；跟随车机时用车机的日夜模式（没有就按钟点，19～6 点是夜里） =================
    private boolean wantDay() {
        if ("light".equals(st.theme)) return true;
        if ("dark".equals(st.theme)) return false;
        if (carNight != null) return !carNight;
        String v = prefs.getString("night", null);
        if ("1".equals(v)) return false;
        if ("0".equals(v)) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 19;
    }

    private void applyTheme() {
        boolean d = wantDay();
        view.scene().setDay(d);
        ui.setDay(d);
    }

    private final Runnable clockTheme = new Runnable() { // 车机没报日夜模式时，每分钟按钟点看一次
        @Override public void run() { if ("auto".equals(st.theme) && carNight == null) applyTheme(); rainApply(); main.postDelayed(this, 60000); } // 暂停着没有媒体推送时也隔一会儿看看天气
    };

    // ================= 雨珠开不开（见 RainPick、BydWeather） =================
    private final Runnable rainCheck = this::rainApply;
    private final BydWeather weather = new BydWeather();
    private ContentObserver weatherObs;
    private BroadcastReceiver weatherRx;

    /** 按设置、天气、这首歌定下不下（媒体、设置、天气变了都调一次；每分钟也看一次——逐分钟降水按现在这一分钟算） */
    private void rainApply() {
        if (!booted) return;
        long now = SystemClock.uptimeMillis();
        if (rp.newTrack(song, now)) { main.removeCallbacks(rainCheck); main.postDelayed(rainCheck, RainPick.WAIT + 100); } // 换歌：新歌的歌词等一会儿还没到就按已有的定
        Boolean want = "on".equals(st.rain) ? Boolean.TRUE : "off".equals(st.rain) ? Boolean.FALSE : rp.auto(song, now, weather.rainingNow(System.currentTimeMillis()));
        if (want != null && want != rainNow) { rainNow = want; view.scene().setRain(want); }
    }

    /** 读一次车机天气（后台线程），读到了再定一次。天气应用每次刷新，数据变化通知和广播差不多同时到：排着一次就不再排 */
    private volatile boolean weatherQueued;
    private void weatherRead() {
        if (weatherQueued) return;
        weatherQueued = true;
        ioH.post(() -> {
            weatherQueued = false;
            if (!weather.read(getContentResolver())) { Log.w(Uiplus.TAG, "车机天气读不到：" + weather.err); return; }
            Log.w(Uiplus.TAG, "车机天气：" + weather.debug() + " 现在下雨=" + weather.rainingNow(System.currentTimeMillis()));
            main.post(this::rainApply);
        });
    }

    /** 显示着、雨珠是自动时跟着车机天气：数据接口一变就读（天气应用每 10～11 分钟刷新一次），也听它给第三方发的刷新广播；藏起来、不是自动就不听 */
    private void weatherWatch(boolean on) {
        if (on && weatherObs == null) {
            weatherObs = new ContentObserver(main) { @Override public void onChange(boolean self) { weatherRead(); } };
            try { getContentResolver().registerContentObserver(BydWeather.URI, false, weatherObs); } catch (Exception ignored) { } // 别的车机没有这个应用：读不到，当查不到天气
            weatherRx = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { weatherRead(); } };
            registerReceiver(weatherRx, new IntentFilter(BydWeather.REFRESH));
            weatherRead();
        } else if (!on && weatherObs != null) {
            try { getContentResolver().unregisterContentObserver(weatherObs); } catch (Exception ignored) { }
            try { unregisterReceiver(weatherRx); } catch (Exception ignored) { }
            weatherObs = null;
            weatherRx = null;
        }
    }

    // ================= 宿主 =================
    @Override
    public void onState(String state, String detail) {
        info.setStatus(detail, "error".equals(state));
        info.setPerms(host.canRead(), host.ready());
        ui.setBlocked(info.covering());
        if ("ready".equals(state)) afterAuth();
    }

    @Override
    public void onPermissions(boolean read, boolean exec) {
        info.setPerms(read, host.ready());
        ui.setBlocked(info.covering());
        afterAuth();
    }

    /** 有了读数据的授权、宿主后端也好了：订阅媒体（再取一次完整快照）、订阅日夜模式（宿主有这个字段才订）。
     *  开机时宿主后端常常比我们晚好：没好就等宿主发 backendReady 再订；订失败（后端没好、超时这类会自己好的）过一会儿再订，
     *  2、4、8、16 秒，之后每 30 秒一次，会话还在就一直试 */
    private void afterAuth() {
        info.setPerms(host.canRead(), host.ready());
        ui.setBlocked(info.covering());
        if (!host.ready() || !host.canRead()) return;
        final String s = host.session();
        if (!s.equals(subFor)) { // 新会话：从头订
            subFor = s;
            mediaId = dnId = null;
            mediaTries = dnTries = 0;
            main.removeCallbacks(subRetry);
            first = true;
            song.reset();
        }
        if (!host.backendReady()) return;
        if (mediaId == null) subMedia(s);
        if (dnId == null) subDn(s);
    }

    private final Runnable subRetry = this::afterAuth;

    private void retrySub(int tries) {
        main.removeCallbacks(subRetry);
        main.postDelayed(subRetry, Math.min(30000, 2000L << Math.min(tries, 4)));
    }

    /** 过一会儿就会好的错（后端没好、超时、宿主内部出错）；没权限、参数不对、宿主不支持这类再试也没用 */
    private static boolean transientErr(String code) {
        return !"PERMISSION_DENIED".equals(code) && !"INVALID_ARGUMENT".equals(code) && !"UNSUPPORTED".equals(code) && !"UNKNOWN_FIELD".equals(code);
    }

    private void subMedia(final String s) {
        final String id = mediaId = "media" + (++subSeq); // 每次换一个订阅号（一个会话里订阅号不能重复用）
        try {
            host.request("media.subscribe", new JSONObject().put("subscriptionId", id), (r, code, msg) -> {
                if (!s.equals(host.session()) || !id.equals(mediaId)) return;
                if (code != null) {
                    Log.w(Uiplus.TAG, "订阅媒体失败：" + code + " " + msg);
                    if (!transientErr(code)) { ui.toast("订阅媒体失败：" + msg); return; } // 不再试（mediaId 留着，换会话才重来）
                    mediaId = null;
                    retrySub(mediaTries++);
                    return;
                }
                mediaTries = 0;
                host.request("media.get", null, (m, c2, m2) -> { if (m != null && s.equals(host.session())) { boolean init = first; first = false; song.onMedia(m, init); } });
            });
        } catch (Exception ignored) { }
    }

    private void subDn(final String s) {
        final String id = dnId = "dn" + (++subSeq);
        host.request("capabilities", null, (r, code, msg) -> {
            if (!s.equals(host.session()) || !id.equals(dnId)) return;
            if (code != null) { if (transientErr(code)) { dnId = null; retrySub(dnTries++); } return; }
            JSONArray f = r.optJSONArray("fields");
            boolean has = false;
            for (int i = 0; f != null && i < f.length(); i++) { JSONObject o = f.optJSONObject(i); if (o != null && "display.dayNightMode".equals(o.optString("name"))) has = true; }
            if (!has) return; // 宿主没有这个字段：按钟点
            try {
                host.request("vehicle.subscribe", new JSONObject().put("subscriptionId", id).put("fields", new JSONArray().put("display.dayNightMode")), (r2, c2, m2) -> {
                    if (!s.equals(host.session()) || !id.equals(dnId)) return;
                    if (c2 == null) dnTries = 0;
                    else if (transientErr(c2)) { dnId = null; retrySub(dnTries++); }
                });
            } catch (Exception ignored) { }
        });
    }

    @Override
    public void onEvent(JSONObject m) {
        String id = m.optString("subscriptionId");
        JSONObject d = m.optJSONObject("data");
        if (d == null) { Log.w(Uiplus.TAG, "订阅推送：" + m); return; }
        if (id.equals(mediaId)) {
            boolean init = first;
            first = false;
            song.onMedia(d, init);
        } else if (id.equals(dnId)) {
            JSONObject disp = d.optJSONObject("display");
            int v = disp == null ? 0 : disp.optInt("dayNightMode");
            if (v != 16 && v != 32) return;
            carNight = v == 32;
            prefs.edit().putString("night", carNight ? "1" : "0").apply();
            applyTheme();
        }
    }

    /** 申请授权（宿主弹确认页） */
    void authorize() {
        try {
            host.authorize(new JSONObject().put("readData", true).put("executeActions", true), (r, code, msg) -> {
                if (code != null) ui.toast("授权没成：" + msg);
            });
        } catch (Exception ignored) { }
    }

    private void infoPress(String what) { if ("auth".equals(what)) authorize(); }

    // ================= 场景交回来的（GL 线程里调，回到主线程） =================
    private static final java.util.Map<String, String> SAY = new java.util.HashMap<>();
    static { SAY.put("toggle", "暂停 / 播放"); SAY.put("next", "切到下一首"); SAY.put("previous", "切到上一首"); }

    @Override
    public void press(final String cmd) {
        main.post(() -> {
            if ("settings".equals(cmd)) { view.scene().prefs(1); return; } // 右上角的键（画在场景里）
            if ("fullscreen".equals(cmd)) { fullscreen(); return; }
            if ("heart".equals(cmd)) { heart(); return; } // 收藏、播放模式不经过宿主
            if ("mode".equals(cmd)) { mode(); return; }
            if (!Player.PKG.equals(song.pkg) && !host.canExec()) { ui.toast("要先在宿主里允许「执行操作」"); authorize(); return; }
            song.press(cmd, this, (r, code, msg) -> {
                if (code == null) return;
                ui.toast("PERMISSION_DENIED".equals(code) ? "没有执行操作的授权：到宿主的「应用授权」里给琉光打开"
                    : "UNSUPPORTED".equals(code) ? "这台车机还不支持" + SAY.get(cmd)
                    : "TIMEOUT".equals(code) || "SESSION_CLOSED".equals(code) || "BACKEND_UNAVAILABLE".equals(code) ? "车机没回应，过一会儿再试"
                    : "没能" + SAY.get(cmd) + "（" + code + "）");
            });
        });
    }

    @Override
    public void btnWait(final boolean w) { main.post(() -> { ui.setWait(w); if (!w) guideLater(1500); }); }

    @Override
    public void guideAt(final float l, final float t, final float r, final float b) { main.post(() -> ui.guideHole(l, t, r, b)); }

    // ================= 新手引导（两步：封面能点也能划、曲库在歌名左边的音源图标；看过的那步不再出，存 guide、guideLib） =================
    private final Runnable guideTry = this::guideTry;

    private void guideLater(long ms) { main.removeCallbacks(guideTry); main.postDelayed(guideTry, ms); }

    /** 开场走完、有歌在放、没开着设置页、曲库和歌单时出；曲库那步等歌名左边的音源图标出来（「多媒体」在放）才讲，看过封面那步的只补这一步 */
    private void guideTry() {
        boolean cover = !prefs.getBoolean("guide", false), lib = !prefs.getBoolean("guideLib", false);
        if (!cover && !lib || ui.guiding() || !started || slept || !ui.keysUp()) return;
        if (prefsOn || libOn || ui.shelfOn() || info.covering() || !song.on) { guideLater(2000); return; }
        lib = lib && view.scene().srcIconOn();
        if (!cover && !lib) { guideLater(5000); return; }
        int[] ids = cover && lib ? new int[] { Guide.COVER, Guide.LIBRARY } : cover ? new int[] { Guide.COVER } : new int[] { Guide.LIBRARY };
        ui.showGuide(ids, new Guide.Steps() {
            @Override public void show(int id) { view.scene().guideTrack(id == Guide.COVER ? Scene.GUIDE_COVER : Scene.GUIDE_SRC); }
            @Override public void seen(int id) { prefs.edit().putBoolean(id == Guide.COVER ? "guide" : "guideLib", true).apply(); }
        }, () -> {
            view.scene().guideTrack(Scene.GUIDE_OFF);
            if (!prefs.getBoolean("guideLib", false)) guideLater(5000); // 曲库那步还没讲（当时图标没出来）
        });
    }

}
