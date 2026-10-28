package com.x3card.liuguang;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 直接连播放器的媒体会话，做宿主没有的两样：跳到某一句（歌词定位线，用 seekTo）、收藏红心（歌、歌词、封面、播放控制照旧走宿主）。
 * 要"通知使用权"（MediaListener）：安卓凭这个权限让应用读别的应用的媒体会话。比亚迪把设置里开这个权限的页面屏蔽了（点进去是"不支持"），开法：
 * - 电脑上 adb 执行 CMD（不要 root）。改 enabled_notification_listeners 这个设置值不管用：安卓 10 的通知服务不从那里读名单。
 * - 有 root 的车：琉光自己用 su 执行同一条（root 管理器弹窗问车主）。
 * 覆盖安装保留，卸载了要重开。
 * 车机自带「多媒体」（com.byd.mediacenter）实测：对外的 actions 里没写拖进度，但 onSeekTo 实现了，照样能拖；收藏是心形评分（metadata 的 USER_RATING），
 * 发 setRating 后 3 秒左右 metadata 跟着变。歌单只给了首数（session extras 的 CP_PLAY_LIST_CHANGED），没有歌名，所以没做。
 * 连哪个会话：宿主快照里 packageName 对得上的那个。红心按歌名对上宿主正在放的那首才显示（换歌时两边先后到，对不上时先藏起来）。
 * 全在主线程里用；grantByRoot 在后台线程里调
 */
final class Player {
    interface Listener {
        void changed();
        void heartFailed();
    }

    static final String CMD = "cmd notification allow_listener com.x3card.liuguang/com.x3card.liuguang.MediaListener";
    /** 红心：不显示（不知道这首收没收藏、播放器不支持）/ 没开权限（淡淡的，点了说怎么开）/ 没收藏 / 收藏了 */
    static final int HIDE = -2, NO_PERM = -1, OFF = 0, ON = 1;

    private final Context ctx;
    private final ComponentName cn;
    private final Handler main;
    private final Listener l;
    private MediaSessionManager msm;
    private MediaController mc;
    private String pkg, mdTitle;
    private Boolean mdHeart, pending; // mdHeart：会话里这首收没收藏（null：播放器没给）；pending：刚点了、等播放器改过来
    private String pendingTitle;
    private boolean on, listening;
    boolean granted;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;
    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { read(); }
        @Override public void onSessionDestroyed() { drop(); l.changed(); }
    };
    private final Runnable pendingOff;

    Player(Context ctx, Handler main, Listener l) {
        this.ctx = ctx;
        this.cn = new ComponentName(ctx, MediaListener.class);
        this.main = main;
        this.l = l;
        pendingOff = () -> { if (pending != null) { pending = null; l.heartFailed(); l.changed(); } };
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    /** 看一眼有没有通知使用权 */
    boolean check() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        granted = nm != null && nm.isNotificationListenerAccessGranted(cn);
        return granted;
    }

    /** 显示着：有权限就连上（权限是不是刚开的也在这里看） */
    void start() {
        on = true;
        if (!check()) { stopListening(); drop(); return; }
        try {
            if (msm == null) msm = ctx.getSystemService(MediaSessionManager.class);
            if (!listening) { msm.addOnActiveSessionsChangedListener(sessions, cn, main); listening = true; }
            pick(msm.getActiveSessions(cn));
        } catch (SecurityException e) { // 权限刚被收回
            granted = false;
            stopListening();
            drop();
        }
    }

    /** 藏起来：断开，不占着 */
    void stop() {
        on = false;
        stopListening();
        drop();
    }

    private void stopListening() {
        if (listening && msm != null) try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        listening = false;
    }

    /** 宿主说现在是哪个播放器在放 */
    void setPkg(String p) {
        if (p == null || p.equals(pkg)) return;
        pkg = p;
        if (on && granted) start();
    }

    private void pick(List<MediaController> list) {
        if (list == null) return;
        MediaController best = null;
        for (MediaController c : list) if (pkg != null && pkg.equals(c.getPackageName())) { best = c; break; }
        if (best == null && pkg == null && !list.isEmpty()) best = list.get(0);
        if (best != null && mc != null && best.getSessionToken().equals(mc.getSessionToken())) return;
        drop();
        if (best == null) { l.changed(); return; }
        mc = best;
        mc.registerCallback(cb, main);
        read();
    }

    private void drop() {
        if (mc != null) try { mc.unregisterCallback(cb); } catch (Exception ignored) { }
        mc = null;
        mdTitle = null;
        mdHeart = null;
        pending = null;
        main.removeCallbacks(pendingOff);
    }

    private void read() {
        MediaMetadata m = mc == null ? null : mc.getMetadata();
        mdTitle = m == null ? null : clean(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        Rating r = m == null ? null : m.getRating(MediaMetadata.METADATA_KEY_USER_RATING);
        mdHeart = r != null && r.getRatingStyle() == Rating.RATING_HEART ? r.hasHeart() : null;
        if (pending != null && (pending.equals(mdHeart) || !java.util.Objects.equals(mdTitle, pendingTitle))) { // 改过来了、或者换了歌
            pending = null;
            main.removeCallbacks(pendingOff);
        }
        l.changed();
    }

    /** 宿主正在放的这首（title）该画成什么样的红心 */
    int heart(String title) {
        if (!granted) return NO_PERM;
        if (mc == null || mdTitle == null || mdTitle.isEmpty() || !mdTitle.equals(clean(title))) return HIDE;
        Boolean h = pending != null ? pending : mdHeart;
        return h == null ? HIDE : h ? ON : OFF;
    }

    boolean connected() { return mc != null; }

    void seek(long ms) {
        if (mc == null) return;
        Log.w(Uiplus.TAG, "跳到 → " + ms);
        mc.getTransportControls().seekTo(ms);
    }

    /** 点红心：先按点下去的显示，播放器改过来了对齐；8 秒没改过来变回去（heartFailed） */
    boolean toggleHeart(String title) {
        int h = heart(title);
        if (h != ON && h != OFF) return false;
        boolean want = h == OFF;
        pending = want;
        pendingTitle = mdTitle;
        main.removeCallbacks(pendingOff);
        main.postDelayed(pendingOff, 8000);
        Log.w(Uiplus.TAG, "收藏 → " + want + "：" + mdTitle);
        mc.getTransportControls().setRating(Rating.newHeartRating(want));
        l.changed();
        return true;
    }

    // ================= 有 root 的车：自己开权限 =================
    static boolean hasSu() {
        String path = System.getenv("PATH");
        for (String d : (path == null ? "/sbin:/system/bin:/system/xbin" : path).split(":")) if (new File(d, "su").exists()) return true;
        return false;
    }

    /** 后台线程里调：用 root 执行 CMD（root 管理器弹窗问车主，最多等 60 秒）。成了返回 null，没成返回原因 */
    static String grantByRoot() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", CMD).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            StringBuilder out = new StringBuilder();
            long end = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < end) {
                while (in.available() > 0) { int n = in.read(buf); if (n > 0 && out.length() < 2000) out.append(new String(buf, 0, n)); }
                if (p.waitFor(200, TimeUnit.MILLISECONDS)) break;
            }
            if (p.isAlive()) return "等 root 授权超时";
            int code = p.exitValue();
            Log.w(Uiplus.TAG, "root 开通知使用权：退出码 " + code + " " + out.toString().trim());
            // Magisk：弹窗里点了拒绝、10 秒没点（按弹窗上选的范围记成拒绝，默认永久）、之前拒绝过都是马上返回非 0
            return code == 0 ? null : "root 没给。弹窗出来后要点允许；之前拒绝过的话，到 root 管理器（如 Magisk 的超级用户）里给琉光打开";
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "root 开通知使用权", e);
            return "没法用 root";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
