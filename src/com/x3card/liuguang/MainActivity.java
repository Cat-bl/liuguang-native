package com.x3card.liuguang;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import java.util.Calendar;

/**
 * 琉光原生版：3D 场景（Scene，照网页版 main.js）+ 右上角的键和设置面板（Ui）+ 上面一层说明（InfoView：连不上、没授权时）。
 * 宿主的数据：媒体（MediaState，照网页版 media.js）、日夜模式（深浅色跟随车机时用，没有就按钟点 6～19 点是白天）。
 * 宿主没有的收藏红心、跳到某一句（歌词定位线）直接连播放器做（Player，要通知使用权；显示着才连）。
 * 主线程把媒体快照、深浅色、设置交给 GL 线程；场景里按的键交回主线程发给宿主。
 * 全屏：宿主给外部应用的接口里没有网页卡片那套窗口切换（windows.*），所以自己在主屏上开一个全屏页（FullActivity，单独的任务）；
 * 在全屏页里点"恢复小屏"就关掉它，回到多窗口里原来那个。全屏页开着时小窗口里的场景停画。设置两边共用一份，回到前台时重新读
 */
public class MainActivity extends Activity implements Uiplus.Listener, Scene.Host, Ui.Actions {
    private static WeakReference<MainActivity> pip = new WeakReference<>(null);

    private Uiplus host;
    private FrameLayout root;
    private SceneView view;
    private InfoView info;
    private Ui ui;
    private MediaState song;
    private Player player;
    private boolean hasRoot, granting; // 车上有 root（能一键开通知使用权）、正在用 root 开
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
        io.start();
        ioH = new Handler(io.getLooper());
        host = new Uiplus(this, this);
        song = new MediaState(host, ioH, this::changed);
        player = new Player(this, main, new Player.Listener() {
            @Override public void changed() { pushSnap(); }
            @Override public void heartFailed() { ui.toast("没收藏上，过一会儿再试"); }
        });
        hasRoot = Player.hasSu();
        root = new FrameLayout(this);
        view = newScene();
        info = new InfoView(this, this::infoPress);
        ui = new Ui(this, st, this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        applyTheme();
        applyBar();
        ui.setBlocked(info.covering());
        main.postDelayed(this::boot, 1200); // 宿主一直不推也照常开场
        main.postDelayed(clockTheme, 60000);
    }

    private SceneView newScene() {
        SceneView v = new SceneView(this, this, st.aa);
        v.scene.setOpts(st);
        v.scene.setDay(wantDay());
        return v;
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        reload();
        ui.setFsMode(isFull() ? "small" : onMainScreen() ? "" : "full");
        if (!slept) { view.onResume(); player.start(); pushSnap(); }
        host.start();
        weatherWatch("auto".equals(st.rain));
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(true); }
    }

    @Override
    protected void onStop() {
        started = false;
        weatherWatch(false);
        host.background(); // 不马上断开：宿主的授权确认页会把这里盖住，授权申请挂在这个会话上
        player.stop();
        view.onPause();
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(false); }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        player.stop();
        host.quit();
        io.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (ui.isOpen()) ui.closeAll(); else super.onBackPressed();
    }

    /** 全屏页开着：小窗口里的场景停画（盖住了也看不见）；关了接着画，顺便读一下全屏页里改过的设置 */
    void sleep(boolean s) {
        if (s == slept) return;
        slept = s;
        if (s) { view.onPause(); player.stop(); }
        else if (started) { reload(); view.onResume(); player.start(); pushSnap(); }
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
        view.scene.boot(true);
        rainApply();
    }

    /** 媒体数据变了：做一份快照交给场景（第一条到了就开场） */
    private void changed() {
        pushSnap();
        if (!booted && song.on) boot();
        rainApply();
    }

    /** 交给场景一份快照（带上直接连播放器的红心） */
    private void pushSnap() {
        player.setPkg(song.pkg);
        song.heart = song.on ? player.heart(song.title) : Player.HIDE;
        view.scene.setSnap(song.snap());
        ui.setPlayer(player.granted, hasRoot, granting);
    }

    // ================= 收藏、跳到某一句（见 Player） =================
    /** 有通知使用权就是真；没有弹卡片说怎么开（可能刚用电脑开过：先再看一眼） */
    private boolean playerReady() {
        if (!player.granted) { player.start(); pushSnap(); }
        if (player.granted) return true;
        ui.card(true);
        return false;
    }

    private void heart() {
        if (!playerReady()) return;
        if (!player.toggleHeart(song.title)) ui.toast("这首歌现在收藏不了");
    }

    @Override
    public void seek(final long ms) {
        main.post(() -> {
            if (!playerReady()) return;
            if (!player.connected()) { ui.toast("连不上这个播放器，跳不过去"); return; }
            player.seek(ms);
            song.seekLocal(ms);
        });
    }

    /** 用 root 开通知使用权（Magisk 这类会弹窗问车主，在另一个线程里等） */
    @Override
    public void grant() {
        if (granting) return;
        granting = true;
        pushSnap();
        new Thread(() -> {
            final String err = Player.grantByRoot();
            main.post(() -> {
                granting = false;
                if (started && !slept) player.start(); else player.check();
                if (player.granted) ui.toast("收藏和歌词定位开好了");
                else ui.toast(err != null ? "没开成：" + err : "没开成，可以用设置里那条 adb 命令开");
                pushSnap();
            });
        }, "grant").start();
    }

    // ================= 设置 =================
    @Override
    public void set(String k, Object v) {
        st.set(k, v);
        st.save(this);
        apply(k);
        ui.refresh();
    }

    /** 一项设置变了以后（同网页版面板的 click） */
    private void apply(String k) {
        switch (k) {
            case "theme": applyTheme(); break;
            case "aa": rebuild(); break;
            case "font": final String f = st.font; ioH.post(() -> { Txt.load(this, f); main.post(() -> view.scene.setOpts(st)); }); break; // 后台读好再换
            case "fsBar": applyBar(); break;
            case "rain": weatherWatch(started && "auto".equals(st.rain)); rainApply(); break;
            default: view.scene.setOpts(st); break;
        }
    }

    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    private void reload() {
        Settings n = Settings.load(this), o = st.copy();
        for (String k : new String[] { "font", "theme", "aa", "fps", "water", "art", "ribbons", "rain", "intro", "reveal", "fsBar" }) {
            if (!n.get(k).equals(o.get(k))) { st.set(k, n.get(k)); apply(k); }
        }
        ui.refresh();
    }

    /** 抗锯齿只能在建画布时定：换一块新画布（场景重建，不再放开场动画） */
    private void rebuild() {
        SceneView old = view;
        view = newScene();
        root.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
        root.removeView(old);
        if (!started || slept) view.onPause();
        if (booted) { pushSnap(); view.scene.boot(false); }
        view.scene.setRain(rainNow);
    }

    /** 全屏页里按设置收起 / 显示顶端状态栏 */
    private void applyBar() {
        if (!isFull()) return;
        if (st.fsBar) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    // ================= 全屏 =================
    @Override
    public void fullscreen() {
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
        view.scene.setDay(d);
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
        if (want != null && want != rainNow) { rainNow = want; view.scene.setRain(want); }
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
            if ("heart".equals(cmd)) { heart(); return; } // 收藏不经过宿主
            if (!host.canExec()) { ui.toast("要先在宿主里允许「执行操作」"); authorize(); return; }
            song.press(cmd, (r, code, msg) -> {
                if (code == null) return;
                ui.toast("PERMISSION_DENIED".equals(code) ? "没有执行操作的授权：到宿主的「应用授权」里给琉光打开"
                    : "UNSUPPORTED".equals(code) ? "这台车机还不支持" + SAY.get(cmd)
                    : "TIMEOUT".equals(code) || "SESSION_CLOSED".equals(code) || "BACKEND_UNAVAILABLE".equals(code) ? "车机没回应，过一会儿再试"
                    : "没能" + SAY.get(cmd) + "（" + code + "）");
            });
        });
    }

    @Override
    public void btnWait(final boolean w) { main.post(() -> ui.setWait(w)); }
}
