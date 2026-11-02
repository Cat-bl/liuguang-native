package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页（3D，同曲库的"分层唱片架"）：左上角一颗返回键、旁边"设置"两个字（同点空白、返回键：关上），下面一排一组（画面 / 场景 / 动画 / 其他），
 * 每项一块立在水面上的玻璃砖：正面写名字、现在的值（大字），底下一排是全部选项（现在这个包在小胶囊里）；每个值一个颜色，关着的是灰的。
 * 点一块：转半圈翻到背面的下一个值、外圈亮一下，交给主线程改设置（Host.prefSet）；值的说明写在标题下面那一行（点了哪块写哪块的）。
 * 一组一排放不下就折到下一排（窄窗口一排两块、宽的四块），只上下滑、不左右滑；左边的分类索引（按组亮，点了滚到那组的第一排）、开关的进度 q，都同曲库（Library）。
 * 主线程交进来现在的设置（Data：别处改了也跟着翻过去）；抗锯齿、渲染方式改了要换画布，新的场景直接开着设置页（request(2)）。
 * 砖的图（256 见方）用到时在 GL 线程里画，一帧最多两块；关完了全部释放。全在 GL 线程里
 */
final class SetPage {
    private final Scene sc;

    SetPage(Scene sc) { this.sc = sc; }

    // ================= 主线程交进来的 =================
    static final class Data {
        final Settings st;
        final boolean fsBar, render; // 有没有"全屏时收起状态栏"（能全屏时才有）、"渲染方式"（车机有 Vulkan 时才有）
        Data(Settings st, boolean fsBar, boolean render) { this.st = st; this.fsBar = fsBar; this.render = render; }
    }

    private volatile Data in;
    private volatile int req; // 1 打开、2 直接开着（换了画布：不放动画）、3 关上

    void setData(Data d) { in = d; }
    void request(int r) { req = r; }

    // ================= 每一项：键、名字、选项（值、显示的字、砖的色相：-1 是关着的灰）、说明 =================
    private static Object[][] on(float hue) { return new Object[][] { { true, "开", hue }, { false, "关", -1f } }; }

    private static final Object[][] DEFS = {
        { "theme", "深浅色", new Object[][] { { "auto", "跟随车机", 190f }, { "light", "浅色", 40f }, { "dark", "深色", 250f } }, null },
        { "aa", "抗锯齿", on(200), "边缘更平滑，更费显卡" },
        { "clarity", "画面清晰度", new Object[][] { { "auto", "自动", 120f }, { "sharp", "清晰优先", 45f } }, "自动：显卡跟不上时把画面缩小一点，更流畅；清晰优先：一直按原尺寸画，字更清楚，帧率会低一些" },
        { "render", "渲染方式", new Object[][] { { "vulkan", "Vulkan", 345f }, { "gl", "OpenGL", 210f } }, "Vulkan 更省处理器；画面不对时换成 OpenGL" },
        { "fps", "帧率", new Object[][] { { "auto", "自动", 165f }, { "60", "60 帧", 275f }, { "30", "30 帧", 215f } }, "自动：画不过来降到 30 帧" },
        { "water", "水面倒影", on(195), null },
        { "art", "背景封面", on(320), null },
        { "ribbons", "光带", on(285), null },
        { "rain", "玻璃上的雨珠", new Object[][] { { "auto", "自动", 205f }, { "on", "开", 225f }, { "off", "关", -1f } }, "水珠挂在屏幕上，偶尔滑落。自动：跟着实时天气，并按歌词动态判断" },
        { "intro", "开场动画", on(30), null },
        { "reveal", "换歌动画", on(50), "光点从封面流出聚成歌词" },
        { "font", "歌词字体", new Object[][] { { "smiley", "得意黑", 340f }, { "system", "系统字体", 220f } }, null },
        { "fsBar", "全屏时收起状态栏", on(150), null },
    };
    private static final Object[][] GROUPS = {
        { "画面", new String[] { "theme", "aa", "clarity", "render", "fps" } },
        { "场景", new String[] { "water", "art", "ribbons", "rain" } },
        { "动画", new String[] { "intro", "reveal" } },
        { "其他", new String[] { "font", "fsBar" } },
    };
    private static final String HINT = "点一下，换成下一个";

    private static final class Item {
        final String key, name, desc;
        final Object[][] opts;
        Object shown, target, backVal; // 正面的值、要的值、背面的值
        int tex, back;                 // 正面、背面的图
        float at, localAt = -99, pulse;
        boolean stale;                 // 字体换了：正面重画
        final Spring flip = new Spring(0, 150, 18, 0.0005f), press = new Spring(1, 300, 18);
        Item(Object[] d) { key = (String) d[0]; name = (String) d[1]; opts = (Object[][]) d[2]; desc = (String) d[3]; }
        int idx(Object v) { for (int i = 0; i < opts.length; i++) if (opts[i][0].equals(v)) return i; return 0; }
        boolean flipping() { return flip.x != 0 || flip.t != 0; }
    }

    /** 一组：索引里的名字、组里的项 */
    private static final class Row {
        final String title;
        final String[] keys;
        List<Item> items = new ArrayList<>();
        int lt;           // 索引里的名字
        G.Mesh ml;
        float tw, tpad;
        Row(String t, String[] k) { title = t; keys = k; }
    }

    /** 画面上的一排：一组放不下时折成几排 */
    private static final class Line { int g; final List<Item> items = new ArrayList<>(); float y, vis; }

    private final Map<String, Item> items = new HashMap<>();
    private final Row[] rows = new Row[GROUPS.length];
    private final List<Line> lines = new ArrayList<>();
    private final int[] first = new int[GROUPS.length]; // 每组的第一排
    private int per = 4;          // 一排几块
    private boolean linesDirty = true;

    {
        for (Object[] d : DEFS) items.put((String) d[0], new Item(d));
        for (int i = 0; i < GROUPS.length; i++) rows[i] = new Row((String) GROUPS[i][0], (String[]) GROUPS[i][1]);
    }

    // ================= 状态 =================
    float q;               // 0 关、1 开
    private float qv;
    private boolean want;
    private static final float DUR = 1.15f;

    boolean engaged() { return want || q > 0; }
    boolean opening() { return want; }
    /** 歌词页 / 长廊让开多少（同曲库） */
    float coverK() { return q <= 0 ? 0 : smooth(0, 0.35f, q); }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static float smooth(float a, float b, float v) { float t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
    private static float easeOut(float t) { float u = 1 - t; return 1 - u * u * u; }

    // ================= 布局（世界坐标：立在 z = Z 处；1 个单位在画面上是 fz 个场景像素；同曲库） =================
    private static final float Z = 40;
    private float fz = 1, T = 1, pitch = 1, rowGap = 1, xL, rowsTop, rowsBot, titleH, topY, sh = 1, xMax = 1, backX, titleX, titleF = 1;
    private float ils = 1, iStep = 1, ixBar, ixText, iy, xEdge, hintY, hintF = 1;
    private int rowsFit = 2;
    private String meshKey = "";
    private G.Mesh mBack;
    private float capZ;
    private int backTex, white;
    private final float[] backM = new float[16], backIm = new float[16];

    void layout() {
        float W = sc.W, H = sc.H;
        boolean nar = sc.narrow;
        fz = sc.dpx / (sc.dpx - Z);
        xMax = W / 2 / fz;
        topY = (H / 2 - H * (nar ? 0.075f : 0.085f)) / fz;
        sh = H * (nar ? 0.058f : 0.068f) / fz;
        ils = H * (nar ? 0.027f : 0.03f);
        hintF = ils * 0.78f;
        hintY = topY - sh * 0.5f - H * 0.04f / fz;
        rowsTop = topY - sh * 0.5f - H * (nar ? 0.095f : 0.065f) / fz; // 窄窗口说明可能折成两行
        rowsBot = (sc.water + H * 0.015f) / fz;
        titleH = H * 0.012f / fz;
        iStep = ils * 2.3f / fz;
        ixBar = (-W / 2 + W * (nar ? 0.04f : 0.035f)) / fz;
        ixText = ixBar + ils * 0.75f / fz;
        xEdge = ixText + ils * 2.6f / fz;
        iy = (rowsTop + rowsBot) / 2;
        // 一排正好放下整数块（窄窗口两块、宽的四块；砖排从 xEdge 起、右边留一点），再看水面以上放得下几整排（同曲库）
        float area = rowsTop - rowsBot, Tw = (xMax - xEdge) / (1.16f * (nar ? 2 : 4) + 0.02f);
        rowsFit = 2;
        for (int k = 4; k > 2; k--) if (area / k * 0.9f - titleH >= Tw * 0.85f) { rowsFit = k; break; }
        rowGap = area / rowsFit;
        T = Math.min(rowGap * 0.9f - titleH, Tw);
        per = Math.max(1, (int) (((xMax - xEdge) / T - 0.02f) / 1.16f)); // 砖被高度限得小了：一排多放几块
        linesDirty = true;
        pitch = T * 1.16f;
        xL = xEdge + T * 0.62f;
        backX = ixBar + sh * 0.5f - ils * 0.3f / fz;
        titleX = backX + sh * 0.5f + sh * 0.32f;
        titleF = sh * fz * 0.66f;
        String key = Math.round(sh * 10) + "";
        if (!key.equals(meshKey) && mBack != null) { mBack.free(); mBack = null; }
        meshKey = key;
        freeLabels();
    }

    private float rowY(int r, float rp) { return rowsTop - (r - rp + 0.5f) * rowGap; }
    private float maxRow() { return Math.max(0, lines.size() - rowsFit); }

    /** 第 x 排（可以是小数）在第几组：索引的竖条、亮哪几个名字按它 */
    private float gAt(float x) {
        int n = lines.size();
        if (n == 0) return 0;
        x = clamp(x, 0, n - 1);
        int i0 = (int) x, i1 = Math.min(i0 + 1, n - 1);
        return lines.get(i0).g + (lines.get(i1).g - lines.get(i0).g) * (x - i0);
    }

    private void buildLines() {
        linesDirty = false;
        lines.clear();
        for (int gi = 0; gi < rows.length; gi++) {
            first[gi] = lines.size();
            List<Item> l = rows[gi].items;
            for (int i = 0; i < l.size(); i += per) {
                Line ln = new Line();
                ln.g = gi;
                ln.items.addAll(l.subList(i, Math.min(l.size(), i + per)));
                lines.add(ln);
            }
        }
        float mx = maxRow();
        if (rowS.x > mx) rowS.set(mx); else if (rowS.t > mx) rowS.t = mx;
    }
    private float idxY(float i) { return iy + ((rows.length - 1) * 0.5f - i) * iStep; }

    // ================= 开 / 关 =================
    private final Spring rowS = new Spring(0, 49, 14, 0.0004f);

    private void open(boolean now) {
        if (want) return;
        if (q == 0) engage();
        want = true;
        if (now) { q = 1; qv = 0; lockUntil = sc.clock + 1.5f; } // 换了画布直接开着：头 1.5 秒（同主线程）不再换
        sc.host.prefsState(true);
    }

    private void close() {
        if (!want) return;
        want = false;
        float top = Math.max(0.8f, 0.7f + 0.09f * (rowsFit - 1)); // 看得见的每一排都到位时的 q：关的时候从这里退，不空等
        if (q > top) q = top;
        sc.host.prefsState(false);
    }

    // ================= 每帧 =================
    /** 先走：开关、进度 q（场景其它东西要用 coverK） */
    boolean tickQ(float dt) {
        int r = req;
        if (r != 0) {
            req = 0;
            if (r == 1 || r == 2) open(r == 2);
            else close();
        }
        float target = want ? 1 : 0;
        if (q == target && qv == 0) return false;
        float dir = Math.signum(target - q);
        qv += (dir / DUR - qv) * (1 - (float) Math.exp(-dt / 0.07));
        q += qv * dt;
        if (q >= 1) { q = 1; if (qv > 0) qv = 0; }
        if (q <= 0) { q = 0; if (qv < 0) qv = 0; }
        if (q == target && Math.abs(qv) < 0.02f) { qv = 0; if (q == 0) release(); }
        return true;
    }

    private Data data;
    private String availSeen = "";
    private float lockUntil; // 这之前抗锯齿、渲染方式两块点了不算：它们改了要换画布，连点会接连重建
    private Typeface faceSeen;
    private int made; // 这一帧画了几块砖的图

    boolean tick(float dt, float time) {
        if (!engaged()) return false;
        boolean moving = q < 1 || want != (q == 1);
        made = 0;
        Typeface f = Txt.face();
        if (f != faceSeen) { // 歌词字体换了：砖上的字、标题、索引跟着换
            faceSeen = f;
            for (Item it : items.values()) if (it.tex != 0) it.stale = true;
            freeLabels();
        }
        takeData(time);
        syncTargets(time);
        if (linesDirty) buildLines();
        if (!(g != null && g.axis == 2) && rowS.step(dt)) moving = true;
        if (flips(dt, time)) moving = true;
        if (layoutAll(dt, time)) moving = true;
        if (hintTick(dt)) moving = true;
        return moving;
    }

    /** 现在的设置：第一次直接用，之后变了的翻过去（刚在这里点过的等主线程跟上，不被晚到的旧值翻回去） */
    private void takeData(float time) {
        Data d = in;
        if (d == null || d == data) return;
        data = d;
        String av = d.fsBar + "|" + d.render;
        if (!av.equals(availSeen)) {
            availSeen = av;
            for (Row r : rows) {
                List<Item> l = new ArrayList<>();
                for (String k : r.keys) {
                    if ("fsBar".equals(k) && !d.fsBar || "render".equals(k) && !d.render) continue;
                    l.add(items.get(k));
                }
                r.items = l;
            }
            linesDirty = true;
        }
        for (Item it : items.values()) {
            Object v = d.st.get(it.key);
            if (v != null && it.target == null) it.target = it.shown = v;
        }
    }

    /** 每帧：要的值跟上主线程的设置（刚在这里点过的等 1.5 秒，不被晚到的旧值翻回去；主线程没改成的过了这段翻回真正的值） */
    private void syncTargets(float time) {
        Data d = data;
        if (d == null) return;
        for (Item it : items.values()) {
            if (it.target == null || time - it.localAt <= 1.5f) continue;
            Object v = d.st.get(it.key);
            if (v != null && !v.equals(it.target)) it.target = v;
        }
    }

    /** 翻面：要的值和正面的不一样就把它画到背面、转半圈；转到了背面当正面、转回 0（看起来一样） */
    private boolean flips(float dt, float time) {
        boolean moving = false;
        for (Item it : items.values()) {
            if (it.pulse > 0) { it.pulse = Math.max(0, it.pulse - dt * 1.6f); moving = true; }
            if (it.press.step(dt)) moving = true;
            if (it.target == null) continue;
            if (it.tex == 0) { if (!it.flipping()) it.shown = it.target; continue; } // 还没画过：用到时直接画成要的值
            if (!it.flipping()) {
                if (it.target.equals(it.shown)) continue;
                if (made >= 2) { moving = true; continue; }
                setBack(it, it.target);
                it.flip.t = (float) Math.PI;
            } else if (it.flip.x < Math.PI / 2 && !it.target.equals(it.backVal) && made < 2) setBack(it, it.target); // 没翻到一半又点了一下：背面换成新的
            if (it.flip.step(dt)) moving = true;
            if (it.flip.t != 0 && Math.abs(it.flip.x - it.flip.t) < 0.02f && Math.abs(it.flip.v) < 0.5f) {
                G.free(it.tex);
                it.tex = it.back;
                it.back = 0;
                it.shown = it.backVal;
                it.flip.set(0);
                moving = true;
            }
        }
        return moving;
    }

    private void setBack(Item it, Object v) {
        made++;
        if (it.back != 0) G.free(it.back);
        Bitmap b = paint(it, v);
        it.back = G.texture(b, true);
        b.recycle();
        it.backVal = v;
    }

    /** 正面的图：还没有、或者字体换了要重画；这一帧画不了返回真（要接着画） */
    private boolean ensureTex(Item it, float time) {
        if (it.tex != 0 && (!it.stale || it.flipping())) return false;
        if (made >= 2) return true;
        made++;
        boolean fresh = it.tex == 0;
        if (!fresh) G.free(it.tex);
        Bitmap b = paint(it, it.shown);
        it.tex = G.texture(b, true);
        b.recycle();
        if (fresh) it.at = time;
        it.stale = false;
        return true;
    }

    // ================= 摆 =================
    private static final class Tile { Item it; float halo, dim; final float[] m = new float[16]; }
    private final Tile[] tiles = new Tile[16];
    { for (int i = 0; i < tiles.length; i++) tiles[i] = new Tile(); }
    private int nt;
    private float kS, kI; // 顶上那行（返回键、标题、说明）、索引显示多少
    private final float[] tmp = new float[16];

    private boolean layoutAll(float dt, float time) {
        boolean moving = false;
        nt = 0;
        float rp = rowS.x;
        // ---- 返回键（从上面落下来） ----
        kS = easeOut(smooth(0.08f, 0.6f, q));
        if (kS > 0.002f) {
            if (mBack == null) { float bv = sh * 0.12f; mBack = G.extrude(G.stadium(sh - bv * 2, sh - bv * 2, 16), sh * 0.18f, bv * 0.8f, bv, 3, 0); capZ = bv * 0.8f + 0.5f; }
            if (backTex == 0) backTex = glyph();
            G.compose(backM, backX, topY + (1 - kS) * sh * 3, Z, 0.04f, -backX / xMax * 0.25f, 0, 1, 1, 1);
            G.compose(tmp, 0, 0, capZ, 0, 0, 0, sh * 0.5f, sh * 0.5f, 1);
            G.mul(backIm, backM, tmp);
            if (ensureTitle()) moving = true;
        }
        // ---- 一排排砖 ----
        for (int r = 0; r < lines.size(); r++) {
            Line row = lines.get(r);
            float y = rowY(r, rp);
            float vis = 1 - smooth(0, rowGap * 0.5f, y + T / 2 + titleH - rowsTop) - smooth(0, rowGap * 0.5f, rowsBot - (y - T / 2));
            int vr = (int) Math.max(0, r - Math.floor(rp));
            float a = 0.2f + 0.09f * vr, rk = easeOut(smooth(a, a + 0.5f, q));
            row.y = y - (1 - rk) * rowGap * 0.9f;
            row.vis = clamp(vis, 0, 1) * rk;
            if (row.vis <= 0.03f) continue;
            float rs = 0.5f + 0.5f * clamp(vis, 0, 1);
            for (int i = 0; i < row.items.size(); i++) {
                float x = xL + i * pitch;
                if (nt >= tiles.length) break;
                Item it = row.items.get(i);
                float u = clamp(x / xMax, -1.2f, 1.2f), ti = easeOut(smooth(a + 0.02f * i, a + 0.5f, q));
                if (ti <= 0.002f) continue;
                Tile t = tiles[nt++];
                t.it = it;
                float s = T * it.press.x * (0.75f + 0.25f * ti) * smooth(0, 0.45f, ti) * rs, fl = it.flip.x; // 进出时边沉边缩到没有（砖不透明，淡不掉）
                float yy = row.y - (1 - ti) * rowGap * 0.4f;
                G.compose(t.m, x, yy, Z - u * u * T * 0.35f + (float) Math.sin(fl) * T * 0.3f, 0.03f, -u * 0.32f + fl, 0, s, s, s); // 翻的时候往前浮一点
                t.dim = clamp(vis, 0, 1); // 只按滚出去多少压暗，进出时不压黑
                t.halo = it.pulse;
                if (ensureTex(it, time)) moving = true;
                if (it.tex != 0 && time - it.at < 0.3f) moving = true;
            }
        }
        // ---- 索引（从左边滑进来） ----
        kI = easeOut(smooth(0.3f, 0.8f, q));
        if (kI > 0.002f) {
            if (white == 0) { Bitmap b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); white = G.texture(b, false); b.recycle(); }
            for (Row r : rows) if (ensureLabel(r)) moving = true;
        }
        return moving;
    }

    // ================= 砖的图 =================
    private static final int ART = 256;

    /** 一块砖的图：渐变底（这个值的颜色，关着是灰的）、上面名字、中间值（大字）、底下一排全部选项 */
    private Bitmap paint(Item it, Object v) {
        int n = ART, i = it.idx(v);
        Object[] o = it.opts[i];
        float hue = (Float) o[2];
        boolean off = hue < 0;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int c1 = off ? 0xff5c6270 : Color.HSVToColor(new float[] { hue, 0.55f, 0.92f }), c2 = off ? 0xff1f2229 : Color.HSVToColor(new float[] { (hue + 40) % 360, 0.7f, 0.5f });
        p.setShader(new LinearGradient(0, 0, n, n, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        p.setShader(new LinearGradient(0, 0, 0, n, 0x30ffffff, 0x00ffffff, Shader.TileMode.CLAMP)); // 上面亮一点（像打了光）
        c.drawRect(0, 0, n, n * 0.5f, p);
        p.setShader(null);
        text(c, p, it.name, n * 0.5f, n * 0.2f, n * 0.105f, Typeface.DEFAULT_BOLD, off ? 0xa6ffffff : 0xd9ffffff, n * 0.86f);
        String lbl = (String) o[1];
        Typeface tf = "font".equals(it.key) ? Txt.load(sc.view.view().getContext(), (String) v) : Txt.face(); // 歌词字体那块：值用它自己的字体写
        text(c, p, lbl, n * 0.5f, n * 0.56f, n * (lbl.length() <= 2 ? 0.3f : 0.22f), tf, off ? 0xb3ffffff : 0xffffffff, n * 0.86f);
        options(c, p, it, i, n, c2);
        return b;
    }

    /** 底下一排：全部选项的名字，现在这个包在一颗白色小胶囊里（字用这块的深色）；放不下整排缩小 */
    private void options(Canvas c, Paint p, Item it, int cur, int n, int dark) {
        int m = it.opts.length;
        Typeface[] tf = new Typeface[m];
        float[] w = new float[m];
        float fs = n * 0.072f, total = 0;
        p.setTextAlign(Paint.Align.LEFT);
        for (int k = 0; k < m; k++) {
            tf[k] = "font".equals(it.key) ? Txt.load(sc.view.view().getContext(), (String) it.opts[k][0]) : Typeface.DEFAULT_BOLD;
            p.setTypeface(tf[k]);
            p.setTextSize(fs);
            w[k] = p.measureText((String) it.opts[k][1]);
            total += w[k];
        }
        float gap = fs * 1.1f, padX = fs * 0.5f, max = n * 0.88f - padX * 2;
        total += gap * (m - 1);
        float sc0 = total > max ? max / total : 1;
        fs *= sc0; gap *= sc0; padX *= sc0; total *= sc0;
        float x = n * 0.5f - total / 2, y = n * 0.845f;
        for (int k = 0; k < m; k++) {
            float wk = w[k] * sc0;
            p.setTypeface(tf[k]);
            p.setTextSize(fs);
            Paint.FontMetrics fm = p.getFontMetrics();
            if (k == cur) {
                p.setColor(0xebffffff);
                c.drawRoundRect(x - padX, y - fs * 0.78f, x + wk + padX, y + fs * 0.78f, fs, fs, p);
            }
            p.setColor(k == cur ? dark : 0x99ffffff);
            c.drawText((String) it.opts[k][1], x, y - (fm.ascent + fm.descent) / 2, p);
            x += wk + gap;
        }
    }

    /** 居中写一行字（放不下缩小），带一点投影 */
    private static void text(Canvas c, Paint p, String t, float x, float y, float size, Typeface tf, int col, float max) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        float w = p.measureText(t);
        if (w > max) { size *= max / w; p.setTextSize(size); }
        p.setColor(col);
        p.setShadowLayer(size * 0.2f, 0, size * 0.06f, 0x66000000);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, x, y - (fm.ascent + fm.descent) / 2, p);
        p.clearShadowLayer();
    }

    /** 返回键上的"‹"：白色，pText 按 uInk 上色 */
    private static int glyph() {
        int n = 64;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(n * 0.11f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path a = new android.graphics.Path();
        a.moveTo(n * 0.6f, n * 0.24f);
        a.lineTo(n * 0.36f, n * 0.5f);
        a.lineTo(n * 0.6f, n * 0.76f);
        c.drawPath(a, p);
        int t = G.texture(b, true);
        b.recycle();
        return t;
    }

    // ================= 字：标题、说明、索引 =================
    private int titleTex;
    private G.Mesh titleMesh;
    private float titleW, titlePad;

    private boolean ensureTitle() {
        if (titleTex != 0) return false;
        Txt.Label l = Txt.label("设置", titleF, sc.W * 0.4f, true);
        titleTex = G.texture(l.bmp, false);
        l.bmp.recycle();
        titleMesh = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
        titleW = l.w; titlePad = l.pad;
        return true;
    }

    /** 说明：要写的（hintWant）和贴图里的（hintNow）不一样时先淡出、换了再淡入；放不下折成两行 */
    private String hintWant = HINT, hintNow;
    private float hintA;
    private final int[] hintTex = new int[2];
    private final G.Mesh[] hintMesh = new G.Mesh[2];
    private final float[] hintW = new float[2], hintPad = new float[2];

    private boolean hintTick(float dt) {
        if (kS <= 0.002f) return false;
        boolean same = hintWant.equals(hintNow);
        if (!same && (hintNow == null || hintA < 0.02f)) { makeHint(hintWant); same = true; }
        float goal = same ? 1 : 0;
        hintA += (goal - hintA) * (1 - (float) Math.exp(-dt / (same ? 0.12f : 0.06f)));
        if (Math.abs(goal - hintA) < 0.01f) { hintA = goal; return false; }
        return true;
    }

    private void makeHint(String text) {
        freeHint();
        hintNow = text;
        float max = -ixBar * 2 * fz; // 左右各留出索引那么宽
        String[] ls = split(text, hintF, max);
        for (int k = 0; k < ls.length && k < 2; k++) {
            Txt.Label l = Txt.label(ls[k], hintF, max, false);
            hintTex[k] = G.texture(l.bmp, false);
            l.bmp.recycle();
            hintMesh[k] = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
            hintW[k] = l.w; hintPad[k] = l.pad;
        }
    }

    /** 一行放不下：在离中间最近的标点后面折成两行 */
    private static String[] split(String t, float Fpx, float maxPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Txt.face(), Typeface.NORMAL));
        p.setTextSize(Fpx);
        if (p.measureText(t) <= maxPx) return new String[] { t };
        int best = -1;
        for (int i = 1; i < t.length() - 1; i++) if ("。；：，".indexOf(t.charAt(i)) >= 0 && (best < 0 || Math.abs(i - t.length() / 2) < Math.abs(best - t.length() / 2))) best = i;
        if (best < 0) best = t.length() / 2;
        return new String[] { t.substring(0, best + 1), t.substring(best + 1) };
    }

    private boolean ensureLabel(Row r) {
        if (r.lt != 0) return false;
        Txt.Label l = Txt.label(r.title, ils, sc.W * 0.2f, true);
        r.lt = G.texture(l.bmp, false);
        l.bmp.recycle();
        r.ml = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
        r.tw = l.w; r.tpad = l.pad;
        return true;
    }

    private void freeHint() {
        for (int k = 0; k < 2; k++) {
            if (hintTex[k] != 0) { G.free(hintTex[k]); hintTex[k] = 0; }
            if (hintMesh[k] != null) { hintMesh[k].free(); hintMesh[k] = null; }
        }
        hintNow = null;
    }

    /** 字号或字体变了：标题、说明、索引重写 */
    private void freeLabels() {
        if (titleTex != 0) { G.free(titleTex); titleTex = 0; }
        if (titleMesh != null) { titleMesh.free(); titleMesh = null; }
        freeHint();
        for (Row r : rows) {
            if (r.lt != 0) { G.free(r.lt); r.lt = 0; }
            if (r.ml != null) { r.ml.free(); r.ml = null; }
        }
    }

    // ================= 画 =================
    private final float[] SM2 = new float[16], hm = new float[16], t2 = new float[16], lm = new float[16];

    /** 不透明的：砖、返回键的玻璃（主画面、倒影都画） */
    void drawSolid(float[] V, float[] Pp) {
        if (!engaged()) return;
        if (nt > 0) {
            G.Prog pg = sc.pGlass;
            pg.use();
            pg.m4("uP", Pp);
            pg.tex("uAur", 3, sc.aurFbo.tex);
            pg.f("uHalf", Scene.HALF); pg.f("uDay", sc.day);
            pg.c3("uTint", sc.gTint);
            pg.f4("uRF", 0, 0, 1, 1); pg.f4("uRF2", 0, 0, 1, 1); pg.f4("uRB", 0, 0, 1, 1);
            pg.f("uFMix", 0);
            for (int k = 0; k < nt; k++) {
                Tile t = tiles[k];
                Item it = t.it;
                sc.mv(V, t.m);
                pg.m4("uMV", sc.MV);
                G.mul(SM2, sc.P, sc.M1); pg.m4("uSM", SM2);
                float a = it.tex == 0 ? 0 : clamp((sc.clock - it.at) / 0.3f, 0, 1);
                int bk = it.back != 0 ? it.back : it.tex;
                pg.tex("uFront", 0, it.tex); pg.tex("uFront2", 1, it.tex); pg.tex("uBack", 2, bk);
                pg.f2("uArt", a, it.back != 0 ? 1 : a);
                pg.f("uDim", t.dim); pg.f("uSheen", 0.75f);
                sc.mGlass.draw();
            }
        }
        if (mBack != null && kS > 0.002f) {
            G.Prog pp = sc.pPill;
            pp.use();
            pp.m4("uP", Pp);
            pp.tex("uAur", 0, sc.aurFbo.tex);
            pp.f2("uHalf", sh / 2, sh / 2); pp.f("uDay", sc.day);
            pp.c3("uTint", sc.pal.c[0]);
            sc.mv(V, backM);
            pp.m4("uMV", sc.MV);
            G.mul(SM2, sc.P, sc.M1); pp.m4("uSM", SM2);
            mBack.draw();
        }
    }

    /** 刚点过的那块：外圈亮一下（只在主画面里） */
    void drawUnder(float[] V, float[] Pp) {
        if (!engaged()) return;
        G.Prog ph = sc.pHalo;
        ph.use();
        ph.m4("uP", Pp);
        ph.c3("uTint", sc.haloTint); ph.f("uDay", sc.day);
        Matrix.setIdentityM(t2, 0);
        Matrix.translateM(t2, 0, 0, 0, -0.09f);
        for (int k = 0; k < nt; k++) {
            Tile t = tiles[k];
            if (t.halo <= 0.01f) continue;
            G.mul(hm, t.m, t2);
            sc.mv(V, hm);
            ph.m4("uMV", sc.MV); ph.f("uK", t.halo);
            sc.mHalo.draw();
        }
    }

    /** 标题、说明、左边的索引、返回键上的"‹"（主画面、倒影都画） */
    void drawText(float[] V, float[] Pp) {
        if (!engaged()) return;
        G.depthTest(false);
        G.Prog pt = sc.pText;
        pt.use();
        pt.m4("uP", Pp); pt.c3("uInk", sc.ink); pt.f("uK", 0.85f);
        if (kI > 0.002f && white != 0) {
            float rp = rowS.x, dx = -(1 - kI) * ils * 3 / fz;
            float gTop = gAt(rp), gBot = gAt(rp + Math.min(rowsFit, lines.size()) - 1); // 看得见的几排在哪几组
            float top = idxY(0) + iStep * 0.4f, bot = idxY(rows.length - 1) - iStep * 0.4f;
            bar(V, ixBar + dx, (top + bot) / 2, 3 / fz, top - bot, 0.14f * kI);
            pt.c3("uInk", sc.day > 0.5f ? sc.playPal.deep : sc.playPal.accent);
            bar(V, ixBar + dx, (idxY(gTop) + idxY(gBot)) / 2, 5 / fz, iStep * (gBot - gTop + 0.75f), kI);
            pt.c3("uInk", sc.ink);
            for (int i = 0; i < rows.length; i++) {
                Row r = rows[i];
                if (r.lt == 0) continue;
                float in = clamp(1 - Math.max(gTop - i, i - gBot), 0, 1);
                G.compose(lm, ixText + dx + (r.tw / 2 - r.tpad) / fz, idxY(i), Z, 0, 0, 0, 1 / fz, 1 / fz, 1);
                sc.mv(V, lm);
                pt.m4("uMV", sc.MV); pt.tex("uMap", 0, r.lt); pt.f("uA", kI * (0.35f + 0.65f * in));
                r.ml.draw();
            }
        }
        if (kS > 0.002f) {
            float dy = (1 - kS) * sh * 3;
            if (backTex != 0) {
                sc.mv(V, backIm);
                pt.m4("uMV", sc.MV); pt.tex("uMap", 0, backTex); pt.f("uA", kS);
                sc.mUnit.draw();
            }
            if (titleTex != 0) {
                G.compose(lm, titleX + (titleW / 2 - titlePad) / fz, topY + dy, Z, 0, 0, 0, 1 / fz, 1 / fz, 1);
                sc.mv(V, lm);
                pt.m4("uMV", sc.MV); pt.tex("uMap", 0, titleTex); pt.f("uA", kS);
                titleMesh.draw();
            }
            float lh = hintF * 1.35f / fz;
            for (int k = 0; k < 2; k++) {
                if (hintTex[k] == 0) continue;
                G.compose(lm, ixBar + (hintW[k] / 2 - hintPad[k]) / fz, hintY - k * lh + dy, Z, 0, 0, 0, 1 / fz, 1 / fz, 1);
                sc.mv(V, lm);
                pt.m4("uMV", sc.MV); pt.tex("uMap", 0, hintTex[k]); pt.f("uA", kS * hintA * 0.75f);
                hintMesh[k].draw();
            }
        }
        G.depthTest(true);
    }

    private void bar(float[] V, float x, float y, float w, float h, float a) {
        G.Prog pt = sc.pText;
        G.compose(lm, x, y, Z, 0, 0, 0, w, h, 1);
        sc.mv(V, lm);
        pt.m4("uMV", sc.MV); pt.tex("uMap", 0, white); pt.f("uA", a);
        sc.mUnit.draw();
    }

    // ================= 建 / 释放 =================
    private void engage() {
        rowS.set(0);
        hintWant = HINT;
        g = null;
    }

    /** 关完了：砖的图、字、玻璃全部还掉 */
    private void release() {
        for (Item it : items.values()) {
            if (it.tex != 0) { G.free(it.tex); it.tex = 0; }
            if (it.back != 0) { G.free(it.back); it.back = 0; }
            it.flip.set(0);
            it.shown = it.target;
            it.pulse = 0;
            it.stale = false;
        }
        freeLabels();
        if (mBack != null) { mBack.free(); mBack = null; }
        if (backTex != 0) { G.free(backTex); backTex = 0; }
        if (white != 0) { G.free(white); white = 0; }
        hintA = 0;
        nt = 0; kI = 0; kS = 0;
        g = null;
    }

    /** GL 环境建好（第一次、或者丢了重建）：旧的编号都作废、不去删 */
    void initGL() {
        for (Item it : items.values()) { it.tex = 0; it.back = 0; it.flip.set(0); it.shown = it.target; it.stale = false; }
        for (Row r : rows) { r.lt = 0; r.ml = null; }
        titleTex = 0; titleMesh = null;
        for (int k = 0; k < 2; k++) { hintTex[k] = 0; hintMesh[k] = null; }
        hintNow = null;
        mBack = null;
        backTex = 0;
        white = 0;
    }

    // ================= 手势（同曲库） =================
    private static final class Grab { float x, y, lx, ly, vy, row0; long t, lt; int axis, idx = -1; boolean back; Item item; }
    private Grab g;

    boolean touching() { return g != null; }

    /** a：0 按下 / 1 移动 / 2 抬起 / 3 取消；ex、ey 画面上的位置（场景像素）。开着的时候全归设置页管 */
    void touch(int a, float ex, float ey, long tms) {
        if (a == 0) {
            if (!want || q < 0.6f) { g = null; return; }
            Grab q0 = new Grab();
            q0.x = q0.lx = ex; q0.y = q0.ly = ey; q0.t = q0.lt = tms;
            q0.back = backAt(ex, ey);
            q0.idx = q0.back ? -1 : idxAt(ex, ey);
            q0.item = q0.idx >= 0 || q0.back ? null : tileAt(ex, ey);
            if (q0.item != null) q0.item.press.t = 0.92f;
            q0.row0 = rowS.x;
            rowS.set(rowS.x);
            g = q0;
            return;
        }
        Grab q1 = g;
        if (q1 == null) return;
        boolean cancel = a == 3;
        if (a == 1) {
            float dx = ex - q1.x, dy = ey - q1.y, dtm = Math.max(1, tms - q1.lt);
            q1.vy = q1.vy * 0.4f + (ey - q1.ly) / dtm * 0.6f;
            q1.lx = ex; q1.ly = ey; q1.lt = tms;
            if (q1.axis == 0 && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                q1.axis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                if (q1.item != null) { q1.item.press.t = 1; q1.item = null; }
            }
            if (q1.axis == 2) {
                float raw = q1.row0 - dy / (rowGap * fz), hi = maxRow();
                rowS.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw);
            }
            sc.wake();
            return;
        }
        if (q1.item != null) { q1.item.press.t = 1; q1.item.press.v += 3; }
        if (q1.axis == 0 && !cancel && tms - q1.t < 500) tap(q1);
        else if (q1.axis == 2) {
            float v = cancel ? 0 : -q1.vy * 1000 / (rowGap * fz);
            rowS.v = v;
            rowS.t = clamp(Math.round(rowS.x + v * 0.18f), 0, maxRow());
        }
        g = null;
        sc.wake();
    }

    private void tap(Grab q1) {
        if (q1.back) { close(); return; }
        if (q1.idx >= 0) { rowS.t = clamp(first[q1.idx], 0, maxRow()); return; } // 索引：滚到那组的第一排
        Item it = q1.item;
        if (it == null) { if (!headerAt(q1.y)) close(); return; } // 点空白：回去（点在标题、说明那一带不算）
        boolean rebuilds = "aa".equals(it.key) || "render".equals(it.key);
        if (it.target == null || rebuilds && sc.clock < lockUntil) return;
        Object nv = it.opts[(it.idx(it.target) + 1) % it.opts.length][0];
        if (rebuilds) lockUntil = sc.clock + 1.5f;
        it.target = nv;
        it.localAt = sc.clock;
        it.pulse = 1;
        hintWant = it.desc != null ? it.name + " · " + it.desc : HINT;
        sc.host.prefSet(it.key, nv);
    }

    private final float[] PS = new float[16], PM = new float[16], o3 = new float[3], c4 = new float[8];

    private void screen(float[] m, float x, float y, float z) {
        G.mul(PM, PS, m);
        G.project(o3, PM, x, y, z);
        o3[0] = (o3[0] * 0.5f + 0.5f) * sc.W;
        o3[1] = (0.5f - o3[1] * 0.5f) * sc.H;
    }

    private boolean backAt(float ex, float ey) {
        if (kS < 0.5f || mBack == null) return false;
        G.mul(PS, sc.P, sc.S);
        screen(backM, 0, 0, 0);
        return Math.abs(ex - o3[0]) < sh * fz * 0.75f && Math.abs(ey - o3[1]) < sh * fz * 0.8f;
    }

    /** 在砖排上面（标题、说明那一带） */
    private boolean headerAt(float ey) {
        G.mul(PS, sc.P, sc.S);
        Matrix.setIdentityM(tmp, 0);
        screen(tmp, 0, rowsTop, Z);
        return ey < o3[1];
    }

    private int idxAt(float ex, float ey) {
        if (kI < 0.5f) return -1;
        G.mul(PS, sc.P, sc.S);
        Matrix.setIdentityM(tmp, 0);
        screen(tmp, ixBar - ils * 0.8f / fz, idxY(0) + iStep * 0.5f, Z);
        float x0 = o3[0], y0 = o3[1];
        screen(tmp, xEdge, idxY(rows.length - 1) - iStep * 0.5f, Z);
        if (ex < x0 || ex > o3[0] || ey < y0 || ey > o3[1]) return -1;
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < rows.length; i++) {
            screen(tmp, 0, idxY(i), Z);
            float d = Math.abs(ey - o3[1]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private Item tileAt(float ex, float ey) {
        G.mul(PS, sc.P, sc.S);
        for (int k = 0; k < nt; k++) {
            Tile t = tiles[k];
            if (t.dim < 0.5f) continue;
            float h = 0.5f;
            float[][] cs = { { -h, -h }, { h, -h }, { h, h }, { -h, h } };
            for (int c = 0; c < 4; c++) { screen(t.m, cs[c][0], cs[c][1], 0.08f); c4[c * 2] = o3[0]; c4[c * 2 + 1] = o3[1]; }
            if (inQuad(ex, ey)) return t.it;
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
}
