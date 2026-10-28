package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 歌单长廊（3D）：车机「多媒体」的播放队列（BydMedia）排成一排立在水面上的玻璃砖（Cover Flow 那种），
 * 中间那块正对着，两边的转约 50° 侧过来、往后叠；每块砖就是场景里封面那块玻璃（同一个网格、同一个着色器），在水里有倒影。
 * - 进出：一个进度 p（0 歌词、1 歌单）带着所有东西走。p 按速度走，换方向时速度平滑地反过来（中途反悔原路退回，不跳）；
 *   每样东西在 p 的一段里用两头平的曲线走（所以 p 走到头时不会一下停住）。
 *   进：歌词散成光点流进封面 → 封面（hero）从原来的位置滑到中间、转正 → 两边的砖从它身后一块块展开 → 歌名浮上来，控制条上的按键淡掉、同一块玻璃上换成刻度条；出：倒着放。
 *   打开：歌词页点封面；回去：点正在放的那块（在中间、在侧面都行，它从原地飞回去），或者返回键。
 *   出的时候中间那块不是正在放的：它边飞回去边翻半圈，背面是正在放的封面（p = 0 时交给场景的封面玻璃砖接着画，一模一样）。
 * - 翻：按在封面那一行上左右拖跟手（两头拉过了有阻力；别处左右拖是转整个场景，场景管），甩出去按速度算出停在第几首、弹簧正好停在那一首上；拖控制条上的刻度条直接跳；
 *   点刻度条上发光的点、或者旁边的"正在放"回到正在放的那首（按距离定时长，快的时候砖是空玻璃一闪而过）。
 * - 点中间那块放它；点侧面那块：滑到中间并直接放；点正在放的那块：回到歌词。水面上那道光马上挪过去（闪着），车机确认换好歌了就稳住。
 * - 中间那块停下来：天空（极光、背景封面、高亮色）换成这首封面的颜色；回到正在放的那首、或者回歌词时换回来。
 * - 搜索：点刻度条左头的放大镜，键盘（SearchPad）从底下滑上来，按拼音首字母 / 全拼 / 英文搜（SongIndex），边打边筛：
 *   长廊里只排搜到的歌（view），留下的砖从原来的位置滑到新位置，没搜中的缩小沉下去；砖那一行缩小一点往上让开键盘。
 *   点一首放它、收起键盘回到整个歌单，停在刚才那首上。
 * 省：封面放在一张 2048×1024 的图集里（32 格，每格 256 连同 128 / 64 / 32 三级缩小图，后台线程缩好按格上传，不用整张重新生成缩小图），
 * 按最近用过的轮换，翻的时候不新建、不删贴图；中间那块停下来才取 512 的大图淡过去（最多留 3 张）；甩得快时不取封面（砖是空的玻璃，取到了淡进来）。
 * 关上歌单、动画放完：图集、歌名、大图、键盘全部释放（下载过的封面在磁盘缓存里，再打开不用重新下）。
 * 全在 GL 线程里（歌单、开关由主线程交进来）
 */
final class Shelf {
    private final Scene sc;
    private final SearchPad pad;
    volatile SongCovers art;

    Shelf(Scene sc) { this.sc = sc; pad = new SearchPad(sc); }

    // ================= 主线程交进来的 =================
    private static final class In { final List<BydMedia.Song> songs; final int cur; In(List<BydMedia.Song> s, int c) { songs = s; cur = c; } }
    private volatile In in = new In(Collections.emptyList(), -1);
    private volatile int req; // 1 打开、2 关上、3 开 / 关、4 返回（先收键盘再关歌单）
    private volatile boolean locateReq;

    void setSongs(List<BydMedia.Song> s, int cur) { in = new In(s, cur); }
    void request(int r) { req = r; }
    void locate() { locateReq = true; }

    // ================= 状态 =================
    private List<BydMedia.Song> songs = Collections.emptyList();
    private int n, playing = -1;
    float p;              // 0 歌词、1 歌单
    private float pv;     // p 的速度
    private boolean want; // 要开还是要关
    private int hero = -1, closeFrom = -1; // closeFrom：点了正在放的那块关歌单，从它那里飞回去（不先滚到中间）
    private boolean heroFlip;
    private static final float DUR = 1.5f; // 进 / 出一趟多久（秒）

    /** 歌单开着、或者还在开 / 关的路上（这时场景的封面砖、控制条、歌词交给这里） */
    boolean engaged() { return want || p > 0; }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static float smooth(float a, float b, float v) { float t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float easeOut(float t) { float u = 1 - t; return 1 - u * u * u; }

    // 给场景用的：每样东西在 p 的哪一段走（见开头）
    /** 歌词第 row 句（离当前句几句）淡掉多少 */
    float lyK(int row) { int r = Math.min(Math.abs(row), 6); return p <= 0 ? 0 : smooth(0.02f * r, 0.26f + 0.02f * r, p); }
    /** 控制条上的按键淡掉多少（同一块玻璃上换成刻度条） */
    float ctlK() { return p <= 0 ? 0 : smooth(0.06f, 0.4f, p); }
    /** 竖屏时歌名往左挪多少（封面走了） */
    float titleK() { return p <= 0 ? 0 : smooth(0.15f, 0.7f, p); }
    /** 键盘开着多少（控制条沉下去） */
    float searchK() { return pad.k.x; }

    // 光点（场景画）：歌词散成光点流进 hero 砖（起点按 hero 砖的姿态换算，砖在飞也跟得上），出的时候倒着走
    boolean partAimed;
    float partMax = 2;
    final float[] heroM = new float[16];
    boolean partOn() { return partAimed && p > 0 && p < 0.72f; }
    float partT() { return partMax * (1 - clamp(p / 0.72f, 0, 1)); }
    float partFade() { return 1 - smooth(0, 0.05f, p); }

    // ================= 布局（场景的尺寸变了重算） =================
    float Cf = 1, yC, zC = 70;
    private float X1, G0, Zb, itemPx = 1, rsK = 0.84f, yK;
    private float rs = 1, yR; // 这一帧砖那一行的缩放、中间的高度（键盘开着时缩小、往上让）
    private static final float KS = 0.84f, ANG = 0.87f;
    private int N = 4;

    void layout() {
        float W = sc.W, H = sc.H;
        Cf = sc.narrow ? Math.min(W * 0.56f, H * 0.34f) : Math.min(H * 0.38f, W * 0.3f);
        yC = sc.water + H * 0.13f + Cf / 2;
        yR = yC;
        X1 = Cf * 0.7f; G0 = Cf * 0.3f; Zb = Cf * 0.55f; itemPx = Cf * 0.6f;
        N = (int) clamp((float) Math.ceil((W * 0.56f - X1) / G0) + 1, 2, 8);
    }

    /** 离中间 d 首（可以是小数）的那块砖的姿态：x, y, z, rx, ry, rz, 大小。
     *  0～1 之间 x 用三次曲线接上外面的直线（速度连续），转角、远近、大小用两头平的曲线；底边对齐立在同一条线上 */
    private void slot(float d, float[] o) {
        float s = Math.abs(d), sg = d < 0 ? -1 : 1, t = Math.min(s, 1), sm = t * t * (3 - 2 * t), x;
        if (s <= 1) { float t2 = t * t, t3 = t2 * t; x = (t3 - 2 * t2 + t) * X1 * 1.5f + (-2 * t3 + 3 * t2) * X1 + (t3 - t2) * G0; }
        else x = X1 + (s - 1) * G0;
        float k = 1 + (KS - 1) * sm, c = Cf * rs;
        o[0] = sg * x * rs; o[1] = yR - c * (1 - k) / 2; o[2] = zC - Zb * rs * sm; o[3] = 0; o[4] = -sg * ANG * sm; o[5] = 0; o[6] = c * k;
    }

    // ================= 显示的列表：没在搜是整个歌单，在搜是搜到的那几首（view 里是歌单序号）；下面的"位置"都是在这个列表里的 =================
    private int[] view;
    private int vn;
    private int[] kOf = new int[0]; // 歌单序号 → 位置（不在列表里是 -1）
    private float viewAt = -10;

    private int vi(int k) { return view == null ? k : view[k]; }
    private int vk(int i) { return i < 0 || i >= n ? -1 : view == null ? i : kOf[i]; }

    // ================= 滚动 =================
    private final Spring posS = new Spring(0, 49, 14, 0.0004f); // 临界阻尼，停稳约 0.8 秒
    private float pos, lastPos, spd;
    private boolean tw;
    private float twFrom, twTo, twT, twDur;
    private int pendPlay = -1;
    private float pendAt;

    private int focus() { return vn == 0 ? -1 : (int) clamp(Math.round(pos), 0, vn - 1); }
    private int focusSong() { int f = focus(); return f < 0 ? -1 : vi(f); }

    /** 平滑滚到第 t 个位置（按距离定时长：近的 0.5 秒，远的最长 1.3 秒） */
    private void tweenTo(int t) {
        if (vn == 0) return;
        t = (int) clamp(t, 0, vn - 1);
        float dist = Math.abs(t - pos);
        if (dist < 0.01f) { posS.set(t); return; }
        tw = true; twFrom = pos; twTo = t; twT = 0;
        twDur = clamp(0.5f + 0.12f * (float) (Math.log(1 + dist) / Math.log(2)), 0.5f, 1.3f);
    }

    private static float inOut(float t) { return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }

    /** 换显示的列表：砖从现在在画面上的地方接着走到新位置（不跳），停在原来那首上（不在新列表里就停在第一首） */
    private void setView(int[] nv, float time) {
        int fs = focusSong();
        for (BS st : live.values()) { st.k.x -= pos; st.k.t -= pos; }
        view = nv;
        vn = view == null ? n : view.length;
        if (view != null) {
            if (kOf.length != n) kOf = new int[n];
            java.util.Arrays.fill(kOf, -1);
            for (int k = 0; k < view.length; k++) kOf[view[k]] = k;
        }
        int nk = vk(fs);
        float np = nk >= 0 ? nk : 0;
        tw = false;
        posS.set(np);
        pos = lastPos = np;
        for (Map.Entry<Integer, BS> e : live.entrySet()) {
            BS st = e.getValue();
            st.k.x += np; st.k.t += np;
            int k = vk(e.getKey());
            st.gone = k < 0;
            if (k >= 0) st.k.t = k;
        }
        viewAt = time;
        lightInit = false;
    }

    // ================= 开 / 关 =================
    private void setWant(boolean w) {
        if (w == want) return;
        if (w && n == 0) return;
        want = w;
        if (w) {
            if (p == 0) { // 从歌词开始：正在放的那首当 hero（场景的封面砖变成它）
                engage();
                hero = playing >= 0 ? playing : 0;
                heroFlip = hero != playing;
                posS.set(hero); pos = lastPos = hero; tw = false;
                pendPlay = -1;
                partAimed = sc.shelfAim(0, yC, zC, Cf);
            }
        } else {
            if (pad.isOpen() || view != null) closeSearch(sc.clock);
            if (p == 1) { // 从歌单开始：点的那块（正在放的）或者中间那块当 hero（不是正在放的就边飞边翻到正在放的封面）
                tw = false;
                hero = closeFrom >= 0 ? closeFrom : focus();
                heroFlip = hero != playing;
                if (closeFrom < 0) posS.t = hero; else posS.t = Math.round(posS.x);
                partAimed = sc.shelfAim(0, yC, zC, Cf);
            }
            preview(-1);
        }
        closeFrom = -1;
        sc.host.shelfState(w);
    }

    // ================= 搜索 =================
    private String query = "";
    private SongIndex index;
    private volatile SongIndex built;
    private boolean indexing;

    private void openSearch() {
        if (pad.isOpen()) return;
        query = "";
        pad.open();
        pad.setText("", "");
        ensureIndex();
        tw = false;
    }

    /** 收起键盘：回到整个歌单，停在现在这首上 */
    private void closeSearch(float time) {
        pad.close();
        query = "";
        if (view != null) setView(null, time);
    }

    private void ensureIndex() {
        if (indexing || index != null && index.songs == songs) return;
        indexing = true;
        SongIndex.build(songs, x -> { built = x; sc.wake(); });
    }

    private void key(char c, float time) {
        if (c == SearchPad.HIDE) { closeSearch(time); return; }
        if (c == SearchPad.BACK) { if (!query.isEmpty()) query = query.substring(0, query.length() - 1); }
        else if (c == SearchPad.CLEAR) query = "";
        else if (query.length() < 24) query += c;
        applyQuery(time);
    }

    private void applyQuery(float time) {
        if (query.isEmpty()) { if (view != null) setView(null, time); pad.setText("", ""); return; }
        if (index == null || index.songs != songs) { pad.setText(query, "…"); ensureIndex(); return; } // 索引好了再筛
        int[] r = index.match(query);
        setView(r, time);
        pad.setText(query, r.length == 0 ? "没有找到" : "找到 " + r.length + " 首");
    }

    // ================= 每帧 =================
    /** 先走：开关、歌单、进度 p（场景其它东西要用 p） */
    boolean tickP(float dt) {
        In s = in;
        if (s.songs != songs) { // 车机每次换歌都会再推一遍歌单（新的列表）：长度没变就不碰滚动（不然甩到一半停住）
            boolean same = s.songs.size() == songs.size();
            songs = s.songs; n = songs.size();
            sc.songsAvail(n > 0);
            if (n == 0) setWant(false);
            if (!same && view != null) { view = null; kOf = new int[0]; }
            vn = view == null ? n : view.length;
            if (posS.x > vn - 1 || posS.t > vn - 1) { tw = false; posS.set(Math.max(0, vn - 1)); }
            if (pad.isOpen()) ensureIndex();
        }
        if (s.cur != playing) playing = s.cur;
        int r = req;
        if (r != 0) {
            req = 0;
            if (r == 4) { if (pad.isOpen()) closeSearch(sc.clock); else setWant(false); }
            else setWant(r == 1 || (r == 3 && !want));
        }
        if (locateReq) { locateReq = false; int pk = vk(playing); if (want && pk >= 0) tweenTo(pk); }
        float target = want ? 1 : 0;
        if (p == target && pv == 0) return false;
        float dir = Math.signum(target - p);
        pv += (dir / DUR - pv) * (1 - (float) Math.exp(-dt / 0.07)); // 速度平滑地转向（中途反悔时不跳）
        p += pv * dt;
        if (p >= 1) { p = 1; if (pv > 0) pv = 0; }
        if (p <= 0) { p = 0; if (pv < 0) pv = 0; }
        if (p == target && Math.abs(pv) < 0.02f) { pv = 0; if (p == 0) release(); }
        return true;
    }

    private final float[] tmpA = new float[7], tmpB = new float[7], tmpC = new float[7];
    private int frame;

    /** 摆砖、取封面、歌名、刻度条、水面的光、键盘（在场景的封面算完之后） */
    boolean tick(float dt, float time) {
        if (!engaged()) return false;
        frame++;
        boolean moving = p < 1 || want != (p == 1);
        drain(time);
        SongIndex b = built;
        if (b != null) { built = null; indexing = false; index = b; if (b.songs != songs) ensureIndex(); else if (pad.isOpen() && !query.isEmpty()) applyQuery(time); }
        if (pad.tick(dt)) moving = true;
        float sk = pad.k.x;
        fitRow();
        rs = mix(1, rsK, sk);
        yR = mix(yC, yK, sk);
        // ---- 滚动 ----
        if (g != null && g.drag) { /* 跟手，在 touch 里改 */ }
        else if (tw) {
            twT += dt;
            float k = inOut(Math.min(1, twT / twDur));
            posS.set(mix(twFrom, twTo, k));
            if (twT >= twDur) tw = false;
            moving = true;
        } else if (posS.step(dt)) moving = true;
        pos = posS.x;
        spd = dt > 0 ? Math.abs(pos - lastPos) / dt : 0;
        lastPos = pos;
        if (pendPlay >= 0 && (pendPlay == playing || time - pendAt > 6)) pendPlay = -1;
        // ---- 砖 ----
        if (layoutBricks(dt, time)) moving = true;
        if (want) fetch(); // 一开就要（砖在 p 0.4 起展开，从磁盘缓存解出来来得及）
        if (labelTick(dt, time)) moving = true;
        if (scrubTick(dt, time)) moving = true;
        if (lightTick(dt, time)) moving = true;
        previewTick(time);
        locateTick();
        for (int k = 0; k < nb; k++) { Brick x = bricks[k]; if (x.art > 0 && x.art < 1 || x.hiK > 0 && x.hiK < 1) moving = true; } // 封面正在淡进来（没取到的不算，不然一直出帧）
        return moving;
    }

    /** 键盘开着时砖那一行缩多小、放多高：歌名在键盘上面、砖的上沿不出画面（窗口矮的时候缩得多） */
    private void fitRow() {
        float H = sc.H, fz = sc.dpx / (sc.dpx - zC);
        float bottom = pad.top() + H * 0.025f + H * 0.115f + H * 0.015f; // 键盘上沿、空一点、歌名两行、空一点：砖的下沿（画面上）
        float top = sc.narrow ? sc.aY - sc.aF * 0.7f - H * 0.02f : H * 0.5f - H * 0.035f; // 竖屏窄：顶上一整排是正在放的歌名，到它下面为止；横屏到画面上沿（左上角的歌名这时淡掉）
        float room = top - bottom;
        rsK = clamp(room / (Cf * fz), 0.5f, 0.84f);
        yK = Math.max(yC, (bottom + Cf * rsK * fz / 2) / fz);
    }

    // ================= 砖 =================
    private static final class Brick {
        int i, k, slot = -1;
        float d, art, back, hiK, dim, sheen, halo;
        int tF, tF2, tB;
        final float[] rF = new float[4], rF2 = new float[4], rB = new float[4], m = new float[16];
    }

    /** 画面上的一块砖（按歌单序号记）：k 是它在列表里的位置（换列表时弹簧走过去），vis 0 是缩没了，gone 是不在列表里了 */
    private static final class BS { final Spring k = new Spring(0, 49, 14, 0.0005f); float vis = 1; boolean gone; int mark; }

    private final Brick[] bricks = new Brick[24];
    { for (int i = 0; i < bricks.length; i++) bricks[i] = new Brick(); }
    private final Map<Integer, BS> live = new HashMap<>();
    private int nb, mark;
    private static final float[] FULL = { 0, 0, 1, 1 };

    private boolean layoutBricks(float dt, float time) {
        nb = 0;
        boolean moving = false;
        float heroK = easeOutIn(smooth(0.12f, 0.72f, p));
        int f0 = (int) Math.floor(pos) - N - 1, f1 = (int) Math.ceil(pos) + N + 1;
        // hero 先算（别的砖从它身后展开）；开关歌单时没在搜，位置就是歌单序号
        boolean heroLive = hero >= 0 && hero < n && p < 1 && view == null;
        if (heroLive) {
            slot(hero - pos, tmpB);
            float[] cp = sc.cvPose;
            for (int k = 0; k < 7; k++) tmpA[k] = mix(cp[k], tmpB[k], heroK);
            if (heroFlip) tmpA[4] += (float) Math.PI * (1 - smooth(0.2f, 0.7f, p));
            Brick b = add(hero, hero, hero - pos);
            G.compose(b.m, tmpA[0], tmpA[1], tmpA[2], tmpA[3], tmpA[4], tmpA[5], tmpA[6], tmpA[6], tmpA[6]);
            System.arraycopy(b.m, 0, heroM, 0, 16);
            b.sheen = 0.75f + tmpA[4] * 0.9f - tmpA[3] * 0.6f;
            b.halo = 1;
            texHero(b, heroK);
        }
        mark++;
        float ve = 1 - (float) Math.exp(-dt / 0.09);
        for (int k = Math.max(0, f0); k <= Math.min(vn - 1, f1); k++) {
            int i = vi(k);
            if (heroLive && i == hero) continue;
            BS st = live.get(i);
            if (st == null) { st = new BS(); st.k.set(k); st.vis = time - viewAt < 0.45f ? 0 : 1; live.put(i, st); }
            st.gone = false; st.k.t = k; st.mark = mark;
            if (st.k.step(dt)) moving = true;
            if (st.vis < 1) { st.vis = st.vis > 0.995f ? 1 : st.vis + (1 - st.vis) * ve; moving = true; }
            if (!place(i, k, st, heroLive, time)) break;
        }
        Iterator<Map.Entry<Integer, BS>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, BS> e = it.next();
            BS st = e.getValue();
            if (st.mark == mark) continue;
            if (!st.gone) { it.remove(); continue; } // 翻出了窗口
            st.vis -= st.vis * ve; // 没搜中：缩小沉下去
            st.k.step(dt);
            moving = true;
            if (st.vis < 0.02f) { it.remove(); continue; }
            place(e.getKey(), -1, st, false, time);
        }
        // 从近到远排（先画近的，被挡住的像素不用算；点的时候也先看近的）
        for (int a = 1; a < nb; a++) { Brick x = bricks[a]; int c = a - 1; while (c >= 0 && Math.abs(bricks[c].d) > Math.abs(x.d)) { bricks[c + 1] = bricks[c]; c--; } bricks[c + 1] = x; }
        return moving;
    }

    /** 摆一块砖（开关歌单时从 hero 身后展开 / 收回去；缩小沉下去的按 vis）；砖用完了返回假 */
    private boolean place(int i, int k, BS st, boolean heroLive, float time) {
        float d = st.k.x - pos;
        slot(d, tmpB);
        float fan = 1;
        if (p < 1) {
            int j = Math.min(Math.abs(k - (hero >= 0 ? hero : focus())), N);
            float a = 0.4f + 0.22f * (Math.max(j, 1) - 1) / Math.max(1, N - 1);
            fan = easeOut(smooth(a, a + 0.38f, p));
            if (fan <= 0.001f) return true;
            float[] h = heroLive ? tmpA : tmpB;
            tmpC[0] = h[0]; tmpC[1] = h[1]; tmpC[2] = h[2] - Cf * 0.5f; tmpC[3] = 0; tmpC[4] = tmpB[4]; tmpC[5] = 0; tmpC[6] = h[6] * 0.82f;
            for (int q = 0; q < 7; q++) tmpB[q] = mix(tmpC[q], tmpB[q], fan);
        }
        if (st.vis < 1) { float v = easeOut(st.vis); tmpB[1] -= (1 - v) * tmpB[6] * 0.35f; tmpB[6] *= v; }
        if (Math.abs(tmpB[0]) - tmpB[6] * 0.5f > sc.W * 0.75f || tmpB[6] < 0.5f) return true; // 跑出画面了
        Brick b = add(i, k, d);
        if (b == null) return false;
        G.compose(b.m, tmpB[0], tmpB[1], tmpB[2], tmpB[3], tmpB[4], tmpB[5], tmpB[6], tmpB[6], tmpB[6]);
        b.sheen = 0.75f + tmpB[4] * 0.9f;
        b.halo = Math.max(0, 1 - Math.abs(d)) * fan * st.vis;
        texPlain(b, time);
        return true;
    }

    private static float easeOutIn(float t) { return t * t * (3 - 2 * t); }

    private Brick add(int i, int k, float d) {
        if (nb >= bricks.length) return null;
        Brick b = bricks[nb++];
        b.i = i; b.k = k; b.d = d; b.dim = 1;
        return b;
    }

    /** hero 砖的贴图：正在放的那首用场景封面砖一模一样的（交接时看不出来）；翻面的背面是正在放的封面 */
    private void texHero(Brick b, float heroK) {
        if (!heroFlip) {
            b.tF = sc.texFront; b.tF2 = sc.texFront2; b.tB = sc.texBack;
            System.arraycopy(FULL, 0, b.rF, 0, 4); System.arraycopy(FULL, 0, b.rF2, 0, 4); System.arraycopy(FULL, 0, b.rB, 0, 4);
            b.art = 1; b.back = 1; b.hiK = sc.gFMix;
            b.dim = mix(sc.gDim, 1, heroK);
            return;
        }
        texPlain(b, sc.clock);
        b.tB = sc.texFront;
        System.arraycopy(FULL, 0, b.rB, 0, 4);
        b.back = 1;
    }

    /** 普通砖：图集里那一格（没取到是空玻璃，取到了淡进来）+ 有大图就叠上大图；正在放的那首用场景的封面（歌名对得上时） */
    private void texPlain(Brick b, float time) {
        BydMedia.Song s = songs.get(b.i);
        Hi hi = hiOf.get(b.i);
        if (b.i == playing && sc.coverTitle.equals(s.title) && sc.cover != null) {
            int t = sc.tex(sc.cover);
            b.tF = t; b.tF2 = t; b.tB = t;
            System.arraycopy(FULL, 0, b.rF, 0, 4); System.arraycopy(FULL, 0, b.rF2, 0, 4); System.arraycopy(FULL, 0, b.rB, 0, 4);
            b.art = 1; b.back = 1; b.hiK = 0; b.slot = -1;
            return;
        }
        Integer sl = slotOf.get(s.cover);
        b.slot = sl == null ? -1 : sl;
        if (b.slot >= 0) { slotUsed[b.slot] = frame; slotRect(b.slot, b.rF); b.art = clamp((time - slotAt[b.slot]) / 0.28f, 0, 1); }
        else { System.arraycopy(FULL, 0, b.rF, 0, 4); b.art = 0; }
        b.tF = atlas; b.tB = atlas;
        System.arraycopy(b.rF, 0, b.rB, 0, 4);
        if (hi != null && hi.url.equals(s.cover)) {
            b.tF2 = sc.tex(hi.b);
            System.arraycopy(FULL, 0, b.rF2, 0, 4);
            b.hiK = clamp((time - hi.at) / 0.3f, 0, 1);
            if (b.slot < 0) { b.art = b.hiK; b.hiK = 1; }
        } else { b.tF2 = atlas; System.arraycopy(b.rF, 0, b.rF2, 0, 4); b.hiK = 0; }
        b.back = b.art;
    }

    // ================= 封面图集 =================
    private static final int AW = 2048, AH = 1024, TS = SongCovers.TILE, COLS = AW / TS, SLOTS = COLS * (AH / TS);
    private int atlas;
    private final String[] slotUrl = new String[SLOTS];
    private final int[] slotUsed = new int[SLOTS];
    private final float[] slotAt = new float[SLOTS];
    private final Map<String, Integer> slotOf = new HashMap<>();
    private final java.util.Set<String> asked = new java.util.HashSet<>();
    private volatile boolean alive;
    private int gen;

    private static final class Got { final int gen; final String url; final boolean big; final Bitmap[] mips; final Palette pal; Got(int g, String u, boolean b, Bitmap[] m, Palette p) { gen = g; url = u; big = b; mips = m; pal = p; } }
    private final ConcurrentLinkedQueue<Got> got = new ConcurrentLinkedQueue<>();

    private final SongCovers.Sink sink = new SongCovers.Sink() {
        @Override public void got(String url, boolean big, Bitmap[] mips, Palette pal) {
            if (!alive) { recycle(mips); return; } // 歌单已经关了
            Shelf.this.got.add(new Got(gen, url, big, mips, pal));
            sc.wake();
        }
    };

    private static void recycle(Bitmap[] m) { if (m != null) for (Bitmap b : m) if (b != null) b.recycle(); }

    private static void slotRect(int s, float[] r) {
        float x0 = (s % COLS) * TS, y0 = (s / COLS) * TS;
        r[0] = (x0 + 0.5f) / AW; r[1] = (y0 + 0.5f) / AH; r[2] = (TS - 1f) / AW; r[3] = (TS - 1f) / AH;
    }

    /** 开歌单（从歌词开始）：建图集（每格带 4 级缩小图，RGB565：4MB + 缩小图约 1.3MB） */
    private void engage() {
        gen++;
        alive = true;
        if (atlas != 0) return;
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        atlas = t[0];
        G.bindTex(0, atlas);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, SongCovers.LEVELS, GLES30.GL_RGB565, AW, AH);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, SongCovers.LEVELS - 1);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    /** 关完了：图集、歌名、大图、排着没传的全部还掉（键盘收完自己还；搜索索引留着，歌单没变下次直接用） */
    private void release() {
        alive = false;
        G.free(atlas);
        atlas = 0;
        forgetSlots();
        Got x;
        while ((x = got.poll()) != null) recycle(x.mips);
        hiOf.clear();
        live.clear();
        freeLabel(lbl); lbl = null;
        for (Lbl o : lblOld) freeLabel(o);
        lblOld.clear();
        G.free(countTex); countTex = 0; countText = "";
        if (countBmp != null) { countBmp.recycle(); countBmp = null; }
        hero = -1;
        partAimed = false;
        previewIdx = -1;
        locDir = 0;
        sc.host.locate(0, 0);
        nb = 0;
    }

    private void forgetSlots() {
        java.util.Arrays.fill(slotUrl, null);
        java.util.Arrays.fill(slotUsed, 0);
        slotOf.clear();
        asked.clear();
    }

    /** 取到的封面传上去：每帧最多 3 格（一格四级约 170KB），大图收进来（画的时候场景按需传） */
    private void drain(float time) {
        int up = 0;
        Got x;
        while (up < 3 && (x = got.poll()) != null) {
            if (x.gen != gen || atlas == 0) { recycle(x.mips); continue; }
            if (x.big) {
                if (x.mips == null) continue;
                Hi h = new Hi();
                h.url = x.url; h.b = x.mips[0]; h.pal = x.pal; h.at = time;
                int idx = indexOf(x.url);
                if (idx < 0) continue;
                hiOf.put(idx, h);
                trimHi();
                continue;
            }
            asked.remove(x.url);
            if (x.mips == null || slotOf.containsKey(x.url)) { recycle(x.mips); continue; }
            int s = freeSlot();
            if (s < 0) { recycle(x.mips); continue; }
            G.bindTex(0, atlas);
            int px = (s % COLS) * TS, py = (s / COLS) * TS;
            for (int l = 0; l < x.mips.length; l++) GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, l, px >> l, py >> l, x.mips[l]);
            recycle(x.mips);
            slotUrl[s] = x.url; slotOf.put(x.url, s); slotAt[s] = time; slotUsed[s] = frame;
            up++;
        }
    }

    /** 这张封面是哪首的（先找中间附近的） */
    private int indexOf(String url) {
        int f = Math.max(0, focusSong());
        for (int k = 0; k < n; k++) { int i = (f + (k % 2 == 0 ? k / 2 : -(k / 2 + 1)) + n * 4) % n; if (url.equals(songs.get(i).cover)) return i; }
        return -1;
    }

    /** 空格子，没有就挑最久没画过的（这一帧、上一帧画了的不动） */
    private int freeSlot() {
        int best = -1, old = Integer.MAX_VALUE;
        for (int s = 0; s < SLOTS; s++) {
            if (slotUrl[s] == null) return s;
            if (slotUsed[s] < frame - 1 && slotUsed[s] < old) { old = slotUsed[s]; best = s; }
        }
        if (best >= 0) { slotOf.remove(slotUrl[best]); slotUrl[best] = null; }
        return best;
    }

    /** 从中间往两边要封面（甩得快时不要；同时最多 4 张在路上） */
    private void fetch() {
        SongCovers a = art;
        if (a == null || atlas == 0 || spd > 10 || asked.size() >= 4) return;
        int f = focus();
        for (int k = 0; k <= N + 4 && asked.size() < 4; k++) {
            for (int sg = 0; sg < (k == 0 ? 1 : 2); sg++) {
                int v = f + (sg == 0 ? k : -k);
                if (v < 0 || v >= vn) continue;
                String u = songs.get(vi(v)).cover;
                if (u.isEmpty() || slotOf.containsKey(u) || asked.contains(u)) continue;
                if (a.load(u, false, sink)) asked.add(u);
                if (asked.size() >= 4) return;
            }
        }
    }

    // ================= 中间那块的大图、天空跟着变色 =================
    private static final class Hi { String url; Bitmap b; Palette pal; float at; }
    private final Map<Integer, Hi> hiOf = new HashMap<>();
    private int previewIdx = -1, settleIdx = -1;
    private float settleAt;

    /** 场景的封面贴图要不要留着（大图也走场景的贴图表） */
    boolean keeps(Bitmap b) { for (Hi h : hiOf.values()) if (h.b == b) return true; return false; }

    private void trimHi() {
        int f = focusSong();
        while (hiOf.size() > 3) {
            Integer far = null;
            for (Integer k : hiOf.keySet()) if (k != f && k != previewIdx && (far == null || Math.abs(k - f) > Math.abs(far - f))) far = k;
            if (far == null) break;
            hiOf.remove(far); // 贴图等场景下一帧回收（不在用了）
        }
    }

    /** 停稳在一首上 0.3 秒：要它的大图；大图到了天空换成它的颜色（正在放的那首：换回来） */
    private void previewTick(float time) {
        int f = focusSong();
        boolean still = want && p == 1 && f >= 0 && g == null && !tw && Math.abs(posS.x - posS.t) < 0.02f && spd < 0.05f;
        if (!still) { settleIdx = -1; return; }
        if (f != settleIdx) { settleIdx = f; settleAt = time; return; }
        if (time - settleAt < 0.3f) return;
        boolean isPlaying = f == playing && sc.coverTitle.equals(songs.get(f).title);
        if (isPlaying) { preview(-1); return; }
        Hi h = hiOf.get(f);
        String u = songs.get(f).cover;
        if (h == null || !h.url.equals(u)) { SongCovers a = art; if (a != null && !u.isEmpty()) a.load(u, true, sink); return; }
        if (previewIdx != f) { previewIdx = f; sc.shelfPreview(h.b, h.pal); }
    }

    private void preview(int idx) {
        if (idx < 0 && previewIdx >= 0) { previewIdx = -1; sc.shelfPreview(null, null); }
    }

    // ================= 中间那首的歌名、歌手（停下来、慢慢翻时才写，快的时候淡掉） =================
    private static final class Lbl { int idx; int tt, ta; G.Mesh mt, ma; float th, ah; boolean now; final Spring k = new Spring(0, 160, 24, 0.002f); }
    private Lbl lbl;
    private final List<Lbl> lblOld = new ArrayList<>();
    private float lblAt;
    private final float[] lm = new float[16];

    private boolean labelTick(float dt, float time) {
        boolean moving = false;
        int f = focusSong();
        boolean slow = spd < 5 || (g != null && g.scrub);
        if (want && slow && f >= 0 && (lbl == null || lbl.idx != f || lbl.now != (f == playing)) && time - lblAt > 0.09f) {
            lblAt = time;
            boolean scrub = g != null && g.scrub; // 拖刻度条：歌名直接换（不淡入淡出，不然一直在换、看不清）
            if (lbl != null) { if (scrub) freeLabel(lbl); else lblOld.add(lbl); }
            if (scrub) { for (Lbl o : lblOld) freeLabel(o); lblOld.clear(); }
            lbl = makeLabel(f);
            if (scrub) lbl.k.set(1);
        }
        if (lbl != null) { lbl.k.t = want && slow && lbl.idx == f ? 1 : 0; if (lbl.k.step(dt)) moving = true; }
        for (int j = lblOld.size() - 1; j >= 0; j--) {
            Lbl o = lblOld.get(j);
            o.k.t = 0;
            if (o.k.step(dt)) moving = true;
            if (o.k.x < 0.01f) { freeLabel(o); lblOld.remove(j); }
        }
        return moving;
    }

    private Lbl makeLabel(int i) {
        BydMedia.Song s = songs.get(i);
        Lbl o = new Lbl();
        o.idx = i;
        o.now = i == playing;
        float H = sc.H, maxW = Math.min(sc.W * 0.9f, Cf * 1.9f);
        Txt.Label t = Txt.label(s.title.isEmpty() ? "未知歌曲" : s.title, H * (sc.narrow ? 0.034f : 0.042f), maxW, true);
        String sub = s.artist + (s.dur > 0 ? (s.artist.isEmpty() ? "" : " · ") + mmss(s.dur) : "");
        Txt.Label a = Txt.label(sub, H * (sc.narrow ? 0.024f : 0.028f), maxW, false);
        o.tt = G.texture(t.bmp, false); t.bmp.recycle();
        o.ta = G.texture(a.bmp, false); a.bmp.recycle();
        o.mt = G.quad(-t.w / 2, -t.h / 2, t.w / 2, t.h / 2);
        o.ma = G.quad(-a.w / 2, -a.h / 2, a.w / 2, a.h / 2);
        o.th = t.h; o.ah = a.h;
        return o;
    }

    private static void freeLabel(Lbl o) { if (o == null) return; G.free(o.tt); G.free(o.ta); o.mt.free(); o.ma.free(); }

    private static String mmss(long ms) { long s = Math.max(0, ms / 1000); return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }

    // ================= 刻度条：画在控制条那块玻璃上（按键淡掉后）：左头放大镜（点了搜索），中间一条线，白的是翻到哪了，亮色的点是正在放的；控制条够宽时写第几首 =================
    private float sX0, sX1, sMx, sK;
    private boolean sCount;
    private final Spring sPress = new Spring(1, 300, 18), magPress = new Spring(1, 300, 18);
    private int countTex;
    private Bitmap countBmp;
    private String countText = "";
    private float countAt;

    private boolean scrubTick(float dt, float time) {
        boolean moving = sPress.step(dt);
        if (magPress.step(dt)) moving = true;
        float lw = sc.pillLw, lh = sc.pillLh;
        sCount = lw / lh >= 5.5f;
        sMx = -lw / 2 + lh * 0.55f;
        sX0 = -lw / 2 + lh * (sCount ? 2.45f : 1.15f);
        sX1 = lw / 2 - lh * 0.6f;
        float k = smooth(0.5f, 0.92f, p);
        sK = k * sc.pillVis;
        int th = Math.max(16, Math.round(512 * lh / lw));
        if (countBmp == null || countBmp.getHeight() != th) { if (countBmp != null) countBmp.recycle(); countBmp = Bitmap.createBitmap(512, th, Bitmap.Config.ARGB_8888); G.free(countTex); countTex = 0; countText = ""; }
        int f = focus();
        String t = vn == 0 || !sCount ? "" : (f + 1) + " / " + vn;
        if (sK > 0 && (!t.equals(countText) || countTex == 0) && (time - countAt > 0.066f || countTex == 0)) { // 一秒最多改 15 次
            countText = t;
            countAt = time;
            countBmp.eraseColor(0);
            Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            pt.setTextSize(lh * 0.26f * 512 / lw);
            pt.setColor(Color.WHITE);
            Paint.FontMetrics fm = pt.getFontMetrics();
            new Canvas(countBmp).drawText(t, 512 * (lh * 0.98f / lw), th / 2f + 2 - (fm.ascent + fm.descent) / 2, pt);
            if (countTex == 0) countTex = G.texture(countBmp, false); else G.update(countTex, countBmp);
        } else if (!t.equals(countText)) moving = true; // 还没改的下一帧接着改
        return moving || (k > 0 && k < 1);
    }

    // ================= 水面上那道光：在正在放的那块底下（刚点了别的：先挪过去、闪着，车机换好了稳住） =================
    private final Spring lightI = new Spring(0, 60, 15.5f, 0.001f);
    private boolean lightInit;
    private float lightK;
    private final float[] lightM = new float[16];

    private boolean lightTick(float dt, float time) {
        int tgt = vk(pendPlay >= 0 ? pendPlay : playing);
        if (tgt < 0) { lightK = 0; lightInit = false; return false; }
        if (!lightInit) { lightI.set(tgt); lightInit = true; }
        lightI.t = tgt;
        boolean moving = lightI.step(dt);
        slot(lightI.x - pos, tmpC);
        float pulse = pendPlay >= 0 ? 0.55f + 0.45f * (float) Math.sin(time * 7) : 0.86f + 0.14f * (float) Math.sin(time * 2.2f);
        lightK = smooth(0.7f, 1, p) * pulse * (1 - pad.k.x);
        G.compose(lightM, tmpC[0], sc.water + 0.6f, tmpC[2] + Cf * 0.08f, (float) (-Math.PI / 2), 0, 0, tmpC[6] * 1.25f, Cf * 0.7f, 1);
        return moving || pendPlay >= 0;
    }

    // ================= 回到正在放的：它跑出画面时旁边出一个"正在放"（Ui 画，按了叫 locate） =================
    private int locDir;
    private float locY;
    private final float[] locO = new float[3];

    private void locateTick() {
        int dir = 0, pk = vk(playing);
        if (want && p > 0.95f && !pad.isOpen() && pk >= 0 && Math.abs(pk - pos) > N - 0.3f) dir = pk < pos ? -1 : 1;
        float y = 0.5f;
        if (dir != 0) { // 砖的中间在画面上多高
            G.mul(PS, sc.P, sc.S);
            G.project(locO, PS, 0, yR, zC);
            y = 0.5f - locO[1] * 0.5f;
        }
        if (dir != locDir || (dir != 0 && Math.abs(y - locY) > 0.01f)) { locDir = dir; locY = y; sc.host.locate(dir, y); }
    }

    // ================= 画 =================
    private final float[] SM2 = new float[16], hm = new float[16], t2 = new float[16];

    /** 不透明的：砖（主画面、倒影都画） */
    void drawSolid(float[] V, float[] Pp) {
        if (!engaged()) return;
        G.Prog pg = sc.pGlass;
        pg.use();
        pg.m4("uP", Pp);
        pg.tex("uAur", 3, sc.aurFbo.tex);
        pg.f("uHalf", Scene.HALF); pg.f("uDay", sc.day);
        pg.c3("uTint", sc.gTint);
        for (int k = 0; k < nb; k++) {
            Brick b = bricks[k];
            sc.mv(V, b.m);
            pg.m4("uMV", sc.MV);
            G.mul(SM2, sc.P, sc.M1); pg.m4("uSM", SM2);
            pg.tex("uFront", 0, b.tF); pg.tex("uFront2", 1, b.tF2); pg.tex("uBack", 2, b.tB);
            pg.f4("uRF", b.rF[0], b.rF[1], b.rF[2], b.rF[3]); pg.f4("uRF2", b.rF2[0], b.rF2[1], b.rF2[2], b.rF2[3]); pg.f4("uRB", b.rB[0], b.rB[1], b.rB[2], b.rB[3]);
            pg.f2("uArt", b.art, b.back);
            pg.f("uDim", b.dim); pg.f("uSheen", b.sheen); pg.f("uFMix", b.hiK);
            sc.mGlass.draw();
        }
    }

    /** 水面上的光、中间那块的影子和光晕（只在主画面里） */
    void drawUnder(float[] V, float[] Pp) {
        if (!engaged()) return;
        if (lightK > 0.002f && pLight != null) {
            pLight.use();
            sc.mv(V, lightM);
            pLight.m4("uP", Pp); pLight.m4("uMV", sc.MV);
            float[] c = sc.day > 0.5f ? sc.playPal.deep : sc.playPal.accent;
            pLight.c3("uCol", c); pLight.f("uK", lightK); pLight.f("uDay", sc.day);
            sc.mUnit.draw();
        }
        G.Prog ph = sc.pHalo;
        ph.use();
        ph.m4("uP", Pp);
        ph.c3("uTint", sc.haloTint); ph.f("uDay", sc.day);
        Matrix.setIdentityM(t2, 0);
        Matrix.translateM(t2, 0, 0, 0, -0.09f);
        for (int k = 0; k < nb; k++) {
            Brick b = bricks[k];
            if (b.halo <= 0.01f) continue;
            G.mul(hm, b.m, t2);
            sc.mv(V, hm);
            ph.m4("uMV", sc.MV); ph.f("uK", b.halo * sc.haloK);
            sc.mHalo.draw();
        }
    }

    /** 歌名、刻度条上的东西（主画面、倒影都画） */
    void drawText(float[] V, float[] Pp) {
        if (!engaged()) return;
        float base = smooth(0.72f, 1, p);
        G.Prog pt = sc.pText;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        pt.use();
        pt.m4("uP", Pp);
        if (lbl != null) drawLabel(lbl, V, base);
        for (Lbl o : lblOld) drawLabel(o, V, base);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        if (sK > 0.001f && countTex != 0 && pScrub != null) {
            pScrub.use();
            sc.mv(V, sc.iconsM);
            pScrub.m4("uP", Pp); pScrub.m4("uMV", sc.MV);
            pScrub.f("uW", sc.pillLw); pScrub.f("uH", sc.pillLh); pScrub.f("uX0", sX0); pScrub.f("uX1", sX1); pScrub.f("uMx", sMx);
            pScrub.f("uPos", vn > 1 ? clamp(pos / (vn - 1), 0, 1) : 0);
            int pk = vk(playing);
            pScrub.f("uPlay", pk >= 0 && vn > 1 ? pk / (float) (vn - 1) : pk == 0 ? 0 : -1);
            pScrub.f("uPress", sPress.x); pScrub.f("uMag", magPress.x);
            pScrub.c3("uInk", sc.ink); pScrub.c3("uAcc", sc.playPal.accent); pScrub.f("uDay", sc.day);
            pScrub.tex("uCount", 0, countTex); pScrub.f("uK", sK);
            sc.mUnit.draw();
        }
    }

    /** 键盘（场景最上面，只在主画面里） */
    void drawPad(float[] Pp) { pad.draw(Pp); }

    private void drawLabel(Lbl o, float[] V, float base) {
        float a = o.k.x * base;
        if (a <= 0.002f) return;
        float rise = (1 - o.k.x) * -10 + (1 - base) * -14;
        float yt = yR - Cf * rs / 2 - sc.H * 0.035f + rise, ya = yt - o.th * 0.42f - o.ah * 0.5f;
        sc.pText.c3("uInk", o.now ? sc.hi : sc.ink);
        G.compose(lm, 0, yt, zC, 0, 0, 0, 1, 1, 1);
        sc.mv(V, lm);
        sc.pText.m4("uMV", sc.MV); sc.pText.tex("uMap", 0, o.tt); sc.pText.f("uA", a); sc.pText.f("uK", 1);
        o.mt.draw();
        sc.pText.c3("uInk", sc.ink);
        G.compose(lm, 0, ya, zC, 0, 0, 0, 1, 1, 1);
        sc.mv(V, lm);
        sc.pText.m4("uMV", sc.MV); sc.pText.tex("uMap", 0, o.ta); sc.pText.f("uK", 0.6f);
        o.ma.draw();
    }

    // ================= 着色器（GL 环境建好时） =================
    private static final String F_SCRUB = "uniform float uW, uH, uX0, uX1, uMx, uPos, uPlay, uPress, uMag, uK, uDay; uniform vec3 uInk, uAcc; uniform sampler2D uCount; in vec2 vUv; out vec4 o;\n"
        + "float fill(float d) { float w = fwidth(d) * 0.75; return 1.0 - smoothstep(-w, w, d); }\n"
        + "vec4 over(vec4 d, vec4 s) { return s + d * (1.0 - s.a); }\n"
        + "void main() {\n"
        + "  vec2 p = (vUv - 0.5) * vec2(uW, uH); float h = uH;\n"
        + "  float dx = max(max(uX0 - p.x, p.x - uX1), 0.0);\n"
        + "  float tr = fill(length(vec2(dx, p.y)) - h * 0.028) * 0.26;\n"
        + "  vec2 q = p - vec2(mix(uX0, uX1, uPos), 0.0); float th = h * 0.085 * uPress, tw = h * 0.17 * uPress;\n"
        + "  float thumb = fill(length(vec2(max(abs(q.x) - tw + th, 0.0), q.y)) - th) * 0.95;\n"
        + "  float pd = uPlay < 0.0 ? 1e5 : length(p - vec2(mix(uX0, uX1, uPlay), 0.0)) - h * 0.075;\n"
        + "  float dt = fill(pd), gl = exp(-max(pd, 0.0) / (h * 0.09)) * step(0.0, pd) * 0.7;\n"
        + "  vec2 m = (p - vec2(uMx, 0.0)) / uMag; float r = h * 0.13;\n"
        + "  float ring = fill(abs(length(m + vec2(r * 0.25, -r * 0.25)) - r) - h * 0.022);\n"
        + "  vec2 hd = m - vec2(r * 0.6, -r * 0.6); float ln = clamp(dot(hd, vec2(0.7071, -0.7071)), 0.0, r * 0.7);\n"
        + "  float handle = fill(length(hd - vec2(0.7071, -0.7071) * ln) - h * 0.03);\n"
        + "  float mag = max(ring, handle) * 0.9;\n"
        + "  float ta = texture(uCount, vec2(vUv.x, 1.0 - vUv.y)).a * 0.7;\n"
        + "  vec3 accC = mix(uAcc, uAcc * 0.62, uDay);\n"
        + "  vec4 c = vec4(uInk * tr, tr);\n"
        + "  c = over(c, vec4(accC * gl, gl * uDay * 0.6));\n"
        + "  c = over(c, vec4(uInk * thumb, thumb));\n"
        + "  c = over(c, vec4(accC * dt, dt));\n"
        + "  c = over(c, vec4(uInk * ta, ta));\n"
        + "  c = over(c, vec4(uInk * mag, mag));\n"
        + "  o = c * uK;\n"
        + "}";
    private static final String F_LIGHT = "uniform vec3 uCol; uniform float uK, uDay; in vec2 vUv; out vec4 o;\n"
        + "void main() {\n"
        + "  vec2 p = (vUv - 0.5) * 2.0;\n"
        + "  float e = exp(-(p.x * p.x * 2.4 + p.y * p.y * 7.0)), core = exp(-(p.x * p.x * 5.0 + p.y * p.y * 70.0));\n"
        + "  float a = (e * 0.5 + core * 0.75) * uK;\n"
        + "  o = vec4(uCol * a * mix(1.0, 0.85, uDay), a * uDay * 0.6);\n"
        + "}";

    private G.Prog pScrub, pLight;

    /** GL 环境建好（第一次、或者丢了重建）：旧的编号都作废、不去删 */
    void initGL() {
        pScrub = new G.Prog(Scene.V_UV, F_SCRUB);
        pLight = new G.Prog(Scene.V_UV, F_LIGHT);
        pad.initGL();
        atlas = 0;
        forgetSlots();
        hiOf.clear();
        lbl = null;
        lblOld.clear();
        countTex = 0;
        countText = "";
        Got x;
        while ((x = got.poll()) != null) recycle(x.mips);
        gen++;
        if (engaged()) engage(); // 开着歌单时环境丢了：图集重建，封面重新取（磁盘缓存里有）
    }

    // ================= 手势 =================
    private static final class Grab { float x, y, lx, vx, pos0, tx0, tx1; long t, lt; boolean drag, scrub, mag, pad, moved; }
    private Grab g;

    boolean touching() { return g != null; }

    /** 这一下归不归歌单管：开着、按在键盘、封面那一行（连同底下的歌名）或者刻度条上 */
    boolean claims(float ex, float ey) {
        if (!want || p < 0.6f || n == 0) return false;
        if (pad.isOpen()) return pad.hit(ex, ey) || rowHit(ey);
        return rowHit(ey) || scrubHit(ex, ey, null);
    }

    private final float[] rowO = new float[3];

    private boolean rowHit(float ey) {
        G.mul(PS, sc.P, sc.S);
        G.project(rowO, PS, 0, yR + Cf * rs * 0.5f, zC);
        float top = (0.5f - rowO[1] * 0.5f) * sc.H;
        G.project(rowO, PS, 0, yR - Cf * rs * 0.5f - sc.H * 0.1f, zC);
        float bot = (0.5f - rowO[1] * 0.5f) * sc.H;
        return ey > top - sc.H * 0.02f && ey < bot;
    }

    /** a：0 按下 / 1 移动 / 2 抬起 / 3 取消；ex、ey 画面上的位置（场景像素） */
    void touch(int a, float ex, float ey, long tms) {
        if (a == 0) {
            if (!want || p < 0.6f || vn == 0 && !pad.isOpen()) return;
            Grab q = new Grab();
            q.x = q.lx = ex; q.y = ey; q.t = q.lt = tms;
            q.pad = pad.isOpen() && pad.hit(ex, ey);
            if (q.pad) { pad.down(ex, ey); g = q; return; }
            tw = false;
            posS.set(pos); // 按住就停（同 iOS）
            q.pos0 = pos;
            q.scrub = !pad.isOpen() && scrubHit(ex, ey, q);
            q.mag = q.scrub && scrubLocal(ex, q) < sX0 - sc.pillLh * 0.15f;
            if (q.mag) magPress.t = 0.82f; else if (q.scrub) sPress.t = 1.25f;
            g = q;
            return;
        }
        Grab q = g;
        if (q == null) return;
        boolean cancel = a == 3;
        if (q.pad) {
            if (a >= 2) { char c = pad.up(ex, ey, cancel); if (c != 0) key(c, sc.clock); g = null; }
            return;
        }
        if (a == 1) {
            float dx = ex - q.x, dtm = Math.max(1, tms - q.lt);
            q.vx = q.vx * 0.4f + (ex - q.lx) / dtm * 0.6f;
            q.lx = ex; q.lt = tms;
            if (!q.moved && (Math.abs(dx) > 8 || Math.abs(ey - q.y) > 8)) { q.moved = true; magPress.t = 1; }
            if (q.mag || vn == 0) return;
            if (q.scrub && q.moved) { q.drag = true; posS.set(scrubAt(ex, q) * Math.max(0, vn - 1)); }
            else if (q.moved) {
                q.drag = true;
                float raw = q.pos0 - dx / itemPx, hi = vn - 1;
                posS.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw); // 两头拉过了有阻力
            }
            return;
        }
        if (!q.moved && !cancel && tms - q.t < 500) tap(ex, ey, q);
        else if (q.scrub && vn > 0) posS.t = clamp(Math.round(posS.x), 0, vn - 1);
        else if (q.drag) {
            float v = cancel ? 0 : -q.vx * 1000 / itemPx; // 首 / 秒
            float target = clamp(Math.round(posS.x + v * 0.22f), 0, vn - 1); // 按速度算出停在第几首
            posS.v = v;
            posS.t = target;
        }
        if (q.scrub) sPress.t = 1;
        if (q.mag) { magPress.t = 1; magPress.v += 3; }
        g = null;
    }

    private void tap(float ex, float ey, Grab q) {
        if (q.mag) { openSearch(); return; }
        if (q.scrub) {
            if (vn == 0) return;
            float fr = scrubAt(ex, q), pk = vk(playing), pf = pk >= 0 && vn > 1 ? pk / (vn - 1) : -1;
            if (pf >= 0 && Math.abs(fr - pf) * Math.abs(q.tx1 - q.tx0) < sc.pillLh * sc.pillF * 0.6f) tweenTo((int) pk); // 点到正在放的那个点
            else tweenTo(Math.round(fr * (vn - 1)));
            return;
        }
        Brick b = brickAt(ex, ey);
        if (b == null) return;
        int i = b.i;
        boolean searching = pad.isOpen();
        if (i == playing) { // 点正在放的：搜着时回到整个歌单停在它上面，不然回歌词（从它那里飞回去）
            if (searching) { tweenTo(b.k); closeSearch(sc.clock); }
            else { closeFrom = i; setWant(false); }
            return;
        }
        sc.host.playIndex(i);
        pendPlay = i;
        pendAt = sc.clock;
        tweenTo(b.k);
        if (searching) closeSearch(sc.clock);
    }

    private final float[] PS = new float[16], PM = new float[16], c4 = new float[8];

    /** 点在哪块砖上（先看近的；按砖的正面投到画面上的四边形算；缩下去的不算） */
    private Brick brickAt(float ex, float ey) {
        G.mul(PS, sc.P, sc.S);
        float[] o = new float[3];
        for (int k = 0; k < nb; k++) {
            Brick b = bricks[k];
            if (b.k < 0) continue;
            G.mul(PM, PS, b.m);
            float h = 0.5f;
            float[][] cs = { { -h, -h }, { h, -h }, { h, h }, { -h, h } };
            for (int c = 0; c < 4; c++) { G.project(o, PM, cs[c][0], cs[c][1], 0.08f); c4[c * 2] = (o[0] * 0.5f + 0.5f) * sc.W; c4[c * 2 + 1] = (0.5f - o[1] * 0.5f) * sc.H; }
            if (inQuad(ex, ey)) return b;
        }
        return null;
    }

    private boolean inQuad(float x, float y) {
        int pos = 0, neg = 0;
        for (int c = 0; c < 4; c++) {
            float ax = c4[c * 2], ay = c4[c * 2 + 1], bx = c4[((c + 1) % 4) * 2], by = c4[((c + 1) % 4) * 2 + 1];
            float cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            if (cr > 0) pos++; else if (cr < 0) neg++;
        }
        return pos == 0 || neg == 0;
    }

    /** 按在刻度条（控制条那块玻璃）上没有；q 不是 null 时记下线两头在画面上的位置 */
    private boolean scrubHit(float ex, float ey, Grab q) {
        if (sK < 0.5f || pad.k.x > 0.3f) return false;
        G.mul(PS, sc.P, sc.S);
        G.mul(PM, PS, sc.pillM);
        if (q != null) {
            G.project(rowO, PM, sX0, 0, sc.iconsZ); q.tx0 = (rowO[0] * 0.5f + 0.5f) * sc.W;
            G.project(rowO, PM, sX1, 0, sc.iconsZ); q.tx1 = (rowO[0] * 0.5f + 0.5f) * sc.W;
        }
        G.project(rowO, PM, 0, 0, 0);
        float cx = (rowO[0] * 0.5f + 0.5f) * sc.W, cy = (0.5f - rowO[1] * 0.5f) * sc.H;
        return Math.abs(ex - cx) < sc.pillLw * sc.pillF * 0.5f && Math.abs(ey - cy) < sc.pillLh * sc.pillF * 0.75f;
    }

    /** 手指在刻度条上的位置换成控制条自己的 x */
    private float scrubLocal(float ex, Grab q) { return sX0 + (ex - q.tx0) / (Math.abs(q.tx1 - q.tx0) < 1 ? 1 : q.tx1 - q.tx0) * (sX1 - sX0); }

    private static float scrubAt(float ex, Grab q) { return clamp((ex - q.tx0) / Math.max(1, q.tx1 - q.tx0), 0, 1); }
}
