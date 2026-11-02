package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/**
 * 右上角的设置键、全屏键：场景里的两颗玻璃圆键（同曲库的返回键：透出后面的极光、跟着场景轻轻摆），出来时从小弹大，按下去缩一点、松手弹回。
 * 什么时候显示、全屏键是"全屏"还是"恢复小屏"由主线程（Ui）定，交进来；点了交给 host.press("settings" / "fullscreen")。
 * 位置同原来的安卓按键（1rem = 高度 / 10.52）：横屏全屏键在设置键左边，竖屏在它下面。全在 GL 线程里
 */
final class TopKeys {
    private final Scene sc;
    private static final float Z = 40;
    private volatile boolean want;
    private volatile String fsMode = "";

    TopKeys(Scene sc) { this.sc = sc; }

    /** 主线程：显不显示、全屏键（full 全屏 / small 恢复小屏 / 空的没有） */
    void set(boolean show, String fs) { want = show; fsMode = fs; sc.wake(); }

    private float fz = 1, r = 1, xMax = 1, capZ;
    private final float[] cx = new float[2], cy = new float[2]; // 0 设置、1 全屏：中心（世界坐标，立在 z = Z）
    private final Spring vis = new Spring(0, 170, 18, 0.002f);
    private final Spring[] press = { new Spring(1, 300, 18), new Spring(1, 300, 18) };
    private final float[][] m = { new float[16], new float[16] }, im = { new float[16], new float[16] };
    private final float[] tmp = new float[16];
    private G.Mesh mesh;
    private int gear, full, small;
    private int down = -1;

    void layout() {
        float W = sc.W, H = sc.H, rem = H / 10.52f, k = rem * 0.62f;
        boolean nar = W / H < 0.8f;
        fz = sc.dpx / (sc.dpx - Z);
        xMax = W / 2 / fz;
        r = k / 2 / fz;
        float gx = W - rem * 0.26f - k / 2, gy = rem * 0.24f + k / 2;
        float fx = nar ? gx : W - rem * 1.02f - k / 2, fy = nar ? rem * 1.0f + k / 2 : gy;
        cx[0] = (gx - W / 2) / fz; cy[0] = (H / 2 - gy) / fz;
        cx[1] = (fx - W / 2) / fz; cy[1] = (H / 2 - fy) / fz;
        if (mesh != null) { mesh.free(); mesh = null; }
    }

    private boolean has(int i) { return i == 0 || !fsMode.isEmpty(); }

    boolean tick(float dt) {
        boolean moving = false;
        vis.t = want && sc.pageQ() < 0.15f ? 1 : 0; // 曲库、设置页关的时候等它差不多退完了再出来
        if (vis.step(dt)) moving = true;
        for (Spring p : press) if (p.step(dt)) moving = true;
        if (vis.x <= 0.002f) return moving;
        if (mesh == null) { float d = r * 2, bv = d * 0.12f; mesh = G.extrude(G.stadium(d - bv * 2, d - bv * 2, 16), d * 0.18f, bv * 0.8f, bv, 3, 0); capZ = bv * 0.8f + 0.5f; }
        if (gear == 0) gear = glyph(R.drawable.ic_gear);
        if (full == 0) full = glyph(R.drawable.ic_full);
        if (small == 0) small = glyph(R.drawable.ic_small);
        for (int i = 0; i < 2; i++) {
            float s = Math.max(0, vis.x) * press[i].x;
            G.compose(m[i], cx[i], cy[i], Z, 0.04f, -cx[i] / xMax * 0.25f, 0, s, s, s);
            G.compose(tmp, 0, 0, capZ, 0, 0, 0, r * 1.1f, r * 1.1f, 1);
            G.mul(im[i], m[i], tmp);
        }
        return moving;
    }

    /** 安卓的矢量图标画成白色贴图（pText 按 uInk 上色） */
    private int glyph(int id) {
        int n = 96;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Drawable d = sc.view.view().getContext().getDrawable(id);
        if (d != null) {
            d = d.mutate();
            d.setTint(Color.WHITE);
            d.setBounds(0, 0, n, n);
            d.draw(new Canvas(b));
        }
        int t = G.texture(b, true);
        b.recycle();
        return t;
    }

    void drawSolid(float[] V, float[] Pp) {
        if (vis.x <= 0.002f || mesh == null) return;
        G.Prog pp = sc.pPill;
        pp.use();
        pp.m4("uP", Pp);
        pp.tex("uAur", 0, sc.aurFbo.tex);
        pp.f2("uHalf", r, r); pp.f("uDay", sc.day);
        pp.c3("uTint", sc.pal.c[0]);
        for (int i = 0; i < 2; i++) {
            if (!has(i)) continue;
            sc.mv(V, m[i]);
            pp.m4("uMV", sc.MV);
            G.mul(SM, sc.P, sc.M1); pp.m4("uSM", SM);
            mesh.draw();
        }
    }

    private final float[] SM = new float[16];

    void drawText(float[] V, float[] Pp) {
        if (vis.x <= 0.002f || gear == 0) return;
        G.depthTest(false);
        G.Prog pt = sc.pText;
        pt.use();
        pt.m4("uP", Pp); pt.c3("uInk", sc.ink); pt.f("uK", 1);
        for (int i = 0; i < 2; i++) {
            if (!has(i)) continue;
            sc.mv(V, im[i]);
            pt.m4("uMV", sc.MV); pt.tex("uMap", 0, i == 0 ? gear : "small".equals(fsMode) ? small : full); pt.f("uA", Math.min(1, vis.x) * 0.9f);
            sc.mUnit.draw();
        }
        G.depthTest(true);
    }

    // ================= 手势 =================
    private final float[] PS = new float[16], PM = new float[16], o3 = new float[3];

    private int hit(float ex, float ey) {
        if (vis.x < 0.5f || mesh == null) return -1;
        G.mul(PS, sc.P, sc.S);
        for (int i = 0; i < 2; i++) {
            if (!has(i)) continue;
            G.mul(PM, PS, m[i]);
            G.project(o3, PM, 0, 0, 0);
            float dx = (o3[0] * 0.5f + 0.5f) * sc.W - ex, dy = (0.5f - o3[1] * 0.5f) * sc.H - ey, rr = r * fz * 1.2f;
            if (dx * dx + dy * dy < rr * rr) return i;
        }
        return -1;
    }

    /** a：0 按下 / 1 移动 / 2 抬起 / 3 取消；按在键上（或者按下时在键上）返回真：这次手势归键 */
    boolean touch(int a, float ex, float ey) {
        if (a == 0) {
            down = hit(ex, ey);
            if (down < 0) return false;
            press[down].t = 0.86f;
            sc.wake();
            return true;
        }
        if (down < 0) return false;
        int k = down;
        if (a >= 2) {
            down = -1;
            press[k].t = 1;
            press[k].v += 3;
            if (a == 2 && hit(ex, ey) == k) sc.host.press(k == 0 ? "settings" : "fullscreen");
        }
        sc.wake();
        return true;
    }

    /** GL 环境建好（第一次、或者丢了重建）：旧的编号都作废、不去删 */
    void initGL() { mesh = null; gear = full = small = 0; }
}
