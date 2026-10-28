package com.x3card.liuguang;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字（同网页版）：歌词一张贴图三个通道——R 是字、G 是稍微糊一点的字、B 是很糊的字（也当发光用），着色器按"失焦程度"在三者之间取；
 * 折行规则同网页版 rowsOf：有空格（歌词里分短句的）先在空格处折，空格太靠前才在字中间折；英文单词不拆；标点不放行首；几行尽量一样长。
 * 字体（设置里选）：得意黑（APK 里带的官方 ttf 原样，不改字体本身）/ 系统字体（粗体）；读不出来用系统字体。字体里没有的字系统自己补。
 * 网页版的霞鹜文楷没带：它的授权只允许为网页显示截字，APK 里只能放整个 13.8MB
 */
final class Txt {
    static final float TF = 72, RH = 1.17f;
    private static Typeface face = Typeface.DEFAULT_BOLD;
    private static final Paint MP = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final java.util.Map<String, Typeface> FACES = new java.util.HashMap<>();

    /** 读一套字体（选了哪个才读哪个，读过就留着；第一次读放在后台线程里） */
    static Typeface load(Context c, String id) {
        synchronized (FACES) {
            Typeface t = FACES.get(id);
            if (t != null) return t;
            String file = "smiley".equals(id) ? "SmileySans-Oblique.ttf" : null;
            try {
                t = file == null ? Typeface.DEFAULT_BOLD : Typeface.createFromAsset(c.getAssets(), file);
            } catch (Exception e) {
                Log.w(Uiplus.TAG, file + " 没装上，用系统字体", e);
                t = Typeface.DEFAULT_BOLD;
            }
            FACES.put(id, t);
            return t;
        }
    }

    /** 换字体（GL 线程里，换完歌词、歌名要重画） */
    static void use(Typeface t) {
        face = t;
        MP.setTypeface(t);
        MP.setTextSize(TF);
    }

    static Typeface face() { return face; }

    private static float w(String s) { return MP.measureText(s); }

    private static boolean noStart(char ch) { return "，。！？、；：,.!?;:）)」』”’…".indexOf(ch) >= 0; }

    private static List<String> wrap(String text, float lim, boolean phrase) {
        List<String> rows = new ArrayList<>();
        String cur = "";
        int sp = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!cur.isEmpty() && w(cur + ch) > lim && !noStart(ch)) {
                if (ch == ' ') { rows.add(cur); cur = ""; sp = -1; continue; }
                boolean word = Character.isLetterOrDigit(ch) && ch < 128 || ch == '\'';
                if (sp > 0 && (word || (phrase && w(cur.substring(0, sp)) >= lim * 0.45f))) { rows.add(cur.substring(0, sp)); cur = cur.substring(sp + 1) + ch; }
                else { rows.add(cur); cur = String.valueOf(ch); }
                sp = cur.lastIndexOf(' ');
            } else { if (ch == ' ') sp = cur.length(); cur += ch; }
        }
        if (!cur.isEmpty()) rows.add(cur);
        List<String> out = new ArrayList<>();
        for (String r : rows) { String t = r.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /** 按宽度折行（maxF：72 号字下的宽度）；mode "phrase" 只在短句的空格处折 */
    static List<String> rowsOf(String text, float maxF, String mode) {
        List<String> a = wrap(text, maxF, true), b = wrap(text, maxF, false);
        boolean phrase = "phrase".equals(mode) || a.size() <= b.size();
        List<String> rows = phrase ? a : b;
        if (rows.size() > 1) {
            for (float lim = w(text) / rows.size(); lim < maxF; lim += TF * 0.25f) {
                List<String> even = wrap(text, lim, phrase);
                if (even.size() == rows.size()) { rows = even; break; }
            }
        }
        return rows;
    }

    /** 一句歌词的贴图和尺寸（像素按 72 号字画，s 是换算到场景的比例）；原点在第一行字的左边、这一句的竖直中心 */
    static final class Line {
        Bitmap bmp;
        int W, H, rows;
        float s, pad = 30, wPx, hc, hPx;
    }

    static Line line(String text, float Fpx, float maxPx, String mode) {
        Line o = new Line();
        o.s = Fpx / TF;
        List<String> rows = rowsOf(text, maxPx / o.s, mode);
        if (rows.isEmpty()) rows.add(" ");
        float rh = TF * RH, maxw = 1;
        for (String r : rows) maxw = Math.max(maxw, w(r));
        o.W = Math.min(2048, (int) Math.ceil(maxw) + (int) o.pad * 2);
        o.H = (int) Math.ceil(rh * rows.size() + o.pad * 2);
        o.rows = rows.size();
        Bitmap b = Bitmap.createBitmap(o.W, o.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(TF);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = -(fm.ascent + fm.descent) / 2;
        // B：很糊（canvas 的 shadowBlur 24 ≈ σ 12）+ 字；G：稍糊（7）+ 字；R：字
        pass(c, p, rows, o, rh, base, 0xff0000ff, 20f);
        pass(c, p, rows, o, rh, base, 0xff0000ff, 0);
        pass(c, p, rows, o, rh, base, 0xff00ff00, 5.2f);
        pass(c, p, rows, o, rh, base, 0xff00ff00, 0);
        pass(c, p, rows, o, rh, base, 0xffff0000, 0);
        o.bmp = b;
        o.wPx = maxw * o.s;
        o.hc = rh * rows.size() * o.s / 2;
        o.hPx = rh * rows.size() * o.s;
        return o;
    }

    private static void pass(Canvas c, Paint p, List<String> rows, Line o, float rh, float base, int color, float blur) {
        p.setColor(color);
        p.setMaskFilter(blur > 0 ? new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) : null);
        for (int k = 0; k < rows.size(); k++) c.drawText(rows.get(k), o.pad, o.pad + rh * (k + 0.5f) + 3 + base, p);
    }

    /** 一行普通的字（歌名、歌手）：白字、左对齐、超出宽度加省略号；画在 64 号字的贴图上，返回 [贴图, 宽, 高（场景像素）, 左边留白] */
    static final class Label { Bitmap bmp; float w, h, pad; }

    static Label label(String text, float Fpx, float maxPx, boolean bold) {
        Label o = new Label();
        float s = Fpx / 64;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold ? face : Typeface.create(face, Typeface.NORMAL));
        p.setTextSize(64);
        float lim = maxPx / s;
        String t = text == null ? "" : text;
        if (p.measureText(t) > lim) { while (t.length() > 1 && p.measureText(t + "…") > lim) t = t.substring(0, t.length() - 1); t += "…"; }
        int W = (int) Math.ceil(p.measureText(t)) + 16, H = 92;
        Bitmap b = Bitmap.createBitmap(Math.max(W, 4), H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        p.setColor(Color.WHITE);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, 8, H / 2f + 3 - (fm.ascent + fm.descent) / 2, p);
        o.bmp = b;
        o.w = W * s;
        o.h = H * s;
        o.pad = 8 * s;
        return o;
    }

    /** 控制条上时间有多宽（按 "0:00" 量，排红心、按钮用；单位同 fontPx） */
    static float timeWidth(float fontPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        p.setTextSize(fontPx);
        return p.measureText("0:00");
    }

    /** 控制条两头的时间：画在和控制条一样长宽比的画布上 */
    static void time(Bitmap b, String left, String right, float fontPx) {
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        p.setTextSize(fontPx);
        p.setColor(Color.WHITE);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = b.getHeight() / 2f + 2 - (fm.ascent + fm.descent) / 2;
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(left, b.getWidth() * 0.075f, y, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(right, b.getWidth() * 0.925f, y, p);
    }
}
