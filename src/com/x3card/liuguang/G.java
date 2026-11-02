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
    // vk：这个线程画的是 Vulkan 画布（VkSceneView）时绑着它，下面每个调用都交给它，不碰 GL
    private static final class St { int prog = -1, unit = -1, vao = -1; boolean revEq; final int[] bound = new int[32]; Vk vk; St() { java.util.Arrays.fill(bound, -1); } }
    private static final ThreadLocal<St> ST = new ThreadLocal<St>() { @Override protected St initialValue() { return new St(); } };
    static void resetCache() { St n = new St(); n.vk = ST.get().vk; ST.set(n); }
    static void useVk(Vk v) { ST.get().vk = v; }
    static boolean vk() { return ST.get().vk != null; }
    static void activeUnit(int u) { St s = ST.get(); if (s.unit != u) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u); s.unit = u; } }
    /** 绑 VAO（画完不解绑：改 VAO 内容的只有建网格时，都先绑上自己的；VAO 都经这里绑） */
    static void bindVao(int vao) { St s = ST.get(); if (s.vao != vao) { GLES30.glBindVertexArray(vao); s.vao = vao; } }
    static void bindTex(int u, int tex) { activeUnit(u); St s = ST.get(); if (s.bound[u] != tex) { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex); s.bound[u] = tex; } }
    private static void forget(int tex) { int[] b = ST.get().bound; for (int i = 0; i < b.length; i++) if (b[i] == tex) b[i] = 0; }

    /**
     * 一个着色器程序和它的 uniform（位置按名字取，取过就记住；值和上次交给它的一样就不再交）。
     * Vulkan 时 id 是原生那边的编号，uniform 写进 vu / fu（顶点、片元两个块，布局 vsI / fsI），贴图记在 tx（按 sampler 的先后），画的时候 Vk 一起带走
     */
    static final class Prog {
        final int id;
        final Vk vk;
        Vk.Stage vsI, fsI;
        float[] vu, fu;
        final int[] tx = new int[4];
        boolean vd, fd;
        int vAt, fAt, seen = -1;
        private static final class U { final int loc; int n; float a, b, c, d; float[] m; int i = Integer.MIN_VALUE; int vo = -1, fo = -1, ti = -1; U(int l) { loc = l; } }
        private String[] names = new String[16];
        private U[] us = new U[16];
        private int un, hint = -1;
        Prog(String vs, String fs) {
            vk = ST.get().vk;
            if (vk == null) { id = program(vs, fs); return; }
            id = vk.prog(HEAD + vs, HEAD_F + fs, this);
            if (vsI != null && vsI.size > 0) vu = new float[vsI.size / 4];
            if (fsI != null && fsI.size > 0) fu = new float[fsI.size / 4];
        }
        /** 按名字找 uniform：每帧设参数的先后差不多一样，从上一次找到的下一个开始看；名字都是字面量，先按引用比，比不上再按内容比 */
        private U uu(String n) {
            for (int k = 0; k < un; k++) { int j = hint + 1 + k; if (j >= un) j -= un; if (names[j] == n) { hint = j; return us[j]; } }
            for (int j = 0; j < un; j++) if (names[j].equals(n)) { hint = j; return us[j]; }
            if (un == names.length) { names = java.util.Arrays.copyOf(names, un * 2); us = java.util.Arrays.copyOf(us, un * 2); }
            U x;
            if (vk == null) x = new U(GLES30.glGetUniformLocation(id, n));
            else {
                x = new U(-1);
                Integer o = vsI != null ? vsI.off.get(n) : null;
                if (o != null) x.vo = o;
                o = fsI != null ? fsI.off.get(n) : null;
                if (o != null) x.fo = o;
                if (fsI != null) x.ti = fsI.sampler(n);
            }
            names[un] = n; us[un] = x; hint = un++;
            return x;
        }
        /** Vulkan：值写进块里（k 个分量） */
        private void put(U x, float a, float b, float c, float d, int k) {
            if (x.vo >= 0) { float[] u = vu; int o = x.vo; u[o] = a; if (k > 1) u[o + 1] = b; if (k > 2) u[o + 2] = c; if (k > 3) u[o + 3] = d; vd = true; }
            if (x.fo >= 0) { float[] u = fu; int o = x.fo; u[o] = a; if (k > 1) u[o + 1] = b; if (k > 2) u[o + 2] = c; if (k > 3) u[o + 3] = d; fd = true; }
        }
        int u(String n) { return uu(n).loc; }
        void use() { if (vk != null) { vk.prog = this; return; } St s = ST.get(); if (s.prog != id) { GLES30.glUseProgram(id); s.prog = id; } }
        void f(String n, float v) { U x = uu(n); if (x.n == 1 && x.a == v) return; x.n = 1; x.a = v; if (vk != null) put(x, v, 0, 0, 0, 1); else GLES30.glUniform1f(x.loc, v); }
        void f2(String n, float a, float b) { U x = uu(n); if (x.n == 2 && x.a == a && x.b == b) return; x.n = 2; x.a = a; x.b = b; if (vk != null) put(x, a, b, 0, 0, 2); else GLES30.glUniform2f(x.loc, a, b); }
        void f3(String n, float a, float b, float c) { U x = uu(n); if (x.n == 3 && x.a == a && x.b == b && x.c == c) return; x.n = 3; x.a = a; x.b = b; x.c = c; if (vk != null) put(x, a, b, c, 0, 3); else GLES30.glUniform3f(x.loc, a, b, c); }
        void f4(String n, float a, float b, float c, float d) { U x = uu(n); if (x.n == 4 && x.a == a && x.b == b && x.c == c && x.d == d) return; x.n = 4; x.a = a; x.b = b; x.c = c; x.d = d; if (vk != null) put(x, a, b, c, d, 4); else GLES30.glUniform4f(x.loc, a, b, c, d); }
        void c3(String n, float[] c) { f3(n, c[0], c[1], c[2]); }
        void m4(String n, float[] m) {
            U x = uu(n);
            if (x.m != null && java.util.Arrays.equals(x.m, m)) return;
            if (x.m == null) x.m = new float[16];
            System.arraycopy(m, 0, x.m, 0, 16);
            if (vk == null) { GLES30.glUniformMatrix4fv(x.loc, 1, false, m, 0); return; }
            if (x.vo >= 0) { System.arraycopy(m, 0, vu, x.vo, 16); vd = true; }
            if (x.fo >= 0) { System.arraycopy(m, 0, fu, x.fo, 16); fd = true; }
        }
        void tex(String n, int unit, int tex) {
            if (vk != null) { U x = uu(n); if (x.ti >= 0) tx[x.ti] = tex; return; }
            bindTex(unit, tex); U x = uu(n); if (x.i != unit) { x.i = unit; GLES30.glUniform1i(x.loc, unit); }
        }
    }

    // ================= 网格 =================
    /** 一个 VAO：属性按 layout(location = i) 依次排（sizes 是每个属性几个分量），交错存放 */
    static final class Mesh {
        int vao, vbo, ibo, count, mode = GLES30.GL_TRIANGLES;
        boolean indexed;
        final Vk vk; // Vulkan 时 vao 是原生那边的编号

        Mesh(float[] data, int[] sizes, short[] index) {
            vk = ST.get().vk;
            if (vk != null) {
                vao = vk.mesh(data, sizes, index);
                int stride = 0;
                for (int s : sizes) stride += s;
                indexed = index != null;
                count = index != null ? index.length : data.length / stride;
                return;
            }
            int[] b = new int[2];
            GLES30.glGenVertexArrays(1, b, 0);
            vao = b[0];
            bindVao(vao);
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
            bindVao(0);
        }

        private FloatBuffer upd; // 换顶点数据用的缓冲（长度不变，建一次反复用）

        /** 换顶点数据（长度不变：光点每次重新瞄准） */
        void update(float[] data) {
            if (vk != null) { vk.meshData(vao, data); return; }
            if (upd == null || upd.capacity() != data.length) upd = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            upd.position(0);
            upd.put(data).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, data.length * 4, upd);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        void draw() {
            if (vk != null) { vk.draw(this, mode == GLES30.GL_POINTS); return; }
            bindVao(vao);
            if (indexed) GLES30.glDrawElements(mode, count, GLES30.GL_UNSIGNED_SHORT, 0);
            else GLES30.glDrawArrays(mode, 0, count);
        }

        void free() {
            if (vk != null) { vk.meshFree(vao); return; }
            St s = ST.get();
            if (s.vao == vao) s.vao = 0; // 删掉正绑着的 VAO，GL 会退回 0
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
        if (x < r * 1e-3f) { // 正圆：两个半圆的头尾重合，去掉重复的点（挤出时零长的边会出缺口）
            float[] o = new float[2 * seg * 2];
            for (int i = 0; i < 2 * seg; i++) { float a = (float) (-Math.PI / 2 + Math.PI * i / seg); o[i * 2] = r * (float) Math.cos(a); o[i * 2 + 1] = r * (float) Math.sin(a); }
            return o;
        }
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
        Vk v = ST.get().vk;
        if (v != null) return v.tex(b, mip);
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
        Vk v = ST.get().vk;
        if (v != null) { v.texData(tex, b); return; }
        bindTex(0, tex);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
    }

    static void free(int tex) {
        if (tex == 0) return;
        Vk v = ST.get().vk;
        if (v != null) v.texFree(tex);
        else { GLES30.glDeleteTextures(1, new int[] { tex }, 0); forget(tex); }
    }

    // ================= 状态（GL 时就是对应的那一个调用；Vulkan 时记下来，画的时候按它挑管线） =================
    static void depthTest(boolean on) { Vk v = ST.get().vk; if (v != null) v.flag(Vk.ST_DTEST, on); else if (on) GLES30.glEnable(GLES30.GL_DEPTH_TEST); else GLES30.glDisable(GLES30.GL_DEPTH_TEST); }
    static void depthMask(boolean on) { Vk v = ST.get().vk; if (v != null) v.flag(Vk.ST_DWRITE, on); else GLES30.glDepthMask(on); }
    static void cull(boolean on) { Vk v = ST.get().vk; if (v != null) v.flag(Vk.ST_CULL, on); else if (on) GLES30.glEnable(GLES30.GL_CULL_FACE); else GLES30.glDisable(GLES30.GL_CULL_FACE); }
    /** 剔除背面（Vulkan 只有这一种） */
    static void cullBack() { if (ST.get().vk == null) GLES30.glCullFace(GLES30.GL_BACK); }
    /** 预乘透明度的混合（ONE, ONE_MINUS_SRC_ALPHA）开 / 关 */
    static void blendPremul(boolean on) { blend(on ? BLEND_PREMUL : BLEND_OFF); }
    /** 混合方式：不混、预乘盖上（ONE, 1-SA）、挖掉（ZERO, 1-SA）、整体减（ONE, ONE，反向减：雨珠的雾珠图蒸发） */
    static final int BLEND_OFF = 0, BLEND_PREMUL = 1, BLEND_ERASE = 2, BLEND_EVAP = 3;
    static void blend(int mode) {
        St s = ST.get();
        if (s.vk != null) { s.vk.blend(mode); return; }
        if (mode == BLEND_OFF) { GLES30.glDisable(GLES30.GL_BLEND); return; }
        GLES30.glEnable(GLES30.GL_BLEND);
        boolean rev = mode == BLEND_EVAP;
        if (rev != s.revEq) { GLES30.glBlendEquation(rev ? GLES30.GL_FUNC_REVERSE_SUBTRACT : GLES30.GL_FUNC_ADD); s.revEq = rev; }
        if (mode == BLEND_EVAP) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        else GLES30.glBlendFunc(mode == BLEND_ERASE ? GLES30.GL_ZERO : GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** 离屏缓冲 src 的颜色整块拷到 dst（一样大） */
    static void copy(Fbo src, Fbo dst) {
        Vk v = ST.get().vk;
        if (v != null) { v.copy(src.tex, dst.tex); return; }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, src.fb);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, dst.fb);
        GLES30.glBlitFramebuffer(0, 0, src.w, src.h, 0, 0, src.w, src.h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** 空的 RGB565 贴图 w × h，levels 级缩小图（歌单的封面图集；内容一块块往里传，见 sub） */
    static int atlas(int w, int h, int levels) {
        Vk v = ST.get().vk;
        if (v != null) return v.atlas(w, h, levels);
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        bindTex(0, t[0]);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGB565, w, h);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Vulkan：实例化绘制（雨珠）：m 是每顶点的（四个角），inst 每实例 8 个数（两个 vec4），n 个 */
    static void drawInstanced(Mesh m, float[] inst, int n) { Vk v = ST.get().vk; if (v != null) v.draw(m, false, inst, n); }

    /** 往贴图 tex 的第 level 级 (x, y) 处传一块位图 */
    static void sub(int tex, int level, int x, int y, Bitmap b) {
        Vk v = ST.get().vk;
        if (v != null) { v.texSub(tex, level, x, y, b); return; }
        bindTex(0, tex);
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, level, x, y, b);
    }
    /** 画到屏幕上（w × h） */
    static void screen(int w, int h) { Vk v = ST.get().vk; if (v != null) v.bind(0); else { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, w, h); } }
    static void clearColor(float r, float g, float b, float a) { Vk v = ST.get().vk; if (v != null) v.clearColor(r, g, b, a); else GLES30.glClearColor(r, g, b, a); }
    static void clear(boolean color, boolean depth) {
        Vk v = ST.get().vk;
        if (v != null) v.clear(color, depth);
        else GLES30.glClear((color ? GLES30.GL_COLOR_BUFFER_BIT : 0) | (depth ? GLES30.GL_DEPTH_BUFFER_BIT : 0));
    }
    /** 屏幕的多重采样倍数（0 / 1 是没开） */
    static int samples() { Vk v = ST.get().vk; if (v != null) return v.samples; int[] a = new int[1]; GLES30.glGetIntegerv(GLES30.GL_SAMPLES, a, 0); return a[0]; }

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
            Vk v = ST.get().vk;
            if (v != null) { fb = tex = v.target(w, h, withDepth, samples); return; } // Vulkan：颜色贴图的编号就是这块缓冲的编号
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

        void bind() { Vk v = ST.get().vk; if (v != null) { v.bind(tex); return; } GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFb != 0 ? msFb : fb); GLES30.glViewport(0, 0, w, h); }

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
            Vk v = ST.get().vk;
            if (v != null) { if (tex != 0) v.texFree(tex); fb = tex = 0; w = h = 0; return; }
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

    /** out = a × b（直接在 Java 里算：系统的 Matrix.multiplyMM 每次都要过 JNI 拷三个数组；out 可以就是 a 或 b） */
    static void mul(float[] out, float[] a, float[] b) {
        float a00 = a[0], a10 = a[1], a20 = a[2], a30 = a[3], a01 = a[4], a11 = a[5], a21 = a[6], a31 = a[7],
            a02 = a[8], a12 = a[9], a22 = a[10], a32 = a[11], a03 = a[12], a13 = a[13], a23 = a[14], a33 = a[15];
        for (int j = 0; j < 16; j += 4) {
            float b0 = b[j], b1 = b[j + 1], b2 = b[j + 2], b3 = b[j + 3];
            out[j] = a00 * b0 + a01 * b1 + a02 * b2 + a03 * b3;
            out[j + 1] = a10 * b0 + a11 * b1 + a12 * b2 + a13 * b3;
            out[j + 2] = a20 * b0 + a21 * b1 + a22 * b2 + a23 * b3;
            out[j + 3] = a30 * b0 + a31 * b1 + a32 * b2 + a33 * b3;
        }
    }

    /** 点 (x, y, z) 乘矩阵（带透视除法）→ out[0..2] */
    static void project(float[] out, float[] m, float x, float y, float z) {
        float w = m[3] * x + m[7] * y + m[11] * z + m[15];
        out[0] = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w;
        out[1] = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w;
        out[2] = (m[2] * x + m[6] * y + m[10] * z + m[14]) / w;
    }
}
