package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.Matrix;

/**
 * 歌单搜索的键盘：一整块玻璃（同控制条的着色器）从画面底下滑上来。最上面一行是打的字（右头退格），下面一排数字、三排字母，
 * 左下"收起"、右下"清空"。不跟着场景摆动（要点得准）、不照进水里，画在场景最上面。
 * 键帽和字画在一张 ALPHA_8 的图上（尺寸变了才重画），打的字另一张小图（打一个字重画一次）；按着的键在着色器里亮一下。
 * 点到哪个键：把手指的位置逆投影回键盘自己的平面上算。全在 GL 线程里
 */
final class SearchPad {
    static final char BACK = '<', CLEAR = '#', HIDE = '^';
    private static final String[] ROWS = { "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    private static final float RX = 0.07f; // 往后仰一点

    private final Scene sc;
    final Spring k = new Spring(0, 90, 19, 0.001f); // 0 收着、1 开着
    private final Spring press = new Spring(0, 400, 30, 0.002f);
    private boolean open;

    SearchPad(Scene sc) { this.sc = sc; }

    // ================= 布局（键盘自己的像素：x 往右、y 往下，左上角是 0） =================
    private float Lw = 1, Lh = 1, cy, rowH = 1, bv = 1; // bv：玻璃四周倒角的宽
    private final RectF field = new RectF();
    private char[] code = new char[0];
    private RectF[] key = new RectF[0];
    private String layoutKey = "";

    private void layout() {
        float W = sc.W, H = sc.H;
        Lw = sc.narrow ? W * 0.94f : Math.min(W * 0.86f, H * 1.3f);
        Lh = sc.narrow ? Math.min(H * 0.36f, W * 0.78f) : Math.min(H * 0.44f, Lw * 0.42f);
        cy = -H / 2 + H * 0.03f + Lh / 2;
        float pad = Lh * 0.05f, gap = Lh * 0.025f, kg = Lw * 0.008f;
        rowH = (Lh - pad * 2 - gap * 4) / 5;
        float kw = (Lw - pad * 2 - kg * 9) / 10, step = kw + kg;
        int nk = 1 + 10 + 10 + 9 + 7 + 2;
        code = new char[nk];
        key = new RectF[nk];
        int c = 0;
        float y0 = pad;
        field.set(pad, y0, Lw - pad - kw * 1.5f - kg, y0 + rowH);
        code[c] = BACK; key[c++] = new RectF(Lw - pad - kw * 1.5f, y0, Lw - pad, y0 + rowH);
        float[] off = { 0, 0, 0.5f, 1.5f };
        for (int r = 0; r < 4; r++) {
            float y = pad + (r + 1) * (rowH + gap);
            for (int j = 0; j < ROWS[r].length(); j++) {
                float x = pad + (off[r] + j) * step;
                code[c] = ROWS[r].charAt(j); key[c++] = new RectF(x, y, x + kw, y + rowH);
            }
        }
        float y4 = pad + 4 * (rowH + gap);
        code[c] = HIDE; key[c++] = new RectF(pad, y4, pad + 1.5f * step - kg, y4 + rowH);
        code[c] = CLEAR; key[c++] = new RectF(pad + 8.5f * step, y4, Lw - pad, y4 + rowH);
    }

    /** 键盘的上边在画面上多高（场景像素，画面中间是 0）：歌单那一行要让开它 */
    float top() { return cy + Lh / 2; }

    // ================= 开 / 关 =================
    boolean isOpen() { return open; }

    void open() { open = true; k.t = 1; pressed = -1; }

    void close() { open = false; k.t = 0; pressed = -1; press.t = 0; }

    // ================= 打的字 =================
    private String text = "", info = "", shown = null;

    void setText(String t, String inf) { text = t; info = inf; }

    // ================= GL 东西：开的时候建，收完还掉 =================
    private G.Mesh slab;
    private G.Prog pPad;
    private int capsTex, queryTex;
    private Bitmap queryBmp;
    private float iz;

    void initGL() {
        pPad = new G.Prog(Scene.V_UV, F_PAD);
        slab = null; capsTex = queryTex = 0; layoutKey = ""; shown = null;
    }

    private void build() {
        String lk = Math.round(sc.W) + "x" + Math.round(sc.H);
        if (lk.equals(layoutKey) && slab != null) return;
        freeGL();
        layoutKey = lk;
        layout();
        bv = Lh * 0.035f;
        slab = G.extrude(G.roundRect(Lw - bv * 2, Lh - bv * 2, Lh * 0.07f, 8), Lh * 0.05f, bv * 0.8f, bv, 3, 0);
        iz = bv * 0.8f + 0.5f;
        // 键帽、字
        float dpr = sc.DPR;
        int bw = Math.min(2048, Math.round(Lw * dpr) / 4 * 4), bh = Math.max(16, Math.round(bw * Lh / Lw)); // ALPHA_8 一行一字节：宽取 4 的倍数（GL 默认按 4 字节对齐读每一行）
        float s = bw / Lw;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Canvas cv = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextAlign(Paint.Align.CENTER);
        float rr = rowH * 0.2f * s;
        RectF q = new RectF(field.left * s, field.top * s, field.right * s, field.bottom * s);
        fill.setAlpha(0x1c);
        cv.drawRoundRect(q, rr, rr, fill);
        for (int i = 0; i < key.length; i++) {
            RectF r = key[i];
            q.set(r.left * s, r.top * s, r.right * s, r.bottom * s);
            char ch = code[i];
            boolean special = ch == BACK || ch == CLEAR || ch == HIDE;
            fill.setAlpha(special ? 0x3a : 0x26);
            cv.drawRoundRect(q, rr, rr, fill);
            txt.setAlpha(0xf2);
            float cx = q.centerX(), cyk = q.centerY();
            if (ch == BACK) drawBack(cv, cx, cyk, rowH * 0.24f * s, txt);
            else if (ch == HIDE) { txt.setTextSize(rowH * 0.36f * s); drawLabel(cv, "收起", cx, cyk, txt); }
            else if (ch == CLEAR) { txt.setTextSize(rowH * 0.36f * s); drawLabel(cv, "清空", cx, cyk, txt); }
            else { txt.setTextSize(rowH * 0.46f * s); drawLabel(cv, String.valueOf(Character.toUpperCase(ch)), cx, cyk, txt); }
        }
        capsTex = G.texture(b, false);
        b.recycle();
        int qw = Math.max(16, Math.round(field.width() * dpr) / 4 * 4), qh = Math.max(8, Math.round(field.height() * dpr));
        queryBmp = Bitmap.createBitmap(qw, qh, Bitmap.Config.ALPHA_8);
        shown = null;
    }

    private static void drawLabel(Canvas cv, String t, float cx, float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2, p);
    }

    /** 退格：往左的箭头形外框，里面一个叉 */
    private static void drawBack(Canvas cv, float cx, float cy, float r, Paint p) {
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(r * 0.16f);
        st.setStrokeJoin(Paint.Join.ROUND);
        st.setStrokeCap(Paint.Cap.ROUND);
        st.setAlpha(p.getAlpha());
        Path path = new Path();
        float w = r * 1.5f, h = r;
        path.moveTo(cx - w, cy);
        path.lineTo(cx - w * 0.45f, cy - h);
        path.lineTo(cx + w, cy - h);
        path.lineTo(cx + w, cy + h);
        path.lineTo(cx - w * 0.45f, cy + h);
        path.close();
        cv.drawPath(path, st);
        float x0 = cx + w * 0.28f, d = h * 0.42f;
        cv.drawLine(x0 - d, cy - d, x0 + d, cy + d, st);
        cv.drawLine(x0 - d, cy + d, x0 + d, cy - d, st);
    }

    /** 打的字（没打时是提示）、右头的"找到几首" */
    private void drawQuery() {
        String want = text + "|" + info;
        if (want.equals(shown) || queryBmp == null) return;
        shown = want;
        queryBmp.eraseColor(0);
        Canvas cv = new Canvas(queryBmp);
        float h = queryBmp.getHeight(), w = queryBmp.getWidth(), padX = h * 0.4f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm;
        float right = w - padX;
        if (!info.isEmpty()) {
            p.setTextSize(h * 0.34f);
            p.setAlpha(0x99);
            p.setTextAlign(Paint.Align.RIGHT);
            fm = p.getFontMetrics();
            cv.drawText(info, right, h / 2 - (fm.ascent + fm.descent) / 2, p);
            right -= p.measureText(info) + padX;
        }
        p.setTextAlign(Paint.Align.LEFT);
        if (text.isEmpty()) { p.setTextSize(h * 0.36f); p.setAlpha(0x70); }
        else { p.setTextSize(h * 0.5f); p.setAlpha(0xff); }
        String t = text.isEmpty() ? "打拼音首字母，比如 qlx（七里香）" : text;
        while (t.length() > 1 && p.measureText(t) > right - padX) t = text.isEmpty() ? t.substring(0, t.length() - 1) : t.substring(1);
        fm = p.getFontMetrics();
        cv.drawText(t, padX, h / 2 - (fm.ascent + fm.descent) / 2, p);
        if (queryTex == 0) queryTex = G.texture(queryBmp, false); else G.update(queryTex, queryBmp);
    }

    private void freeGL() {
        if (slab != null) slab.free();
        slab = null;
        G.free(capsTex); capsTex = 0;
        G.free(queryTex); queryTex = 0;
        if (queryBmp != null) { queryBmp.recycle(); queryBmp = null; }
        shown = null;
        layoutKey = "";
    }

    // ================= 每帧 =================
    private final float[] mv = new float[16], mvo = new float[16], tmp = new float[16], inv = new float[16], pm = new float[16];
    private int pressed = -1;

    /** 返回还在不在动；收完了还掉 GL 东西 */
    boolean tick(float dt) {
        if (open) build();
        boolean moving = k.step(dt);
        if (press.step(dt)) moving = true;
        if (!open && k.x < 0.001f && slab != null) freeGL();
        if (slab == null) return moving;
        drawQuery();
        float y = cy - (1 - k.x) * (Lh + sc.H * 0.06f);
        G.compose(mv, 0, y * sc.wpp, -Scene.D, RX, 0, 0, sc.wpp, sc.wpp, sc.wpp);
        G.compose(tmp, 0, 0, iz, 0, 0, 0, Lw, Lh, 1);
        G.mul(mvo, mv, tmp);
        return moving;
    }

    /** 画在场景最上面（只在主画面里） */
    void draw(float[] Pp) {
        if (slab == null || k.x < 0.002f || capsTex == 0) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        G.Prog pp = sc.pPill;
        pp.use();
        pp.m4("uP", Pp); pp.m4("uMV", mv);
        pp.tex("uAur", 0, sc.aurFbo.tex);
        G.mul(pm, sc.P, mv); pp.m4("uSM", pm);
        pp.f2("uHalf", Lw / 2, Lh / 2); pp.f("uDay", sc.day);
        pp.c3("uTint", sc.pal.c[0]);
        slab.draw();
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        pPad.use();
        pPad.m4("uP", Pp); pPad.m4("uMV", mvo);
        pPad.tex("uCaps", 0, capsTex);
        pPad.tex("uQuery", 1, queryTex != 0 ? queryTex : capsTex);
        pPad.f4("uQR", field.left / Lw, field.top / Lh, field.width() / Lw, field.height() / Lh);
        RectF r = pressed >= 0 ? key[pressed] : field;
        pPad.f4("uPR", r.centerX(), r.centerY(), r.width() / 2, r.height() / 2);
        pPad.f("uPress", pressed >= 0 ? press.x : 0); pPad.f("uR", rowH * 0.2f);
        pPad.f2("uSize", Lw, Lh); pPad.f("uSR", Lh * 0.07f); pPad.f("uSI", bv); pPad.f("uDay", sc.day);
        pPad.c3("uInk", sc.ink); pPad.f("uK", k.x);
        sc.mUnit.draw();
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    // ================= 点 =================
    private final float[] a4 = new float[4], b4 = new float[4], in4 = new float[4];

    /** 手指在键盘上的位置（键盘自己的像素，左上角 0）；不在键盘上返回假 */
    private boolean local(float ex, float ey, float[] out) {
        if (slab == null || k.x < 0.9f) return false;
        G.mul(pm, sc.P, mvo);
        if (!Matrix.invertM(inv, 0, pm, 0)) return false;
        float nx = ex / sc.W * 2 - 1, ny = 1 - ey / sc.H * 2;
        in4[0] = nx; in4[1] = ny; in4[2] = -1; in4[3] = 1;
        Matrix.multiplyMV(a4, 0, inv, 0, in4, 0);
        in4[2] = 1;
        Matrix.multiplyMV(b4, 0, inv, 0, in4, 0);
        float az = a4[2] / a4[3], bz = b4[2] / b4[3], t = Math.abs(bz - az) < 1e-6f ? 0 : -az / (bz - az);
        float x = a4[0] / a4[3] + (b4[0] / b4[3] - a4[0] / a4[3]) * t, y = a4[1] / a4[3] + (b4[1] / b4[3] - a4[1] / a4[3]) * t;
        out[0] = (x + 0.5f) * Lw; out[1] = (0.5f - y) * Lh;
        return out[0] >= 0 && out[0] <= Lw && out[1] >= 0 && out[1] <= Lh;
    }

    private final float[] lp = new float[2];

    /** 按在键盘上没有 */
    boolean hit(float ex, float ey) { return open && local(ex, ey, lp); }

    private int keyAt(float ex, float ey) {
        if (!local(ex, ey, lp)) return -1;
        float tol = rowH * 0.06f;
        for (int i = 0; i < key.length; i++) { RectF r = key[i]; if (lp[0] >= r.left - tol && lp[0] <= r.right + tol && lp[1] >= r.top - tol && lp[1] <= r.bottom + tol) return i; }
        return -1;
    }

    void down(float ex, float ey) { pressed = keyAt(ex, ey); press.t = pressed >= 0 ? 1 : 0; if (pressed >= 0) press.x = 1; }

    /** 抬起：还在按下去的那个键上就返回它（0 是没有） */
    char up(float ex, float ey, boolean cancel) {
        int kk = cancel ? -1 : keyAt(ex, ey);
        char c = kk >= 0 && kk == pressed ? code[kk] : 0;
        press.t = 0;
        pressed = -1;
        return c;
    }

    // 键帽、字底下先铺一层半透明的底（夜里深、白天浅），只铺在玻璃平的那一面上、不盖四周的倒角：玻璃透出后面的极光，亮的地方白字会看不见
    private static final String F_PAD = "uniform sampler2D uCaps, uQuery; uniform vec4 uQR, uPR; uniform vec2 uSize; uniform float uPress, uR, uSR, uSI, uK, uDay; uniform vec3 uInk; in vec2 vUv; out vec4 o;\n"
        + "float sdRR(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
        + "vec4 over(vec4 d, vec4 s) { return s + d * (1.0 - s.a); }\n"
        + "void main() {\n"
        + "  vec2 uv = vec2(vUv.x, 1.0 - vUv.y), px = uv * uSize;\n"
        + "  float a = texture(uCaps, uv).a;\n"
        + "  vec2 q = (uv - uQR.xy) / uQR.zw;\n"
        + "  float qa = q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0 ? texture(uQuery, q).a : 0.0;\n"
        + "  float pa = (1.0 - smoothstep(-1.0, 1.0, sdRR(px - uPR.xy, uPR.zw, uR))) * uPress * 0.3;\n"
        + "  float sa = (1.0 - smoothstep(-1.0, 1.0, sdRR(px - uSize * 0.5, uSize * 0.5 - uSI, uSR))) * mix(0.55, 0.6, uDay);\n"
        + "  vec4 c = vec4(mix(vec3(0.02, 0.025, 0.04), vec3(1.0), uDay) * sa, sa);\n"
        + "  c = over(c, vec4(uInk * a, a));\n"
        + "  c = over(c, vec4(uInk * qa, qa));\n"
        + "  c = over(c, vec4(uInk * pa, pa));\n"
        + "  o = c * uK;\n"
        + "}";
}
