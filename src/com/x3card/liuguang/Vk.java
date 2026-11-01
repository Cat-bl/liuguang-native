package com.x3card.liuguang;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Vulkan 后端（设置"渲染方式"选 Vulkan 时）：G 在画画线程里绑着一个 Vk 时，GL 的那些调用（程序、uniform、贴图、网格、离屏缓冲、状态、绘制）都交到这里：
 * uniform 写进每个程序自己的一份块（vu / fu，布局见打包时生成的 vk.json），绘制时把块拷进这一帧的 uniform 数据、记一条命令；
 * 一帧画完（end）整串命令和 uniform 数据一次交给原生代码（lgvk.cpp）录命令缓冲、提交、显示。
 * 命令（整数）：PASS 目标 清什么 r g b a；DRAW 程序 状态 顶点块偏移 片元块偏移 贴图×4 网格 是不是点 有没有每实例数据 它的偏移 实例数；
 * CLEAR 清什么 r g b a；COPY 从哪块离屏缓冲 拷到哪块。
 */
final class Vk {
    static final boolean LOADED;
    static {
        boolean ok = false;
        try { System.loadLibrary("liuguang"); ok = true; } catch (Throwable t) { Log.w(Uiplus.TAG, "Vulkan：原生库载不进来 " + t); }
        LOADED = ok;
    }

    private static native long nCreate(Surface s, AssetManager am, String cacheDir, int samples);
    private static native int[] nInfo(long c);
    private static native boolean nSurface(long c, Surface s);
    private static native void nSurfaceLost(long c);
    private static native boolean nResize(long c, boolean force, int w, int h);
    private static native int nProg(long c, String vs, String fs);
    private static native int nMesh(long c, float[] data, int[] sizes, short[] index);
    private static native void nMeshData(long c, int id, float[] data);
    private static native void nMeshFree(long c, int id);
    private static native int nTex(long c, Bitmap b, boolean mip);
    private static native void nTexData(long c, int id, Bitmap b);
    private static native void nTexFree(long c, int id);
    private static native int nTarget(long c, int w, int h, boolean depth, int samples);
    private static native void nBegin(long c);
    private static native int nFrame(long c, int[] cmd, int n, float[] ubo, int nu, float[] ins, int ni);
    private static native int nAtlas(long c, int w, int h, int levels);
    private static native void nTexSub(long c, int id, int level, int x, int y, Bitmap b);
    private static native void nDestroy(long c);

    /** 起不来过一次（这个进程里不再试，用 OpenGL） */
    static volatile boolean failed;

    /** 能不能用 Vulkan：原生库载进来了、系统说有 Vulkan、这个进程里没起失败过 */
    static boolean supported(android.content.Context c) {
        return LOADED && !failed && c.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
    }

    // ================= 着色器的 uniform 布局（打包时生成，按原文的键查） =================
    static final class Stage {
        final int size;
        final Map<String, Integer> off = new HashMap<>();
        final String[] samplers;
        Stage(JSONObject o) throws Exception {
            size = o.getInt("s");
            JSONArray u = o.getJSONArray("u");
            for (int i = 0; i < u.length(); i++) { JSONArray m = u.getJSONArray(i); off.put(m.getString(0), m.getInt(2) / 4); }
            JSONArray t = o.getJSONArray("t");
            samplers = new String[t.length()];
            for (int i = 0; i < samplers.length; i++) samplers[i] = t.getString(i);
        }
        int sampler(String n) { for (int i = 0; i < samplers.length; i++) if (samplers[i].equals(n)) return i; return -1; }
    }
    private static Map<String, Stage> stages;

    private static synchronized Map<String, Stage> stages(AssetManager am) {
        if (stages != null) return stages;
        Map<String, Stage> m = new HashMap<>();
        try (InputStream in = am.open("vk.json")) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject o = new JSONObject(b.toString("UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, new Stage(o.getJSONObject(k))); }
        } catch (Exception e) { Log.w(Uiplus.TAG, "Vulkan：读不了 vk.json " + e); }
        return stages = m;
    }

    /** 着色器原文的键（同打包脚本 vk/shaders.mjs 的 keyOf） */
    static String key(String src) { return Integer.toHexString(src.hashCode()) + "_" + src.length(); }

    // ================= 一个画布一份 =================
    private final long c;
    private final AssetManager am;
    final int align, samples;

    private Vk(long c, AssetManager am) {
        this.c = c;
        this.am = am;
        int[] in = nInfo(c);
        align = Math.max(1, in[0] / 4);
        samples = in[1];
    }

    /** 建实例、设备、交换链；不行返回 null */
    static Vk create(Surface s, AssetManager am, String cacheDir, int samples) {
        long c = nCreate(s, am, cacheDir, samples);
        return c == 0 ? null : new Vk(c, am);
    }

    boolean surface(Surface s) { return nSurface(c, s); }
    void surfaceLost() { nSurfaceLost(c); }
    /** w × h：画布要的大小（SurfaceView 交来的） */
    boolean resize(boolean force, int w, int h) { return nResize(c, force, w, h); }
    void destroy() { nDestroy(c); }

    // ---------------- 资源 ----------------
    /** 程序：返回原生的编号，顺便把两段的布局交给 p */
    int prog(String vs, String fs, G.Prog p) {
        String kv = key(vs), kf = key(fs);
        Map<String, Stage> st = stages(am);
        p.vsI = st.get(kv);
        p.fsI = st.get(kf);
        if (p.vsI == null || p.fsI == null) Log.w(Uiplus.TAG, "Vulkan：vk.json 里没有这段着色器 " + kv + " / " + kf);
        return nProg(c, kv, kf);
    }
    int mesh(float[] data, int[] sizes, short[] index) { return nMesh(c, data, sizes, index); }
    void meshData(int id, float[] data) { nMeshData(c, id, data); }
    void meshFree(int id) { nMeshFree(c, id); }
    int tex(Bitmap b, boolean mip) { return nTex(c, b, mip); }
    void texData(int id, Bitmap b) { nTexData(c, id, b); }
    void texFree(int id) { nTexFree(c, id); }
    int target(int w, int h, boolean depth, int s) { return nTarget(c, w, h, depth, s); }
    int atlas(int w, int h, int levels) { return nAtlas(c, w, h, levels); }
    void texSub(int id, int level, int x, int y, Bitmap b) { nTexSub(c, id, level, x, y, b); }

    // ---------------- 一帧 ----------------
    static final int ST_BLEND = 3, ST_DTEST = 4, ST_DWRITE = 8, ST_CULL = 16; // 低两位是混合方式（见 G.blend）
    private int[] cmd = new int[4096];
    private int cn;
    private float[] ubo = new float[16384];
    private int un;
    private float[] ins = new float[4096]; // 每实例数据（雨珠）
    private int inn;
    int frame;
    int state = ST_DWRITE; // GL 的初始状态：混合、深度测试、剔除都关着，写深度开着
    G.Prog prog;
    private int pending = -1, pendFlags, passes; // 绑了、还没开始的目标（第一次画或清的时候才开）
    private boolean screenDone;
    private float cr, cg, cb, ca = 1;

    void begin() { cn = 0; un = 0; inn = 0; frame++; pending = -1; pendFlags = 0; passes = 0; screenDone = false; nBegin(c); }

    /** 画完一帧：交给原生代码；返回 0 好了、1 交换链要重建、-1 出错 */
    int end() {
        flushPending();
        if (!screenDone) { bind(0); clear(true, true); flushPending(); } // 这一帧没画到屏幕上：也要显示一帧（清成黑的）
        return nFrame(c, cmd, cn, ubo, un, ins, inn);
    }

    void flag(int bit, boolean on) { state = on ? state | bit : state & ~bit; }
    void blend(int mode) { state = (state & ~ST_BLEND) | mode; }

    /** 整块拷：离屏缓冲 src 的颜色拷到 dst（在渲染流程外面做，拷完原来的流程结束，接着画要重新绑） */
    void copy(int src, int dst) {
        flushPending();
        pending = -1;
        room(3);
        cmd[cn++] = 4; cmd[cn++] = src; cmd[cn++] = dst;
    }
    void clearColor(float r, float g, float b, float a) { cr = r; cg = g; cb = b; ca = a; }

    void bind(int target) { flushPending(); pending = target; pendFlags = 0; }

    void clear(boolean color, boolean depth) {
        int fl = (color ? 1 : 0) | (depth ? 2 : 0);
        if (pending >= 0) { pendFlags |= fl; return; }
        room(6);
        cmd[cn++] = 3; cmd[cn++] = fl;
        cmd[cn++] = Float.floatToRawIntBits(cr); cmd[cn++] = Float.floatToRawIntBits(cg); cmd[cn++] = Float.floatToRawIntBits(cb); cmd[cn++] = Float.floatToRawIntBits(ca);
    }

    /** 绑着的目标还没开始：清了的照样开（只清不画也要做），没清的等第一次画 */
    private void flushPending() { if (pending >= 0 && pendFlags != 0) open(); }

    private void open() {
        room(7);
        cmd[cn++] = 1; cmd[cn++] = pending; cmd[cn++] = pendFlags;
        cmd[cn++] = Float.floatToRawIntBits(cr); cmd[cn++] = Float.floatToRawIntBits(cg); cmd[cn++] = Float.floatToRawIntBits(cb); cmd[cn++] = Float.floatToRawIntBits(ca);
        if (pending == 0) screenDone = true;
        pending = -1; pendFlags = 0; passes++;
    }

    void draw(G.Mesh m, boolean points) { draw(m, points, null, 0); }

    /** inst 不是 null：实例化绘制，每实例 8 个数（两个 vec4），n 个实例 */
    void draw(G.Mesh m, boolean points, float[] inst, int n) {
        G.Prog p = prog;
        if (p == null) return;
        if (pending >= 0) open();
        int vo = 0, fo = 0;
        if (p.vu != null) { if (p.vd || p.seen != frame) { p.vAt = put(p.vu); p.vd = false; } vo = p.vAt; }
        if (p.fu != null) { if (p.fd || p.seen != frame) { p.fAt = put(p.fu); p.fd = false; } fo = p.fAt; }
        p.seen = frame;
        int io = 0;
        if (inst != null) {
            if (inn + n * 8 > ins.length) ins = java.util.Arrays.copyOf(ins, Math.max(ins.length * 2, inn + n * 8));
            System.arraycopy(inst, 0, ins, inn, n * 8);
            io = inn;
            inn += n * 8;
        }
        room(14);
        cmd[cn++] = 2; cmd[cn++] = p.id; cmd[cn++] = state; cmd[cn++] = vo * 4; cmd[cn++] = fo * 4;
        cmd[cn++] = p.tx[0]; cmd[cn++] = p.tx[1]; cmd[cn++] = p.tx[2]; cmd[cn++] = p.tx[3];
        cmd[cn++] = m.vao; cmd[cn++] = points ? 1 : 0;
        cmd[cn++] = inst != null ? 1 : 0; cmd[cn++] = io * 4; cmd[cn++] = n;
    }

    /** 一个 uniform 块拷进这一帧的数据（按设备要求的对齐）：返回起点（按 float 数） */
    private int put(float[] u) {
        int at = (un + align - 1) / align * align;
        if (at + u.length > ubo.length) ubo = java.util.Arrays.copyOf(ubo, Math.max(ubo.length * 2, at + u.length));
        System.arraycopy(u, 0, ubo, at, u.length);
        un = at + u.length;
        return at;
    }

    private void room(int n) { if (cn + n > cmd.length) cmd = java.util.Arrays.copyOf(cmd, cmd.length * 2); }
}
