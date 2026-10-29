package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/** OpenGL ES 3.0 的小工具：着色器、网格（VAO）、离屏缓冲、贴图、矩阵、几何体（平面、网格带、带倒角的挤出体）。只在 GL 线程里用 */
final class G {
    private G() { }

    // ================= 着色器 =================
    static final String HEAD = "#version 300 es\nprecision highp float;\n";
    static final String HEAD_F = "#version 300 es\nprecision mediump float;\n";

    static int program(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, HEAD + vs));
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, HEAD_F + fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(Uiplus.TAG, "着色器链接失败：" + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(Uiplus.TAG, "着色器编译失败：" + GLES30.glGetShaderInfoLog(s) + "\n" + src);
        return s;
    }

    // ================= GL 状态缓存：值没变就不再交给驱动（每帧几十次绘制、几百个 uniform 大多和上一帧一样） =================
    // 当前程序、当前贴图单元、每个单元绑着的贴图只在一个画布（GL 上下文）里有效。全屏页和小窗口各有一块画布、各在自己的 GL 线程里画，
    // 所以按线程各记一份；建画布时 resetCache()。贴图都经 bindTex 绑；删贴图时 GL 会把它从单元上解开，forget() 跟着清掉
    private static final class St { int prog = -1, unit = -1; final int[] bound = new int[32]; St() { java.util.Arrays.fill(bound, -1); } }
    private static final ThreadLocal<St> ST = new ThreadLocal<St>() { @Override protected St initialValue() { return new St(); } };
    static void resetCache() { ST.set(new St()); }
    static void activeUnit(int u) { St s = ST.get(); if (s.unit != u) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u); s.unit = u; } }
    static void bindTex(int u, int tex) { activeUnit(u); St s = ST.get(); if (s.bound[u] != tex) { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex); s.bound[u] = tex; } }
    private static void forget(int tex) { int[] b = ST.get().bound; for (int i = 0; i < b.length; i++) if (b[i] == tex) b[i] = 0; }

    /** 一个着色器程序和它的 uniform（位置按名字取，取过就记住；值和上次交给它的一样就不再交） */
    static final class Prog {
        final int id;
        private static final class U { final int loc; int n; float a, b, c, d; float[] m; int i = Integer.MIN_VALUE; U(int l) { loc = l; } }
        private final java.util.HashMap<String, U> us = new java.util.HashMap<>();
        Prog(String vs, String fs) { id = program(vs, fs); }
        private U uu(String n) { U x = us.get(n); if (x == null) { x = new U(GLES30.glGetUniformLocation(id, n)); us.put(n, x); } return x; }
        int u(String n) { return uu(n).loc; }
        void use() { St s = ST.get(); if (s.prog != id) { GLES30.glUseProgram(id); s.prog = id; } }
        void f(String n, float v) { U x = uu(n); if (x.n == 1 && x.a == v) return; x.n = 1; x.a = v; GLES30.glUniform1f(x.loc, v); }
        void f2(String n, float a, float b) { U x = uu(n); if (x.n == 2 && x.a == a && x.b == b) return; x.n = 2; x.a = a; x.b = b; GLES30.glUniform2f(x.loc, a, b); }
        void f3(String n, float a, float b, float c) { U x = uu(n); if (x.n == 3 && x.a == a && x.b == b && x.c == c) return; x.n = 3; x.a = a; x.b = b; x.c = c; GLES30.glUniform3f(x.loc, a, b, c); }
        void f4(String n, float a, float b, float c, float d) { U x = uu(n); if (x.n == 4 && x.a == a && x.b == b && x.c == c && x.d == d) return; x.n = 4; x.a = a; x.b = b; x.c = c; x.d = d; GLES30.glUniform4f(x.loc, a, b, c, d); }
        void c3(String n, float[] c) { f3(n, c[0], c[1], c[2]); }
        void m4(String n, float[] m) {
            U x = uu(n);
            if (x.m != null && java.util.Arrays.equals(x.m, m)) return;
            if (x.m == null) x.m = new float[16];
            System.arraycopy(m, 0, x.m, 0, 16);
            GLES30.glUniformMatrix4fv(x.loc, 1, false, m, 0);
        }
        void tex(String n, int unit, int tex) { bindTex(unit, tex); U x = uu(n); if (x.i != unit) { x.i = unit; GLES30.glUniform1i(x.loc, unit); } }
    }

    // ================= 网格 =================
    /** 一个 VAO：属性按 layout(location = i) 依次排（sizes 是每个属性几个分量），交错存放 */
    static final class Mesh {
        int vao, vbo, ibo, count, mode = GLES30.GL_TRIANGLES;
        boolean indexed;

        Mesh(float[] data, int[] sizes, short[] index) {
            int[] b = new int[2];
            GLES30.glGenVertexArrays(1, b, 0);
            vao = b[0];
            GLES30.glBindVertexArray(vao);
            GLES30.glGenBuffers(index != null ? 2 : 1, b, 0);
            vbo = b[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(data).position(0);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, fb, GLES30.GL_STATIC_DRAW);
            int stride = 0;
            for (int s : sizes) stride += s;
            int off = 0;
            for (int i = 0; i < sizes.length; i++) {
                GLES30.glEnableVertexAttribArray(i);
                GLES30.glVertexAttribPointer(i, sizes[i], GLES30.GL_FLOAT, false, stride * 4, off * 4);
                off += sizes[i];
            }
            if (index != null) {
                ibo = b[1];
                indexed = true;
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo);
                ShortBuffer sb = ByteBuffer.allocateDirect(index.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
                sb.put(index).position(0);
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, index.length * 2, sb, GLES30.GL_STATIC_DRAW);
                count = index.length;
            } else count = data.length / stride;
            GLES30.glBindVertexArray(0);
        }

        /** 换顶点数据（长度不变：光点每次重新瞄准） */
        void update(float[] data) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(data).position(0);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, data.length * 4, fb);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        void draw() {
            GLES30.glBindVertexArray(vao);
            if (indexed) GLES30.glDrawElements(mode, count, GLES30.GL_UNSIGNED_SHORT, 0);
            else GLES30.glDrawArrays(mode, 0, count);
            GLES30.glBindVertexArray(0);
        }

        void free() {
            GLES30.glDeleteVertexArrays(1, new int[] { vao }, 0);
            GLES30.glDeleteBuffers(indexed ? 2 : 1, indexed ? new int[] { vbo, ibo } : new int[] { vbo }, 0);
        }
    }

    /** 一块平面：x 从 x0 到 x1、y 从 y0 到 y1，带贴图坐标（位置 3 + uv 2 + 法线 3） */
    static Mesh quad(float x0, float y0, float x1, float y1) {
        float[] d = {
            x0, y0, 0, 0, 0, 0, 0, 1, x1, y0, 0, 1, 0, 0, 0, 1, x1, y1, 0, 1, 1, 0, 0, 1,
            x0, y0, 0, 0, 0, 0, 0, 1, x1, y1, 0, 1, 1, 0, 0, 1, x0, y1, 0, 0, 1, 0, 0, 1 };
        return new Mesh(d, new int[] { 3, 2, 3 }, null);
    }

    /** 光带用的网格带：x 在 -0.5～0.5 分 n 段，y 两排 -0.5 / 0.5 */
    static Mesh strip(int n) {
        float[] d = new float[(n + 1) * 2 * 3];
        short[] idx = new short[n * 6];
        for (int i = 0; i <= n; i++) {
            float x = -0.5f + i / (float) n;
            int k = i * 6;
            d[k] = x; d[k + 1] = -0.5f; d[k + 2] = 0; d[k + 3] = x; d[k + 4] = 0.5f; d[k + 5] = 0;
        }
        for (int i = 0; i < n; i++) {
            int a = i * 2, k = i * 6;
            idx[k] = (short) a; idx[k + 1] = (short) (a + 2); idx[k + 2] = (short) (a + 1);
            idx[k + 3] = (short) (a + 1); idx[k + 4] = (short) (a + 2); idx[k + 5] = (short) (a + 3);
        }
        return new Mesh(d, new int[] { 3 }, idx);
    }

    /** 点：每个点一组属性（sizes），GL_POINTS */
    static Mesh points(float[] data, int[] sizes) {
        Mesh m = new Mesh(data, sizes, null);
        m.mode = GLES30.GL_POINTS;
        return m;
    }

    // ================= 带倒角的挤出体（同 three 的 ExtrudeGeometry：轮廓 → 正面、倒角、侧面、倒角、背面） =================
    /** 圆角矩形的轮廓（逆时针，中心在原点）：w × h、圆角 r、每个角 seg 段 */
    static float[] roundRect(float w, float h, float r, int seg) {
        float[] o = new float[4 * (seg + 1) * 2];
        float[][] c = { { w / 2 - r, -h / 2 + r }, { w / 2 - r, h / 2 - r }, { -w / 2 + r, h / 2 - r }, { -w / 2 + r, -h / 2 + r } };
        int k = 0;
        for (int q = 0; q < 4; q++) {
            float a0 = (float) (-Math.PI / 2 + q * Math.PI / 2);
            for (int i = 0; i <= seg; i++) {
                float a = a0 + (float) (Math.PI / 2) * i / seg;
                o[k++] = c[q][0] + r * (float) Math.cos(a);
                o[k++] = c[q][1] + r * (float) Math.sin(a);
            }
        }
        return o;
    }

    /** 跑道形（两头半圆）：w × h、每个半圆 seg 段 */
    static float[] stadium(float w, float h, int seg) {
        float r = h / 2, x = w / 2 - r;
        float[] o = new float[2 * (seg + 1) * 2];
        int k = 0;
        for (int i = 0; i <= seg; i++) { float a = (float) (-Math.PI / 2 + Math.PI * i / seg); o[k++] = x + r * (float) Math.cos(a); o[k++] = r * (float) Math.sin(a); }
        for (int i = 0; i <= seg; i++) { float a = (float) (Math.PI / 2 + Math.PI * i / seg); o[k++] = -x + r * (float) Math.cos(a); o[k++] = r * (float) Math.sin(a); }
        return o;
    }

    /**
     * 挤出：轮廓 outline（凸的、逆时针）往外扩 bs 的倒角、厚 depth；正面在 z = zFront，往后 bt + depth + bt。
     * 倒角按四分之一圆弧 segs 段（同 three：z = bt·cos θ、往外 bs·sin θ），法线按弧面算（平滑）。顶点：位置 3 + 法线 3
     */
    static Mesh extrude(float[] outline, float depth, float bt, float bs, int segs, float zFront) {
        int n = outline.length / 2;
        // 每个轮廓点往外的法线（相邻两条边法线的平均）
        float[] on = new float[n * 2];
        for (int i = 0; i < n; i++) {
            int a = (i + n - 1) % n, b = (i + 1) % n;
            float ex1 = outline[i * 2] - outline[a * 2], ey1 = outline[i * 2 + 1] - outline[a * 2 + 1];
            float ex2 = outline[b * 2] - outline[i * 2], ey2 = outline[b * 2 + 1] - outline[i * 2 + 1];
            float nx = ey1 / len(ex1, ey1) + ey2 / len(ex2, ey2), ny = -ex1 / len(ex1, ey1) - ex2 / len(ex2, ey2);
            float l = len(nx, ny);
            on[i * 2] = nx / l; on[i * 2 + 1] = ny / l;
        }
        // 一圈圈的环：正面倒角（segs+1 圈）+ 背面倒角（segs+1 圈）；侧面就是两组中间连着的那段
        List<float[]> rings = new ArrayList<>();
        float zTop = zFront, zDepthTop = zFront - bt;
        for (int s = 0; s <= segs; s++) {
            double th = s / (double) segs * Math.PI / 2;
            rings.add(new float[] { (float) (bs * Math.sin(th)), zDepthTop + (float) (bt * Math.cos(th)), (float) (bt * Math.sin(th)), (float) (bs * Math.cos(th)) });
        }
        float zDepthBot = zDepthTop - depth;
        for (int s = segs; s >= 0; s--) {
            double th = s / (double) segs * Math.PI / 2;
            rings.add(new float[] { (float) (bs * Math.sin(th)), zDepthBot - (float) (bt * Math.cos(th)), (float) (bt * Math.sin(th)), -(float) (bs * Math.cos(th)) });
        }
        int R = rings.size();
        // 侧面（两组倒角之间）用纯水平的法线；为此在中间插两圈法线水平的环
        List<float[]> vtx = new ArrayList<>();
        List<Short> idx = new ArrayList<>();
        // 环上的顶点
        int[] ringStart = new int[R + 2];
        int rr = 0;
        for (int ri = 0; ri < R; ri++) {
            float[] g = rings.get(ri);
            if (ri == segs + 1) { // 侧面：上下两圈法线水平
                ringStart[rr++] = vtx.size();
                addRing(vtx, outline, on, rings.get(segs)[0], rings.get(segs)[1], 1, 0, n);
                ringStart[rr++] = vtx.size();
                addRing(vtx, outline, on, g[0], g[1], 1, 0, n);
            }
            ringStart[rr++] = vtx.size();
            float nl = len(g[2], g[3]);
            addRing(vtx, outline, on, g[0], g[1], g[2] / Math.max(nl, 1e-6f), g[3] / Math.max(nl, 1e-6f), n);
        }
        // 相邻的环连成四边形（侧面那两圈之间、以及每组倒角里面）
        for (int k = 0; k + 1 < rr; k++) {
            if (k == segs) continue;          // 正面倒角最后一圈 → 侧面第一圈：位置一样，不连
            if (k == segs + 2) continue;      // 侧面第二圈 → 背面倒角第一圈：位置一样，不连
            int a = ringStart[k], b = ringStart[k + 1];
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                idx.add((short) (a + i)); idx.add((short) (b + i)); idx.add((short) (a + j));
                idx.add((short) (a + j)); idx.add((short) (b + i)); idx.add((short) (b + j));
            }
        }
        // 正面、背面：扇形（轮廓是凸的）
        int cf = vtx.size();
        vtx.add(new float[] { 0, 0, zTop, 0, 0, 1 });
        for (int i = 0; i < n; i++) vtx.add(new float[] { outline[i * 2], outline[i * 2 + 1], zTop, 0, 0, 1 });
        for (int i = 0; i < n; i++) { idx.add((short) cf); idx.add((short) (cf + 1 + i)); idx.add((short) (cf + 1 + (i + 1) % n)); }
        int cb = vtx.size(), zb = 0;
        float zBot = zDepthBot - bt;
        vtx.add(new float[] { 0, 0, zBot, 0, 0, -1 });
        for (int i = 0; i < n; i++) vtx.add(new float[] { outline[i * 2], outline[i * 2 + 1], zBot, 0, 0, -1 });
        for (int i = 0; i < n; i++) { idx.add((short) cb); idx.add((short) (cb + 1 + (i + 1) % n)); idx.add((short) (cb + 1 + i)); }
        float[] data = new float[vtx.size() * 6];
        for (int i = 0; i < vtx.size(); i++) System.arraycopy(vtx.get(i), 0, data, i * 6, 6);
        short[] ix = new short[idx.size()];
        for (int i = 0; i < ix.length; i++) ix[i] = idx.get(i);
        return new Mesh(data, new int[] { 3, 3 }, ix);
    }

    private static void addRing(List<float[]> vtx, float[] o, float[] on, float out, float z, float nOut, float nz, int n) {
        for (int i = 0; i < n; i++) {
            float nx = on[i * 2], ny = on[i * 2 + 1];
            vtx.add(new float[] { o[i * 2] + nx * out, o[i * 2 + 1] + ny * out, z, nx * nOut, ny * nOut, nz });
        }
    }

    private static float len(float x, float y) { return (float) Math.sqrt(x * x + y * y); }

    // ================= 贴图 =================
    static int texture(Bitmap b, boolean mip) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        bindTex(0, t[0]);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mip ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        return t[0];
    }

    static void update(int tex, Bitmap b) {
        bindTex(0, tex);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
    }

    static void free(int tex) { if (tex != 0) { GLES30.glDeleteTextures(1, new int[] { tex }, 0); forget(tex); } }

    /** 离屏缓冲：一张颜色贴图（RGBA8）+ 可选的深度缓冲 */
    static final class Fbo {
        int fb, tex, depth, w, h;
        int samples; // 大于 0：先画进这么多倍采样的缓冲，resolve() 解析到 tex
        private int msFb, msColor;
        final boolean withDepth;
        Fbo(boolean withDepth) { this.withDepth = withDepth; }

        void size(int w2, int h2) {
            if (w2 == w && h2 == h && fb != 0) return;
            free();
            w = w2; h = h2;
            int[] a = new int[1];
            GLES30.glGenTextures(1, a, 0);
            tex = a[0];
            bindTex(0, tex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glGenFramebuffers(1, a, 0);
            fb = a[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0);
            if (samples > 0) multisample(a);
            if (withDepth && msFb == 0) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb);
                GLES30.glGenRenderbuffers(1, a, 0);
                depth = a[0];
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth);
                GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h);
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        /** 多重采样的颜色（和深度）缓冲；驱动不支持就不用，照常画进 tex */
        private void multisample(int[] a) {
            GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, a, 0);
            int n = Math.min(samples, a[0]);
            if (n < 2) return;
            GLES30.glGenFramebuffers(1, a, 0);
            msFb = a[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFb);
            GLES30.glGenRenderbuffers(1, a, 0);
            msColor = a[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msColor);
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, n, GLES30.GL_RGBA8, w, h);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msColor);
            if (withDepth) {
                GLES30.glGenRenderbuffers(1, a, 0);
                depth = a[0];
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth);
                GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, n, GLES30.GL_DEPTH_COMPONENT24, w, h);
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth);
            }
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) freeMs();
        }

        void bind() { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFb != 0 ? msFb : fb); GLES30.glViewport(0, 0, w, h); }

        private static final int[] DEPTH_ATT = { GLES30.GL_DEPTH_ATTACHMENT };

        /** 画完：多重采样的解析到 tex（深度先丢掉，不用存回内存） */
        void resolve() {
            if (msFb == 0) return;
            if (withDepth) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEPTH_ATT, 0);
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFb);
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fb);
            GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        }

        /** GL 环境丢了：旧编号作废，不去删（新环境里同样的编号可能已经是别的东西） */
        void forget() { fb = tex = depth = msFb = msColor = 0; w = h = 0; }

        private void freeMs() {
            if (msFb != 0) GLES30.glDeleteFramebuffers(1, new int[] { msFb }, 0);
            if (msColor != 0) GLES30.glDeleteRenderbuffers(1, new int[] { msColor }, 0);
            if (depth != 0) GLES30.glDeleteRenderbuffers(1, new int[] { depth }, 0);
            msFb = msColor = depth = 0;
        }

        void free() {
            freeMs();
            if (fb != 0) GLES30.glDeleteFramebuffers(1, new int[] { fb }, 0);
            if (tex != 0) { GLES30.glDeleteTextures(1, new int[] { tex }, 0); G.forget(tex); }
            fb = tex = 0;
            w = h = 0;
        }
    }

    // ================= 矩阵（列主序，同 GL / three） =================
    /** 平移 + 欧拉角（XYZ，同 three：R = Rx·Ry·Rz）+ 缩放 */
    static void compose(float[] out, float x, float y, float z, float rx, float ry, float rz, float sx, float sy, float sz) {
        float a = (float) Math.cos(rx), b = (float) Math.sin(rx), c = (float) Math.cos(ry), d = (float) Math.sin(ry), e = (float) Math.cos(rz), f = (float) Math.sin(rz);
        float ae = a * e, af = a * f, be = b * e, bf = b * f;
        out[0] = c * e * sx; out[4] = -c * f * sy; out[8] = d * sz;
        out[1] = (af + be * d) * sx; out[5] = (ae - bf * d) * sy; out[9] = -b * c * sz;
        out[2] = (bf - ae * d) * sx; out[6] = (be + af * d) * sy; out[10] = a * c * sz;
        out[3] = 0; out[7] = 0; out[11] = 0;
        out[12] = x; out[13] = y; out[14] = z; out[15] = 1;
    }

    static void mul(float[] out, float[] a, float[] b) { Matrix.multiplyMM(out, 0, a, 0, b, 0); }

    /** 点 (x, y, z) 乘矩阵（带透视除法）→ out[0..2] */
    static void project(float[] out, float[] m, float x, float y, float z) {
        float[] v = { x, y, z, 1 }, r = new float[4];
        Matrix.multiplyMV(r, 0, m, 0, v, 0);
        out[0] = r[0] / r[3]; out[1] = r[1] / r[3]; out[2] = r[2] / r[3];
    }
}
