package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 从封面取色（同网页版 palette()）：按色相分 12 格，取分量最大、互相隔开的三种颜色给极光（c），
 * 最鲜亮的一点当高亮色（accent），夜里当前句的颜色 hiN、白天的深色 deep。颜色都是 0～1 的 rgb
 */
final class Palette {
    final float[][] c = new float[3][];
    float[] accent, hiN, deep;

    private Palette() { }

    static Palette of(Bitmap src) {
        int n = 32;
        Bitmap b = Bitmap.createScaledBitmap(src, n, n, true);
        int[] px = new int[n * n];
        b.getPixels(px, 0, n, 0, 0, n, n);
        if (b != src) b.recycle();
        float[][] bins = new float[12][5]; // 权重、r、g、b、序号
        for (int i = 0; i < 12; i++) bins[i][4] = i;
        float[] acc = { 1, 1, 1 }, hsl = new float[3];
        float accS = -1;
        for (int p : px) {
            float r = Color.red(p) / 255f, g = Color.green(p) / 255f, bl = Color.blue(p) / 255f;
            toHsl(r, g, bl, hsl);
            float w = (hsl[1] + 0.05f) * (0.25f + hsl[2]) * (hsl[2] > 0.06f && hsl[2] < 0.97f ? 1 : 0.05f);
            float[] B = bins[(int) Math.floor(hsl[0] * 12) % 12];
            B[0] += w; B[1] += r * w; B[2] += g * w; B[3] += bl * w;
            float sc = hsl[1] * (1 - Math.abs(hsl[2] - 0.66f) * 1.4f);
            if (sc > accS) { accS = sc; acc = new float[] { r, g, bl }; }
        }
        java.util.List<float[]> order = new java.util.ArrayList<>();
        for (float[] B : bins) if (B[0] > 0) order.add(B);
        order.sort((x, y) -> Float.compare(y[0], x[0]));
        java.util.List<float[]> picks = new java.util.ArrayList<>();
        for (float[] B : order) {
            if (picks.size() >= 3) break;
            boolean ok = true;
            for (float[] q : picks) { int d = (int) Math.abs(q[4] - B[4]); if (Math.min(d, 12 - d) < 2) ok = false; }
            if (ok) picks.add(B);
        }
        java.util.List<float[]> cols = new java.util.ArrayList<>();
        for (float[] B : picks) cols.add(new float[] { B[1] / B[0], B[2] / B[0], B[3] / B[0] });
        if (cols.isEmpty()) cols.add(new float[] { 0.3f, 0.35f, 0.6f });
        while (cols.size() < 3) { toHsl(cols.get(0), hsl); cols.add(fromHsl((hsl[0] + 0.07f * cols.size()) % 1, hsl[1], hsl[2])); }
        Palette o = new Palette();
        for (int i = 0; i < 3; i++) { toHsl(cols.get(i), hsl); o.c[i] = fromHsl(hsl[0], clamp(hsl[1] * 1.15f, 0.42f, 0.95f), clamp(hsl[2], 0.34f, 0.56f)); }
        toHsl(acc, hsl);
        o.accent = fromHsl(hsl[0], Math.max(hsl[1], 0.55f), 0.74f);
        o.hiN = new float[] { 1 + (o.accent[0] - 1) * 0.7f, 1 + (o.accent[1] - 1) * 0.7f, 1 + (o.accent[2] - 1) * 0.7f };
        toHsl(o.c[0], hsl);
        o.deep = fromHsl(hsl[0], Math.max(hsl[1], 0.6f), 0.36f);
        return o;
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }

    private static void toHsl(float[] c, float[] out) { toHsl(c[0], c[1], c[2], out); }

    /** rgb → hsl（同 three 的 Color.getHSL：h、s、l 都是 0～1） */
    static void toHsl(float r, float g, float b, float[] out) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (min + max) / 2, h, s;
        if (min == max) { h = 0; s = 0; }
        else {
            float d = max - min;
            s = l <= 0.5f ? d / (max + min) : d / (2 - max - min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        out[0] = h; out[1] = s; out[2] = l;
    }

    static float[] fromHsl(float h, float s, float l) {
        h = ((h % 1) + 1) % 1;
        if (s == 0) return new float[] { l, l, l };
        float p = l <= 0.5f ? l * (1 + s) : l + s - l * s, q = 2 * l - p;
        return new float[] { hue(q, p, h + 1f / 3), hue(q, p, h), hue(q, p, h - 1f / 3) };
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3) return p + (q - p) * 6 * (2f / 3 - t);
        return p;
    }
}
