package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局共用的键盘（场景里只有一个：Scene.kb），带中 / 英输入。谁要用就 open(自己, 提示)，打的字变了叫它的 changed()、点了收起叫 hide()（关不关它自己定）；
 * 同一时间只有最后 open 的那个是主人，别的问 isOpen / k 都当关着。
 * 一整块玻璃（同控制条的着色器）从画面底下滑上来：最上面一行是打的字（上屏的字后面跟着在打的拼音：切好音节、淡一点、带下划线；右头是主人给的 info），
 * 下面一排数字（打拼音时换成候选词：淡入淡出，左右划着看更多，第一个衬一块底），三排字母（最后一排左边中 / 英、右边退格），最下面收起、空格、清空。
 * 输入：中文时字母先进拼音（Pinyin 查候选），点候选词上屏、剩下的接着选；空格选第一个候选（没有就把字母原样上屏），不在打拼音时是空格；
 * 退格先删拼音再删字；切到英文时没选完的拼音原样上屏；一串拼音分几次选完的，选完时连起来记一个词（Pinyin.learn）。中 / 英一直记着，不随打开清掉。
 * 上屏的每个字另记一份拼音（spell），给按拼音筛的主人用（keyFull / keyInit）。
 * 不跟着场景摆动（要点得准）、不照进水里，画在场景最上面。键帽和字画在一张 ALPHA_8 的图上（尺寸、中英变了才重画），打的字、候选词各一张小图（变了才重画）；
 * 按着的键在着色器里亮一下。点到哪个键：把手指的位置逆投影回键盘自己的平面上算。全在 GL 线程里
 */
final class Keyboard {
    interface Owner {
        void changed(); // 上屏的字或者在打的拼音变了
        void hide();    // 点了收起
    }

    static final int MAX = 32, MAX_COMP = 40;
    private static final char BACK = '<', CLEAR = '#', HIDE = '^', SPACE = ' ', LANG = '~';
    private static final String[] ROWS = { "1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    private static final String HINT_EN = "英文：字母直接打上去";
    private static final float RX = 0.07f; // 往后仰一点

    private final Scene sc;
    private final Spring k = new Spring(0, 90, 19, 0.001f); // 0 收着、1 开着
    private final Spring press = new Spring(0, 400, 30, 0.002f);
    private final Spring candK = new Spring(0, 260, 32, 0.002f); // 数字那排换成候选词
    private final Spring candS = new Spring(0, 160, 26, 0.3f);   // 候选词往左划了多少（键盘像素）
    private boolean open, zh = true;
    private Owner owner;

    Keyboard(Scene sc) { this.sc = sc; }

    // ================= 主人用的 =================
    /** 打开（清掉上次打的），o 成为主人；hint：中文时没打字显示的提示 */
    void open(Owner o, String hint) {
        owner = o;
        hintZh = hint;
        text = ""; comp = ""; res = null; info = "";
        spell.clear();
        picked.clear();
        open = true;
        k.t = 1;
        pressed = -1;
        sc.host.imeOpen();
        show();
    }

    void close() { open = false; k.t = 0; pressed = -1; press.t = 0; }

    /** o 是主人、开着 */
    boolean isOpen(Owner o) { return open && owner == o; }

    /** 对 o 来说开着多少（0 收着、1 开着；不是主人是 0） */
    float k(Owner o) { return owner == o ? k.x : 0; }

    /** 键盘的上边在画面上多高（场景像素，画面中间是 0）：要让开它的东西按这个摆 */
    float top() { return cy + Lh / 2; }

    /** 上屏的字 */
    String text() { return text; }

    /** 正在打拼音（还没选完） */
    boolean composing() { return !comp.isEmpty(); }

    /** 上屏的字换成拼音（汉字是音节，字母数字是它自己）接上在打的拼音 */
    String keyFull() {
        StringBuilder b = new StringBuilder();
        for (String s : spell) b.append(s);
        return b.append(comp).toString();
    }

    /** 同 keyFull，汉字只取拼音首字母 */
    String keyInit() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < spell.size(); i++) { String s = spell.get(i); b.append(hanAt(i) && !s.isEmpty() ? s.substring(0, 1) : s); }
        return b.append(comp).toString();
    }

    /** 除了上屏的汉字以外还有字母数字（在打的拼音、英文）：按拼音筛 */
    boolean lettered() {
        if (!comp.isEmpty()) return true;
        for (int i = 0; i < spell.size(); i++) if (!hanAt(i) && !spell.get(i).isEmpty()) return true;
        return false;
    }

    private boolean hanAt(int i) { return text.charAt(i) > 0x7f; }

    /** 打的字那行右头的字（"找到 N 首"）；o 不是主人不改 */
    void setInfo(Owner o, String s) { if (owner == o) info = s == null ? "" : s; }

    // ================= 输入 =================
    private String text = "", comp = "", hintZh = "";
    private final List<String> spell = new ArrayList<>(); // 上屏的每个字的拼音（汉字的音节 ü 写成 u，同 SongIndex；字母数字是它自己；别的是空的）
    private Pinyin.Result res; // comp 的候选（词库还没读好是空的）
    private final List<Pinyin.Cand> picked = new ArrayList<>();

    private void refresh() { res = comp.isEmpty() ? null : Pinyin.query(comp); }

    /** 按了一个键（cand：点了第几个候选）；收起交给主人，别的改完叫 changed() */
    private void key(char c, int cand) {
        if (c == HIDE) { if (owner != null) owner.hide(); return; }
        if (cand >= 0) { if (res != null && cand < res.cands.length) pick(res.cands[cand]); }
        else if (c == CLEAR) { text = ""; comp = ""; res = null; spell.clear(); picked.clear(); }
        else if (c == BACK) {
            if (!comp.isEmpty()) { comp = comp.substring(0, comp.length() - 1); picked.clear(); refresh(); }
            else if (!text.isEmpty()) { text = text.substring(0, text.length() - 1); spell.remove(spell.size() - 1); }
        } else if (c == LANG) { flush(); zh = !zh; }
        else if (c == SPACE) {
            if (!comp.isEmpty()) { if (res != null && res.cands.length > 0) pick(res.cands[0]); else flush(); }
            else if (!text.isEmpty() && !text.endsWith(" ")) add(" ", null);
        } else if (zh && c >= 'a' && c <= 'z') {
            if (comp.length() < MAX_COMP) { comp += c; refresh(); }
        } else { flush(); add(String.valueOf(c), null); }
        show();
        if (owner != null) owner.changed();
    }

    /** 上屏 s（ids：每个字的音节，空的是字母数字原样）；超过 MAX 的截掉 */
    private void add(String s, short[] ids) {
        for (int i = 0; i < s.length() && text.length() < MAX; i++) {
            char ch = s.charAt(i);
            text += ch;
            spell.add(ids != null && i < ids.length ? Pinyin.syl(ids[i]).replace('v', 'u')
                : ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' ? String.valueOf(ch) : "");
        }
    }

    /** 没选完的拼音原样上屏 */
    private void flush() {
        if (comp.isEmpty()) return;
        add(comp, null);
        comp = "";
        res = null;
        picked.clear();
    }

    private void pick(Pinyin.Cand c) {
        add(c.text, c.ids);
        comp = c.used >= comp.length() ? "" : comp.substring(c.used);
        picked.add(c);
        if (comp.isEmpty()) {
            if (picked.size() == 1) Pinyin.learn(c.text, c.ids);
            else {
                StringBuilder w = new StringBuilder();
                int n = 0;
                for (Pinyin.Cand p : picked) { w.append(p.text); n += p.ids.length; }
                short[] ids = new short[n];
                int o = 0;
                for (Pinyin.Cand p : picked) { System.arraycopy(p.ids, 0, ids, o, p.ids.length); o += p.ids.length; }
                Pinyin.learn(w.toString(), ids);
            }
            picked.clear();
        }
        refresh();
    }

    /** 输入变了：换显示的拼音、候选词、提示（候选词换了一批从头看） */
    private void show() {
        compShown = comp.isEmpty() ? "" : res != null ? res.shown : comp;
        String[] n;
        if (res == null || comp.isEmpty()) n = new String[0];
        else { n = new String[res.cands.length]; for (int i = 0; i < n.length; i++) n[i] = res.cands[i].text; }
        if (!java.util.Arrays.equals(n, cands)) {
            cands = n;
            candLaid = false;
            candS.set(0);
            candK.t = n.length > 0 ? 1 : 0;
            if (n.length > 0) candShown = null;
        }
        hint = zh ? hintZh : HINT_EN;
    }

    // ================= 布局（键盘自己的像素：x 往右、y 往下，左上角是 0） =================
    private float Lw = 1, Lh = 1, cy, rowH = 1, bv = 1; // bv：玻璃四周倒角的宽
    private final RectF field = new RectF(), candR = new RectF();
    private char[] code = new char[0];
    private RectF[] key = new RectF[0];
    private String layoutKey = "", capsKey = "";

    private void layout() {
        float W = sc.W, H = sc.H;
        Lw = sc.narrow ? W * 0.94f : Math.min(W * 0.86f, H * 1.3f);
        Lh = sc.narrow ? Math.min(H * 0.42f, W * 0.9f) : Math.min(H * 0.5f, Lw * 0.48f);
        cy = -H / 2 + H * 0.03f + Lh / 2;
        float pad = Lh * 0.06f, gap = Lh * 0.02f, kg = Lw * 0.008f; // 边距要够：四个角上的键得落在玻璃平面的圆角（倒角以内、半径 Lh * 0.07）里面
        rowH = (Lh - pad * 2 - gap * 5) / 6;
        float kw = (Lw - pad * 2 - kg * 9) / 10, step = kw + kg;
        code = new char[10 + 10 + 9 + 7 + 5];
        key = new RectF[code.length];
        int c = 0;
        field.set(pad, pad, Lw - pad, pad + rowH);
        float[] off = { 0, 0, 0.5f, 1.5f };
        for (int r = 0; r < 4; r++) {
            float y = pad + (r + 1) * (rowH + gap);
            for (int j = 0; j < ROWS[r].length(); j++) {
                float x = pad + (off[r] + j) * step;
                code[c] = ROWS[r].charAt(j); key[c++] = new RectF(x, y, x + kw, y + rowH);
            }
        }
        float y1 = pad + rowH + gap, y4 = pad + 4 * (rowH + gap), y5 = pad + 5 * (rowH + gap);
        candR.set(pad, y1, Lw - pad, y1 + rowH);
        code[c] = LANG; key[c++] = new RectF(pad, y4, pad + 1.5f * step - kg, y4 + rowH);
        code[c] = BACK; key[c++] = new RectF(pad + 8.5f * step, y4, Lw - pad, y4 + rowH);
        code[c] = HIDE; key[c++] = new RectF(pad, y5, pad + 1.5f * step - kg, y5 + rowH);
        code[c] = SPACE; key[c++] = new RectF(pad + 1.5f * step, y5, pad + 8.5f * step - kg, y5 + rowH);
        code[c] = CLEAR; key[c++] = new RectF(pad + 8.5f * step, y5, Lw - pad, y5 + rowH);
    }

    // ================= 显示 =================
    private String info = "", hint = "", compShown = "", shown = null;
    private String[] cands = new String[0];
    private float[] cx0 = new float[0], cx1 = new float[0]; // 每个候选词在那一排里的左右（键盘像素，没划时）
    private float candW;

    // ================= GL 东西：开的时候建，收完还掉 =================
    private G.Mesh slab;
    private G.Prog pPad;
    private int capsTex, queryTex, candTex;
    private Bitmap queryBmp, candBmp;
    private float iz;

    void initGL() {
        pPad = new G.Prog(Scene.V_UV, F_PAD);
        slab = null; capsTex = queryTex = candTex = 0; layoutKey = capsKey = ""; shown = candShown = null;
    }

    private void build() {
        String lk = Math.round(sc.W) + "x" + Math.round(sc.H);
        if (!lk.equals(layoutKey) || slab == null) {
            freeGL();
            layoutKey = lk;
            layout();
            bv = Lh * 0.035f;
            slab = G.extrude(G.roundRect(Lw - bv * 2, Lh - bv * 2, Lh * 0.07f, 8), Lh * 0.05f, bv * 0.8f, bv, 3, 0);
            iz = bv * 0.8f + 0.5f;
            float dpr = sc.DPR;
            int qw = Math.max(16, Math.round(field.width() * dpr) / 4 * 4), qh = Math.max(8, Math.round(field.height() * dpr));
            queryBmp = Bitmap.createBitmap(qw, qh, Bitmap.Config.ALPHA_8);
            int cw = Math.max(16, Math.round(candR.width() * dpr) / 4 * 4), ch = Math.max(8, Math.round(candR.height() * dpr));
            candBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ALPHA_8);
            shown = candShown = null;
            candLaid = false;
        }
        String ck = lk + (zh ? "z" : "e");
        if (ck.equals(capsKey) && capsTex != 0) return;
        capsKey = ck;
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
            boolean special = ch == BACK || ch == CLEAR || ch == HIDE || ch == LANG;
            fill.setAlpha(special ? 0x3a : 0x26);
            cv.drawRoundRect(q, rr, rr, fill);
            txt.setAlpha(0xf2);
            float cx = q.centerX(), cyk = q.centerY();
            if (ch == BACK) drawBack(cv, cx, cyk, rowH * 0.24f * s, txt);
            else if (ch == HIDE) { txt.setTextSize(rowH * 0.36f * s); drawLabel(cv, "收起", cx, cyk, txt); }
            else if (ch == CLEAR) { txt.setTextSize(rowH * 0.36f * s); drawLabel(cv, "清空", cx, cyk, txt); }
            else if (ch == SPACE) { txt.setTextSize(rowH * 0.34f * s); txt.setAlpha(0xb0); drawLabel(cv, "空格", cx, cyk, txt); }
            else if (ch == LANG) drawLang(cv, cx, cyk, rowH * s, txt);
            else { txt.setTextSize(rowH * 0.46f * s); drawLabel(cv, String.valueOf(Character.toUpperCase(ch)), cx, cyk, txt); }
        }
        G.free(capsTex);
        capsTex = G.texture(b, false);
        b.recycle();
    }

    private static void drawLabel(Canvas cv, String t, float cx, float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2, p);
    }

    /** 中 / 英：现在用的那个大、亮，另一个小、淡 */
    private void drawLang(Canvas cv, float cx, float cy, float h, Paint p) {
        String on = zh ? "中" : "英", off = zh ? "英" : "中";
        p.setTextSize(h * 0.42f);
        float w1 = p.measureText(on);
        p.setTextSize(h * 0.26f);
        float w2 = p.measureText(off), gap = h * 0.08f, x = cx - (w1 + gap + w2) / 2;
        p.setTextSize(h * 0.42f);
        p.setAlpha(0xf2);
        drawLabel(cv, on, x + w1 / 2, cy, p);
        p.setTextSize(h * 0.26f);
        p.setAlpha(0x70);
        drawLabel(cv, off, x + w1 + gap + w2 / 2, cy + h * 0.06f, p);
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

    /** 打的字（没打时是提示）、后面在打的拼音、右头的 info；放不下从前面截掉 */
    private void drawQuery() {
        String want = text + "\u0001" + compShown + "\u0001" + info + "\u0001" + hint;
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
        if (text.isEmpty() && compShown.isEmpty()) {
            p.setTextSize(h * 0.36f);
            p.setAlpha(0x70);
            String t = hint;
            while (t.length() > 1 && p.measureText(t) > right - padX) t = t.substring(0, t.length() - 1);
            fm = p.getFontMetrics();
            cv.drawText(t, padX, h / 2 - (fm.ascent + fm.descent) / 2, p);
        } else {
            p.setTextSize(h * 0.5f);
            String all = text + compShown;
            int st = 0;
            while (st < all.length() - 1 && p.measureText(all, st, all.length()) > right - padX) st++;
            String a1 = st < text.length() ? all.substring(st, text.length()) : "", a2 = all.substring(Math.max(st, text.length()));
            fm = p.getFontMetrics();
            float base = h / 2 - (fm.ascent + fm.descent) / 2, x = padX;
            p.setAlpha(0xff);
            cv.drawText(a1, x, base, p);
            x += p.measureText(a1);
            if (!a2.isEmpty()) {
                p.setAlpha(0xa8);
                cv.drawText(a2, x, base, p);
                float x2 = x + p.measureText(a2), ly = base + h * 0.1f;
                p.setStrokeWidth(Math.max(1, h * 0.035f));
                cv.drawLine(x, ly, x2, ly, p);
            }
        }
        if (queryTex == 0) queryTex = G.texture(queryBmp, false); else G.update(queryTex, queryBmp);
    }

    // ================= 候选词那一排 =================
    private boolean candLaid;
    private String candShown;
    private float candPadX;

    /** 每个候选词的左右（键盘像素） */
    private void layCands() {
        if (candLaid) return;
        candLaid = true;
        Paint p = candPaint(1);
        candPadX = rowH * 0.38f;
        cx0 = new float[cands.length];
        cx1 = new float[cands.length];
        float x = rowH * 0.12f;
        for (int i = 0; i < cands.length; i++) {
            cx0[i] = x;
            x += p.measureText(cands[i]) + candPadX * 2;
            cx1[i] = x;
            x += rowH * 0.06f;
        }
        candW = x;
    }

    private Paint candPaint(float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(rowH * 0.46f * s);
        return p;
    }

    private float candMax() { return Math.max(0, candW - candR.width()); }

    /** 候选词那排的图：换了一批、划动了才重画（收起来淡掉时留着最后一张） */
    private void drawCands() {
        if (candBmp == null || cands.length == 0) return;
        layCands();
        String want = cands.length + "|" + System.identityHashCode(cands) + "|" + Math.round(candS.x * sc.DPR);
        if (want.equals(candShown)) return;
        candShown = want;
        candBmp.eraseColor(0);
        Canvas cv = new Canvas(candBmp);
        float s = candBmp.getWidth() / candR.width(), h = candBmp.getHeight(), off = candS.x;
        Paint p = candPaint(s), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = h / 2 - (fm.ascent + fm.descent) / 2, ph = h * 0.78f;
        for (int i = 0; i < cands.length; i++) {
            float a = (cx0[i] - off) * s, b = (cx1[i] - off) * s;
            if (b < 0) continue;
            if (a > candBmp.getWidth()) break;
            if (i == 0) { // 第一个：空格选它，衬一块底
                bg.setAlpha(0x30);
                cv.drawRoundRect(new RectF(a, (h - ph) / 2, b, (h + ph) / 2), ph * 0.3f, ph * 0.3f, bg);
            }
            p.setAlpha(i == 0 ? 0xff : 0xe0);
            cv.drawText(cands[i], a + candPadX * s, base, p);
        }
        if (candTex == 0) candTex = G.texture(candBmp, false); else G.update(candTex, candBmp);
    }

    private void freeGL() {
        if (slab != null) slab.free();
        slab = null;
        G.free(capsTex); capsTex = 0;
        G.free(queryTex); queryTex = 0;
        G.free(candTex); candTex = 0;
        if (queryBmp != null) { queryBmp.recycle(); queryBmp = null; }
        if (candBmp != null) { candBmp.recycle(); candBmp = null; }
        shown = candShown = null;
        layoutKey = capsKey = "";
    }

    // ================= 每帧 =================
    private final float[] mv = new float[16], mvo = new float[16], tmp = new float[16], inv = new float[16], pm = new float[16];
    private int pressed = -1;

    /** 返回还在不在动；收完了还掉 GL 东西 */
    boolean tick(float dt) {
        if (open) {
            build();
            if (!comp.isEmpty() && res == null && Pinyin.ready()) { refresh(); show(); } // 词库刚读好
        }
        boolean moving = k.step(dt);
        if (press.step(dt)) moving = true;
        if (candK.step(dt)) moving = true;
        if (candS.step(dt)) moving = true;
        if (!open && k.x < 0.001f && slab != null) freeGL();
        if (slab == null) return moving;
        drawQuery();
        drawCands();
        float y = cy - (1 - k.x) * (Lh + sc.H * 0.06f);
        G.compose(mv, 0, y * sc.wpp, -Scene.D, RX, 0, 0, sc.wpp, sc.wpp, sc.wpp);
        G.compose(tmp, 0, 0, iz, 0, 0, 0, Lw, Lh, 1);
        G.mul(mvo, mv, tmp);
        return moving;
    }

    private final RectF pr = new RectF();

    /** 画在场景最上面（只在主画面里） */
    void draw(float[] Pp) {
        if (slab == null || k.x < 0.002f || capsTex == 0) return;
        G.depthTest(false);
        G.blendPremul(false);
        G.Prog pp = sc.pPill;
        pp.use();
        pp.m4("uP", Pp); pp.m4("uMV", mv);
        pp.tex("uAur", 0, sc.aurFbo.tex);
        G.mul(pm, sc.P, mv); pp.m4("uSM", pm);
        pp.f2("uHalf", Lw / 2, Lh / 2); pp.f("uDay", sc.day);
        pp.c3("uTint", sc.pal.c[0]);
        slab.draw();
        G.blendPremul(true);
        pPad.use();
        pPad.m4("uP", Pp); pPad.m4("uMV", mvo);
        pPad.tex("uCaps", 0, capsTex);
        pPad.tex("uQuery", 1, queryTex != 0 ? queryTex : capsTex);
        pPad.tex("uCand", 2, candTex != 0 ? candTex : capsTex);
        pPad.f4("uQR", field.left / Lw, field.top / Lh, field.width() / Lw, field.height() / Lh);
        pPad.f4("uCR", candR.left / Lw, candR.top / Lh, candR.width() / Lw, candR.height() / Lh);
        pPad.f("uCK", candTex != 0 ? candK.x : 0);
        boolean on = pressed >= 0 || pressedCand >= 0;
        float rad = rowH * 0.2f;
        if (pressed >= 0) pr.set(key[pressed]);
        else if (pressedCand >= 0 && pressedCand < cx0.length) {
            pr.set(candR.left + cx0[pressedCand] - candS.x, candR.top + rowH * 0.11f, candR.left + cx1[pressedCand] - candS.x, candR.bottom - rowH * 0.11f);
            rad = pr.height() * 0.3f;
        } else pr.set(field);
        pPad.f4("uPR", pr.centerX(), pr.centerY(), pr.width() / 2, pr.height() / 2);
        pPad.f("uPress", on ? press.x : 0); pPad.f("uR", rad);
        pPad.f2("uSize", Lw, Lh); pPad.f("uSR", Lh * 0.07f); pPad.f("uSI", bv); pPad.f("uDay", sc.day);
        pPad.c3("uInk", sc.ink); pPad.f("uK", k.x);
        sc.mUnit.draw();
        G.depthTest(true);
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

    /** o 的键盘开着、按在键盘上 */
    boolean hit(Owner o, float ex, float ey) { return isOpen(o) && local(ex, ey, lp); }

    /** 候选词那排露出来了（这时数字键点不到） */
    private boolean candOn() { return cands.length > 0 && candK.t > 0.5f; }

    private int keyAt(float ex, float ey) {
        if (!local(ex, ey, lp)) return -1;
        float tol = rowH * 0.06f;
        boolean co = candOn();
        for (int i = 0; i < key.length; i++) {
            if (co && code[i] >= '0' && code[i] <= '9') continue;
            RectF r = key[i];
            if (lp[0] >= r.left - tol && lp[0] <= r.right + tol && lp[1] >= r.top - tol && lp[1] <= r.bottom + tol) return i;
        }
        return -1;
    }

    /** 那排里 x（键盘像素）上是第几个候选词（-1：没有） */
    private int candIdx(float x) {
        float u = x - candR.left + candS.x;
        for (int i = 0; i < cx0.length; i++) if (u >= cx0[i] - rowH * 0.03f && u <= cx1[i] + rowH * 0.03f) return i;
        return -1;
    }

    private int pressedCand = -1;
    private boolean candDown, candDrag;
    private float cdX, cdS0, cdLx, cdV;
    private long cdT;

    void down(float ex, float ey) {
        pressedCand = -1;
        candDown = candDrag = false;
        if (candOn() && local(ex, ey, lp) && lp[1] >= candR.top - rowH * 0.1f && lp[1] <= candR.bottom + rowH * 0.1f) {
            layCands();
            candDown = true;
            cdX = cdLx = lp[0];
            cdS0 = candS.x;
            candS.set(candS.x);
            cdV = 0;
            cdT = System.nanoTime();
            pressedCand = candIdx(lp[0]);
            pressed = -1;
        } else pressed = keyAt(ex, ey);
        press.t = pressed >= 0 || pressedCand >= 0 ? 1 : 0;
        if (press.t > 0) press.x = 1;
    }

    /** 手指挪动：按在候选词那排上左右划着看 */
    void move(float ex, float ey) {
        if (!candDown || !local(ex, ey, lp)) return;
        float dx = lp[0] - cdX;
        if (!candDrag && Math.abs(dx) > rowH * 0.22f) { candDrag = true; pressedCand = -1; press.t = 0; }
        if (!candDrag) return;
        long now = System.nanoTime();
        float dt = Math.max(0.001f, (now - cdT) / 1e9f), hi = candMax(), raw = cdS0 - dx;
        cdV = cdV * 0.4f + (cdLx - lp[0]) / dt * 0.6f;
        cdLx = lp[0];
        cdT = now;
        candS.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw);
        sc.wake();
    }

    /** 抬起：还在按下去的那个键 / 候选词上就按它 */
    void up(float ex, float ey, boolean cancel) {
        char c = 0;
        int cand = -1;
        if (candDown) {
            if (candDrag) candS.t = Math.max(0, Math.min(candMax(), candS.x + (cancel ? 0 : cdV * 0.25f)));
            else if (!cancel && pressedCand >= 0 && local(ex, ey, lp) && candIdx(lp[0]) == pressedCand) cand = pressedCand;
        } else {
            int kk = cancel ? -1 : keyAt(ex, ey);
            c = kk >= 0 && kk == pressed ? code[kk] : 0;
        }
        press.t = 0;
        pressed = -1;
        pressedCand = -1;
        candDown = candDrag = false;
        if (c != 0 || cand >= 0) key(c, cand);
    }

    // 键帽、字底下先铺一层半透明的底（夜里深、白天浅），只铺在玻璃平的那一面上、不盖四周的倒角：玻璃透出后面的极光，亮的地方白字会看不见。
    // 候选词那排盖在数字那排上（uCK：0 数字、1 候选词）
    private static final String F_PAD = "uniform sampler2D uCaps, uQuery, uCand; uniform vec4 uQR, uPR, uCR; uniform vec2 uSize; uniform float uPress, uR, uSR, uSI, uK, uDay, uCK; uniform vec3 uInk; in vec2 vUv; out vec4 o;\n"
        + "float sdRR(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
        + "vec4 over(vec4 d, vec4 s) { return s + d * (1.0 - s.a); }\n"
        + "void main() {\n"
        + "  vec2 uv = vec2(vUv.x, 1.0 - vUv.y), px = uv * uSize;\n"
        + "  float a = texture(uCaps, uv).a;\n"
        + "  vec2 r = (uv - uCR.xy) / uCR.zw;\n"
        + "  float inR = step(0.0, r.x) * step(r.x, 1.0) * step(0.0, r.y) * step(r.y, 1.0);\n"
        + "  a = mix(a, texture(uCand, clamp(r, 0.0, 1.0)).a, uCK * inR);\n"
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
