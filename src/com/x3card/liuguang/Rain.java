package com.x3card.liuguang;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 玻璃上的雨珠（设置里开，默认关；同网页版 main.js 的 RAIN）：像隔着一块淋过雨的玻璃看。
 * 先把水画成一张"水图"（每个像素存：有没有水、表面朝哪边、水珠多大），再整屏合成一遍——有水的地方透过水珠看后面
 * （倒过来、按水珠大小偏移），边缘暗、迎光处亮，没水的地方原样（场景不模糊，照常看得清）。
 * - 水图里的每颗水珠是柔边的（中间实、往外慢慢淡），合成时按"够不够实"一刀切：挨得近的几颗会连成一块不规则的水；
 *   底下略胖、上头略尖，每颗随机扁一点、歪一点。
 * - 细小的雾珠不一颗颗算：直接印在一张一直留着的"雾珠图"上（雨一直在下，每秒落几十颗），雾珠图整体慢慢蒸发变淡，不会越积越多。
 * - 大一点的水珠单独模拟：雨落上来、碰到就并进去长大；隔 8～16 秒挑一颗大的先慢慢鼓起来再滑下来，一顿一顿地走、左右晃，
 *   吞掉路上的水珠、擦掉路上的雾珠，身后在雾珠图上拖出一条细细的水痕（之后慢慢蒸发）、偶尔留下一颗小水珠。
 * - 打开时从干玻璃开始下：头几秒雨点落得密、越来越稀；关上时水珠一起缩小消失，干了再收。
 * 场景照常画完后整屏拷下来（开着抗锯齿时顺便解析多重采样）；只有水珠在动时（暂停着、场景不动）
 * 直接用拷下来的画面合成，不重画场景。水图、雾珠图按 CSS 像素大小；每帧：雾珠图上印几颗 + 擦 / 拖水痕 + 隔一会儿蒸发一次，
 * 雾珠图拷进水图再叠上大水珠（一次实例化绘制），整屏合成一遍。只在 GL 线程里用
 */
final class Rain {
    private static final int MAX = 480, MIST = 256, TRAIL = 64, ERASE = 64;
    private static final float RMAX = 0.045f;

    private static final class Drop {
        float x, y, r, rT, a = 1, sp, vy, pause, wob, trailAt, lx, ly, sx, sy, rot;
        boolean die, slide, ripe;
    }

    private final List<Drop> drops = new ArrayList<>();
    private float W = 1, H = 1, dpr = 1, next, acc, mistAcc, mistEarly, evap, fade = 1;
    private int sliders;
    private boolean on, active, valid, fresh;

    // ================= 模拟（单位：CSS 像素，左上原点，y 朝下） =================
    private static float rnd() { return (float) Math.random(); }

    private int target() { return Math.round(Math.max(60, Math.min(300, W * H / 2200))); }

    /** 大一点的水珠：大多三四个像素，少数很大的 */
    private float size0() { float x = rnd(); return rnd() < 0.5f ? H * (0.004f + 0.004f * x) : H * (0.008f + 0.018f * (float) Math.pow(x, 2.4)); }

    private Drop make(float x, float y, float r, boolean grow) {
        Drop d = new Drop();
        d.x = d.lx = x; d.y = d.ly = d.trailAt = y; d.rT = r; d.r = grow ? r * 0.6f : r; d.a = grow ? 0 : 1; d.sp = grow ? 1 : 0;
        d.wob = rnd() * 6.28f; d.sx = 0.75f + rnd() * 0.4f; d.sy = 1 + rnd() * 0.4f; d.rot = (rnd() - 0.5f) * 0.6f;
        return d;
    }

    /** 打开：从干玻璃开始下起来（已经在下就接着下）/ 关上：水珠缩小消失、场景慢慢变清楚，干了再收 */
    void toggle(boolean v, float time) {
        if (v && !on) {
            if (!active) {
                drops.clear(); sliders = 0; acc = 0; mistAcc = 0; fade = 1;
                mistEarly = W * H / 420; next = time + 5 + rnd() * 3;
            }
            on = active = true;
        } else if (!v && on) {
            on = false;
            for (Drop d : drops) if (d.ripe) { d.ripe = false; sliders--; }
        }
    }

    /** 开着，或者关上了还在慢慢干 */
    boolean on() { return active; }
    float next() { return next; }
    int count() { return drops.size(); }
    boolean valid() { return valid && copy.fb != 0; }

    /** 印一颗雾珠到雾珠图上（下一次画的时候一起印） */
    private void mist(float x, float y, float a) { float u = rnd(); stamp.add(x, y, 0.6f + 2.6f * u * u, a, 0.8f + rnd() * 0.4f, 0.85f + rnd() * 0.45f, (rnd() - 0.5f) * 0.8f, 0.08f); }

    /** 落上来一颗大一点的：落在已有的水珠上就并进去（大水珠是这么慢慢长大的），不然就是一颗新的（很快长到原来大小，像雨点打上来） */
    private void land() {
        float x = rnd() * W, y = rnd() * H, r = size0();
        for (Drop o : drops) {
            if (o.slide || o.ripe || o.die) continue;
            float dx = o.x - x, dy = o.y - y, rr = o.rT + r * 0.6f;
            if (dx * dx + dy * dy < rr * rr) { o.rT = Math.min(H * 0.03f, (float) Math.sqrt(o.rT * o.rT + r * r)); return; }
        }
        if (drops.size() < MAX) drops.add(make(x, y, r, true));
    }

    /** 一帧：雨一直在下（大的每颗平均一分半钟换一批，雾珠每秒几十颗），挂着的大水珠多了随便干掉一颗；
     *  隔 8～16 秒挑一颗大的先鼓起来再滑；大小变化都平滑过渡（不凭空变大）。刚打开时玻璃是干的，雨点先落得密、越来越稀。
     *  返回还在不在动（关上后干透了就收掉、返回 false） */
    boolean tick(float dt, float time) {
        if (!active) return false;
        evap += dt;
        int n = 0;
        for (int i = drops.size() - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            if (d.die) { d.a -= dt / 1.5f; if (d.a <= 0) { drops.remove(i); continue; } }
            else if (d.a < 1) d.a = Math.min(1, d.a + dt * 8);
            if (d.sp > 0) d.sp = d.sp < 0.01f ? 0 : d.sp * (float) Math.exp(-dt * 7);
            if (d.r != d.rT) { d.r += (d.rT - d.r) * Math.min(1, dt * (d.ripe ? 1.2f : 9)); if (Math.abs(d.rT - d.r) < 0.05f) d.r = d.rT; }
            if (!d.slide && !d.die) n++;
        }
        int want = target();
        if (on) {
            float dry = Math.max(0, 1 - (float) n / want);
            acc += dt * want / 90 * (1 + 24 * dry * dry); // 玻璃还干着时落得密
            while (acc >= 1) { acc--; land(); }
            mistAcc += dt * W * H / 3500; // 雾珠
            float early = mistEarly * Math.min(1, dt / 1.5f);
            mistEarly -= early;
            mistAcc += early;
            while (mistAcc >= 1 && stamp.n < MIST) { mistAcc--; mist(rnd() * W, rnd() * H, 0.4f + rnd() * 0.6f); }
            if (n > want && !drops.isEmpty()) { Drop d = drops.get((int) (rnd() * drops.size())); if (!d.slide && !d.ripe && !d.die) d.die = true; } // 多了随便干掉一颗（慢慢干掉）
            // 隔 8～16 秒挑一颗大的：先慢慢鼓起来，鼓够了再滑（同时最多两颗）
            if (time >= next && sliders < 2 && !drops.isEmpty()) {
                Drop o = null;
                for (int i = 0; i < 16; i++) {
                    Drop d = drops.get((int) (rnd() * drops.size()));
                    if (!d.slide && !d.ripe && !d.die && d.y < H * 0.7f && (o == null || d.r > o.r)) o = d;
                }
                if (o != null) { o.ripe = true; o.rT = Math.max(o.rT, H * (0.02f + rnd() * 0.01f)); sliders++; }
                next = time + 8 + rnd() * 8;
            }
            fade = Math.min(1, fade + dt / 0.6f);
        } else {
            fade = Math.max(0, fade - dt / 1.2f);
        }
        for (int i = drops.size() - 1; i >= 0; i--) {
            if (i >= drops.size()) continue;
            Drop d = drops.get(i);
            if (d.ripe) {
                if (d.r >= d.rT * 0.97f) { d.ripe = false; d.slide = true; d.vy = 0; d.trailAt = d.ly = d.y; d.lx = d.x; d.rot = 0; d.sx = d.sy = 1; }
                continue;
            }
            if (!d.slide) continue;
            if (d.pause > 0) d.pause -= dt;
            else {
                d.vy = Math.min(d.vy + H * 0.8f * dt, H * (0.12f + d.r / H * 8));
                d.y += d.vy * dt;
                d.x += (float) Math.sin(time * 2.7f + d.wob) * H * 0.014f * dt;
                if (rnd() < dt * 0.7f) { d.pause = 0.12f + rnd() * 0.45f; d.vy *= 0.25f; } // 一顿一顿地走
            }
            // 从上一帧的位置到这一帧一路擦掉雾珠、拖出水痕（帧率低时也连得上）
            float gx = d.x - d.lx, gy = d.y - d.ly;
            int steps = Math.min(24, Math.max(1, (int) Math.ceil(Math.sqrt(gx * gx + gy * gy) / (d.r * 0.2f))));
            for (int j = 1; j <= steps; j++) {
                float px = d.lx + gx * j / steps, py = d.ly + gy * j / steps;
                erase.add(px, py + d.r * 0.2f, d.r * 1.05f, 1, 1, 1, 0, 0); // 擦的范围在水痕起点下面，不会把刚拖出的水痕擦掉
                trail.add(px, py - d.r * 0.9f, d.r * (0.17f + 0.06f * (float) Math.sin(py * 0.15f + d.wob)), 0.95f, 1, 3, 0, d.r / (H * RMAX) * 0.35f);
            }
            d.lx = d.x; d.ly = d.y;
            if (d.y - d.trailAt > d.r * 4 && drops.size() < MAX && rnd() < 0.5f) { // 偶尔留下一颗小水珠
                d.trailAt = d.y;
                drops.add(make(d.x + (rnd() - 0.5f) * d.r * 0.3f, d.y - d.r * (1.3f + rnd() * 0.5f), d.r * (0.22f + rnd() * 0.16f), false));
                d.rT *= 0.98f;
            }
            for (int j = drops.size() - 1; j >= 0; j--) { // 碰到下面的水珠就吞进来
                Drop o = drops.get(j);
                if (o == d || o.slide || o.ripe || o.die || o.y < d.y) continue;
                float dx = o.x - d.x, dy = o.y - d.y, rr = d.r + o.r * 0.7f;
                if (dx * dx + dy * dy < rr * rr) {
                    d.rT = Math.min(H * RMAX, (float) Math.sqrt(d.rT * d.rT + o.r * o.r));
                    drops.remove(j);
                    if (j < i) i--;
                }
            }
            if (d.y - d.r * 2 > H) { drops.remove(d); sliders--; }
        }
        if (!on && fade <= 0) { active = false; drops.clear(); sliders = 0; stamp.n = trail.n = erase.n = 0; freeGL(); return false; } // 关上：玻璃干了才收
        return true;
    }

    /** 雾珠图：换尺寸时重新印一层（深浅不一，像已经下了一会儿） */
    private void prefill() {
        int n = Math.round(W * H / 420);
        for (int i = 0; i < n; i++) { mist(rnd() * W, rnd() * H, 0.4f + rnd() * 0.6f); if (stamp.n >= MIST) stamp.flush(mistFb); }
        stamp.flush(mistFb);
    }

    // ================= GL =================
    // 实例化的一批水珠：aD 中心（CSS 像素，左上原点）、半径、浓淡；aS 横向、纵向拉长、转角、大小（0～1，合成时决定透镜偏多少）
    private static final String V_INST = "layout(location = 0) in vec2 q; layout(location = 1) in vec4 aD; layout(location = 2) in vec4 aS;\n"
        + "uniform vec2 uView; out vec2 vP; out float vS; out float vA;\n"
        + "void main() {\n"
        + "  vec2 e = vec2(q.x * aS.x, q.y * aS.y) * aD.z, c = vec2(cos(aS.z), sin(aS.z));\n"
        + "  e = vec2(c.x * e.x - c.y * e.y, c.y * e.x + c.x * e.y);\n"
        + "  vec2 px = aD.xy + vec2(e.x, -e.y);\n"
        + "  vP = q; vS = aS.w; vA = aD.w;\n"
        + "  gl_Position = vec4(px.x / uView.x * 2.0 - 1.0, 1.0 - px.y / uView.y * 2.0, 0.0, 1.0);\n"
        + "}";
    // 一颗水珠写进水图：rg 表面朝向、b 大小、a 浓（都按 a 预乘，叠在一起时朝向按浓淡平均）
    private static final String F_DROP = "in vec2 vP; in float vS; in float vA; out vec4 o;\n"
        + "void main() {\n"
        + "  vec2 p = vec2(vP.x / (1.0 + 0.22 * max(-vP.y, 0.0)), vP.y * (vP.y < 0.0 ? 0.92 : 1.06));\n" // 底下略胖、上头略尖
        + "  float d2 = dot(p, p); if (d2 >= 1.0) discard;\n"
        + "  float a = pow(1.0 - d2, 0.75) * vA;\n"
        + "  o = vec4(vec3(p * 0.5 + 0.5, vS) * a, a);\n"
        + "}";
    // 水痕：沿竖直方向拉长的一段，表面只朝左右（像一根横躺的水柱，比水珠平，边上不压暗），一段段叠起来是一条顺滑的线；两头淡出
    private static final String F_TRAIL = "in vec2 vP; in float vS; in float vA; out vec4 o;\n"
        + "void main() { float x2 = vP.x * vP.x; if (x2 >= 1.0) discard; float a = pow(1.0 - x2, 0.75) * smoothstep(1.0, 0.5, abs(vP.y)) * vA; o = vec4(vec3(vP.x * 0.3 + 0.5, 0.5, vS) * a, a); }";
    private static final String F_ERASE = "in vec2 vP; in float vS; in float vA; out vec4 o; void main() { float d2 = dot(vP, vP); if (d2 >= 1.0) discard; o = vec4((1.0 - d2 * d2) * vA); }";
    private static final String V_FULL = "layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; out vec2 vUv; void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }";
    // 雾珠图蒸发：每次整体减一点（朝向那两路减一半，保持"朝向 / 浓淡"差不多不变）
    private static final String F_EVAP = "uniform float uE; out vec4 o; void main() { o = vec4(uE * 0.5, uE * 0.5, uE * 0.25, uE); }";
    // 合成：有水的地方透过水珠看后面（倒过来、按大小偏移）；明暗照真水珠：朝光那边边上暗、里面一个小高光，背光那边边上亮（光透出来）
    private static final String F_COMPOSE = "uniform sampler2D uScene, uWater; uniform highp vec2 uRes; uniform highp float uLens; uniform float uDay, uFade; in highp vec2 vUv; out vec4 o;\n"
        + "void main() {\n"
        + "  vec3 base = texture(uScene, vUv).rgb;\n"
        + "  vec4 w = texture(uWater, vUv) * uFade;\n" // 关上时整体变淡：过了一刀切的都缩小消失
        + "  float cov = smoothstep(0.3, 0.42, w.a);\n"
        + "  if (cov <= 0.0) { o = vec4(base, 1.0); return; }\n"
        + "  vec3 q = w.rgb / max(w.a, 0.004); vec2 n = q.xy * 2.0 - 1.0; float s = q.z, l = length(n);\n"
        + "  highp vec2 off = n * (0.06 + s) * uLens / uRes;\n"
        + "  vec3 c = texture(uScene, vUv - off).rgb;\n" // 每颗都是小透镜：里面是后面倒过来、缩小的像，越大的水珠偏得越多
        + "  c = (c - 0.5) * 1.12 + 0.51;\n" // 水珠里的像对比稍强
        + "  vec2 L = vec2(-0.6, 0.8); float lit = dot(n / max(l, 0.001), L), rim = smoothstep(0.5, 0.95, l);\n" // 光从左上来
        + "  c *= 1.0 - rim * (0.2 + 0.6 * max(lit, 0.0)) * mix(0.85, 0.65, uDay);\n" // 朝光那边边上一道暗的月牙（后面的影子映在前壁上），背光那边淡
        + "  c += rim * max(-lit, 0.0) * 0.3 + max(-dot(n, L), 0.0) * 0.07;\n" // 背光那边边上一道亮的月牙（光从里面透出来），那半边整体亮一点
        + "  c += smoothstep(0.17, 0.05, length(n - L * 0.5)) * 0.85;\n" // 朝光那边里面一个小而亮的高光
        + "  o = vec4(mix(base, c, cov), 1.0);\n"
        + "}";

    /** 一批实例：每颗 aD（4）+ aS（4），一次实例化绘制。kind：0 水珠、1 水痕、2 擦掉 */
    private static final class Batch {
        final int max, kind;
        final float[] buf;
        final FloatBuffer fb;
        int n, vao, vbo;
        private final Rain rain;

        Batch(Rain rain, int max, int kind) {
            this.rain = rain; this.max = max; this.kind = kind;
            buf = new float[max * 8];
            fb = ByteBuffer.allocateDirect(max * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }

        void add(float x, float y, float r, float a, float sx, float sy, float rot, float s) {
            if (n >= max) return;
            int o = n++ * 8;
            buf[o] = x; buf[o + 1] = y; buf[o + 2] = r; buf[o + 3] = a;
            buf[o + 4] = sx; buf[o + 5] = sy; buf[o + 6] = rot; buf[o + 7] = s;
        }

        void initGL(int corners) {
            int[] b = new int[1];
            GLES30.glGenVertexArrays(1, b, 0);
            vao = b[0];
            GLES30.glBindVertexArray(vao);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, corners);
            GLES30.glEnableVertexAttribArray(0);
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
            GLES30.glGenBuffers(1, b, 0);
            vbo = b[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, buf.length * 4, null, GLES30.GL_DYNAMIC_DRAW);
            for (int i = 1; i <= 2; i++) {
                GLES30.glEnableVertexAttribArray(i);
                GLES30.glVertexAttribPointer(i, 4, GLES30.GL_FLOAT, false, 32, (i - 1) * 16);
                GLES30.glVertexAttribDivisor(i, 1);
            }
            GLES30.glBindVertexArray(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        }

        /** 画进 fbo（水珠、水痕按预乘"盖上去"，擦的是按浓淡挖掉） */
        void flush(G.Fbo fbo) {
            if (n == 0) return;
            if (vao == 0 || fbo.fb == 0) { n = 0; return; }
            fb.position(0);
            fb.put(buf, 0, n * 8).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 8 * 4, fb);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            fbo.bind();
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(kind == 2 ? GLES30.GL_ZERO : GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            G.Prog p = kind == 0 ? rain.pDrop : kind == 1 ? rain.pTrail : rain.pErase;
            p.use();
            p.f2("uView", rain.W, rain.H);
            GLES30.glBindVertexArray(vao);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 6, n);
            GLES30.glBindVertexArray(0);
            n = 0;
        }
    }

    private final Batch stamp = new Batch(this, MIST, 0), trail = new Batch(this, TRAIL, 1), erase = new Batch(this, ERASE, 2), big = new Batch(this, MAX, 0);
    private G.Prog pDrop, pTrail, pErase, pEvap, pCompose;
    private G.Mesh mFull;
    private final G.Fbo copy = new G.Fbo(false), mistFb = new G.Fbo(false), waterFb = new G.Fbo(false);
    private int corners;

    /** GL 环境建好（第一次、或者丢了重建）：旧编号都作废 */
    void initGL() {
        copy.forget(); mistFb.forget(); waterFb.forget();
        valid = false;
        if (active) fresh = true;
        pDrop = new G.Prog(V_INST, F_DROP);
        pTrail = new G.Prog(V_INST, F_TRAIL);
        pErase = new G.Prog(V_INST, F_ERASE);
        pEvap = new G.Prog(V_FULL, F_EVAP);
        pCompose = new G.Prog(V_FULL, F_COMPOSE);
        mFull = G.quad(-1, -1, 1, 1);
        int[] b = new int[1];
        GLES30.glGenBuffers(1, b, 0);
        corners = b[0];
        float[] c = { -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1 };
        FloatBuffer f = ByteBuffer.allocateDirect(c.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        f.put(c).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, corners);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, c.length * 4, f, GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
        stamp.initGL(corners); trail.initGL(corners); erase.initGL(corners); big.initGL(corners);
    }

    /** 画面尺寸变了：水珠按比例挪，拷贝缓冲换尺寸；雨已经下着时雾珠图重新印满 */
    void size(float w, float h, int bw0, int bh0, float dpr0) {
        float kx = w / W, ky = h / H;
        for (Drop d : drops) { d.x *= kx; d.y *= ky; d.lx *= kx; d.ly *= ky; d.r *= ky; d.rT *= ky; d.trailAt *= ky; }
        W = w; H = h; dpr = dpr0;
        if (copy.fb != 0) copy.size(bw0, bh0);
        if (mistFb.fb != 0) { mistFb.free(); waterFb.free(); fresh = true; }
        valid = false;
    }

    /** 场景画完：整屏拷下来给合成用（开着抗锯齿时顺便解析多重采样） */
    void grab(int bw0, int bh0) {
        copy.size(bw0, bh0);
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, copy.fb);
        GLES30.glBlitFramebuffer(0, 0, bw0, bh0, 0, 0, bw0, bh0, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        valid = true;
    }

    /** 更新雾珠图、拼水图、整屏合成（场景没重画时也一样：用上次拷下来的） */
    void draw(float day, int bw0, int bh0) {
        if (copy.fb == 0) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        int mw = Math.max(8, Math.round(W)), mh = Math.max(8, Math.round(H));
        if (mistFb.fb == 0 || mistFb.w != mw || mistFb.h != mh) {
            mistFb.size(mw, mh);
            waterFb.size(mw, mh);
            mistFb.bind();
            GLES30.glClearColor(0, 0, 0, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glClearColor(0, 0, 0, 1);
        }
        if (fresh) { fresh = false; prefill(); }
        if (evap >= 0.25f) { // 隔一会儿蒸发一次（8 位颜色每次要减够一格）
            evap = 0;
            mistFb.bind();
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendEquation(GLES30.GL_FUNC_REVERSE_SUBTRACT);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            pEvap.use();
            pEvap.f("uE", 3 / 255f);
            mFull.draw();
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        }
        erase.flush(mistFb);
        stamp.flush(mistFb);
        trail.flush(mistFb);
        // 雾珠图拷进水图，再叠上大水珠
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, mistFb.fb);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, waterFb.fb);
        GLES30.glBlitFramebuffer(0, 0, mw, mh, 0, 0, mw, mh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        float s = 1 / (H * RMAX);
        for (int i = 0; i < drops.size(); i++) {
            Drop d = drops.get(i);
            float st = d.slide ? 1 + Math.min(0.5f, d.vy / (H * 0.8f)) : 1;
            // 刚落上来摊开：横向宽、纵向扁，慢慢收回去
            big.add(d.x, d.y, d.r, Math.max(0, d.a), d.sx / (float) Math.sqrt(st) * (1 + 0.5f * d.sp), st * d.sy / d.sx * (1 - 0.3f * d.sp), d.rot, Math.min(1, d.r * s));
        }
        big.flush(waterFb);
        // 整屏合成
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, bw0, bh0);
        GLES30.glDisable(GLES30.GL_BLEND);
        pCompose.use();
        pCompose.tex("uScene", 0, copy.tex);
        pCompose.tex("uWater", 1, waterFb.tex);
        pCompose.f2("uRes", bw0, bh0);
        pCompose.f("uLens", H * RMAX * dpr * 2.2f);
        pCompose.f("uDay", day);
        pCompose.f("uFade", fade);
        mFull.draw();
        G.activeUnit(0);
    }

    /** 收掉：拷贝缓冲、雾珠图、水图都释放（整屏几块，不用就别占着） */
    void freeGL() {
        copy.free(); mistFb.free(); waterFb.free();
        valid = false;
    }
}
