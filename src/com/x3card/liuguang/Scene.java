package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 琉光的 3D 场景（OpenGL ES 3.0），照网页版 lyric3d/main.js 一段段搬过来的：
 * 封面颜色做成缓慢流动的极光背景，场景最深处挂一张大的封面；封面是一块侧着的厚玻璃砖，立在水面上方；
 * 歌词贴在横放的圆柱上（像 iOS 的滚轮），横屏时整列绕自己中心的竖直轴转 30° 朝向封面；当前句整句亮起（宿主只给每句开头的时间）；
 * 水面是真的平面倒影（镜像一台相机），封面、歌词、光带、背景封面、控制条都按真实的高度和远近照进水里；
 * 控制条是一块浮在水面上的玻璃，播放 / 暂停图形会变形，播放键外圈是进度，"上一首"左边是收藏红心；上下翻歌词时中间出现定位线，点右边的播放键从那一句开始放
 * （收藏、跳到某句宿主都没有：收藏状态从车机的媒体控制器读、点了发给「多媒体」，跳到某句也发给「多媒体」，都是零权限，见 BydMedia、Player）。
 * 开场、换歌：光点从封面流出聚成歌词。所有动作都是弹簧算的。
 * 歌单（点封面打开）：封面砖变成一排立在水面上的玻璃砖，见 Shelf。
 * 单位同网页版：场景里 1 = 1 个 CSS 像素（设备像素 ÷ 屏幕密度），画布按密度 1.5 画（同网页版的像素比上限）。
 * 只在 GL 线程里跑：主线程把媒体快照、深浅色、设置交进来（字体等到下一帧在 GL 线程里换），手势用 queueEvent。停着不动不出帧，帧率按设置（自动时 60 帧画不过来降到 30 帧）。
 */
final class Scene implements GLSurfaceView.Renderer {
    interface Host {
        void press(String cmd);   // previous / toggle / next / heart
        void seek(long ms);       // 点了歌词定位线上的播放键：从那一句开始放
        void btnWait(boolean w);  // 开场动画还没走完：右上角的键先藏着
        void playIndex(int i);    // 歌单里点了第几首
        void shelfState(boolean open); // 歌单开了 / 关了（歌单键亮不亮、返回键关歌单）
        void locate(int dir, float y); // 正在放的那首跑出画面了：dir -1 在左边、1 在右边、0 不显示；y 是砖的中间在画面上多高（0～1）
        void coverAt(float l, float t, float r, float b); // 新手引导开着：封面在画面上的范围（占画面的比例）
    }

    final Shelf shelf = new Shelf(this);

    final SceneView view;
    final Host host;
    private final float dens;
    final float DPR;

    Scene(SceneView view, Host host, float density) {
        this.view = view;
        this.host = host;
        this.dens = density;
        this.DPR = Math.min(density, 1.5f);
        Matrix.setIdentityM(I, 0); // 主画面的相机在原点、朝 -z 看
    }

    // ================= 小工具 =================
    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static float smooth(float a, float b, float v) { float t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static void lerp3(float[] out, float[] a, float[] b, float t) { for (int i = 0; i < 3; i++) out[i] = a[i] + (b[i] - a[i]) * t; }
    private static final class Rnd { int s; Rnd(int seed) { s = seed % 2147483647; if (s <= 0) s = 1; } float next() { s = (int) ((s * 16807L) % 2147483647); return (s - 1) / 2147483646f; } }

    // ================= 主线程交进来的 =================
    private volatile MediaState.Snap MS = new MediaState.Snap();
    private boolean dayWanted = false, ready = false;

    void setSnap(MediaState.Snap s) { MS = s; wake(); }
    void setDay(boolean d) { dayWanted = d; DAY.t = d ? 1 : 0; wake(); }
    /** 车机「多媒体」的播放队列和正在放的是第几首（BydMedia） */
    void setSongs(List<BydMedia.Song> s, int cur) { shelf.setSongs(s, cur); wake(); }
    void setCovers(SongCovers c) { shelf.art = c; }
    /** 歌单：1 打开、2 关上、3 开 / 关、4 返回（先收搜索键盘再关） */
    void shelf(int r) { shelf.request(r); wake(); }
    /** 歌单里回到正在放的那首 */
    void shelfLocate() { shelf.locate(); wake(); }

    // ================= 设置（主线程交进来；同网页版的 lg-settings） =================
    private volatile boolean stWater = true, stArt = true, stRibbons = true, stIntro = true, stReveal = true, stRain, rainToggle;
    private volatile String fpsMode = "60", wantFont = "smiley", fontNow = null;

    /** 下不下雨珠（主线程按设置、天气、这首歌定好交进来） */
    void setRain(boolean on) { if (on != stRain) { stRain = on; rainToggle = true; } wake(); }

    void setOpts(Settings s) {
        stWater = s.water; stArt = s.art; stRibbons = s.ribbons; stIntro = s.intro; stReveal = s.reveal;
        if (!s.fps.equals(fpsMode)) { fpsMode = s.fps; fpsReset = true; }
        wantFont = s.font;
        wake();
    }

    // ================= 画面尺寸、相机 =================
    static final float D = 10;
    private static final float FOV = 34;
    float W = 1, H = 1, wpp = 1, dpx = 1;
    private int bw = 1, bh = 1;
    final float[] P = new float[16], S = new float[16], I = new float[16];
    private float sRx, sRy;

    // ================= 深浅色：所有材质共用（0 夜、1 昼），切换时整体慢慢过渡 =================
    private final Spring DAY = new Spring(0, 30, 11, 0.001f);
    float day;
    final float[] INK_N = { 1, 1, 1 }, INK_D = { 0.075f, 0.085f, 0.12f }, ink = { 1, 1, 1 }, hi = { 1, 1, 1 };

    // ================= 当前这首 =================
    private String title = "", artist = "";
    private String[] lines = new String[0];
    private long dur;
    Bitmap cover;
    Palette pal, playPal; // playPal：正在放的这首的颜色（歌单里预览别的歌时 pal 是那首的）
    String coverTitle = ""; // 封面是哪首的（歌名）：歌单里正在放的那块用场景的封面，要对得上
    private int seenTrack = -1, seenLines = -1, seenCover = -1;

    private int[] lyricState() {
        if (pend != null) return pend.st; // 换了歌、封面还没认准：滚轮上还是上一首，停在原来那句
        if (MS.lines.length == 0) return lastSt = new int[] { 0, 1 };
        return lastSt = new int[] { (int) clamp(MS.cur, 0, lines.length - 1), MS.cur >= 0 ? 1 : 0 };
    }

    /** 换了歌、等这首的封面认准了再翻面（见 syncMedia） */
    private static final class Pend { int dir; float at; int[] st; }
    private Pend pend;
    private int[] lastSt = { 0, 1 };

    private String[] songLines() {
        if (MS.lines.length > 0) return MS.lines.clone();
        if (MS.on) return new String[] { "♪ " + (MS.title.isEmpty() ? "未知歌曲" : MS.title) };
        return new String[] { "还没有在放的歌" };
    }

    private void songText() {
        title = MS.on ? MS.title : "琉光";
        artist = MS.on ? MS.artist : MS.canPlay ? "点下面的播放键，或者打开音乐播放器" : "打开音乐播放器，歌词会出现在这里";
        dur = MS.dur;
    }

    // ================= GL 对象 =================
    G.Prog pAur, pBg, pRib, pWater, pGlass, pHalo, pArt, pLine, pText, pPill, pIcons, pSeek, pDust, pPart;
    G.Mesh mFull, mUnit, mStrip, mGlass, mHalo, mPill, mDust, mPart;
    final G.Fbo aurFbo = new G.Fbo(false), waterFbo = new G.Fbo(true);
    /** 封面贴图：按 Bitmap 记，只留正在用的几张；GL 环境丢了全部重传 */
    private final Map<Bitmap, Integer> covers = new HashMap<>();
    int texFront, texFront2, texBack;
    private Bitmap bFront, bFront2, bBack;

    int tex(Bitmap b) {
        if (b == null) return 0;
        Integer t = covers.get(b);
        if (t == null) { t = G.texture(b, true); covers.put(b, t); }
        return t;
    }

    /** 不用的封面贴图释放掉 */
    private void gcCovers() {
        Iterator<Map.Entry<Bitmap, Integer>> it = covers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Bitmap, Integer> e = it.next();
            Bitmap b = e.getKey();
            if (b != bFront && b != bFront2 && b != bBack && !isArt(b) && b != cover && !shelf.keeps(b)) { G.free(e.getValue()); it.remove(); }
        }
    }

    // ================= 着色器 =================
    private static final String V_FULL = "layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; out vec2 vUv; void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }";
    static final String V_UV = "layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; uniform mat4 uP, uMV; out vec2 vUv; void main() { vUv = uv; gl_Position = uP * uMV * vec4(position, 1.0); }";
    // 封面、控制条的玻璃和背景封面按"在主画面上的位置"取后面的极光（vS = 主相机的投影 × 世界坐标，uSM 在 drawScene 里给）：
    // 画倒影时也取主画面上同一处的，倒影的颜色和本体一样（按倒影自己在屏幕上的位置取的话，倒影常常是另一种颜色、看着没颜色）
    private static final String V_SOLID = "layout(location = 0) in vec3 position; layout(location = 1) in vec3 normal; uniform mat4 uP, uMV, uSM; out vec3 vP, vN, vVN, vV; out highp vec4 vS;"
        + " void main() { vP = position; vN = normal; vVN = normalize(mat3(uMV) * normal); vec4 mv = uMV * vec4(position, 1.0); vV = mv.xyz; vS = uSM * vec4(position, 1.0); gl_Position = uP * mv; }";
    private static final String V_UVS = "layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; uniform mat4 uP, uMV, uSM; out vec2 vUv; out highp vec4 vS;"
        + " void main() { vUv = uv; vS = uSM * vec4(position, 1.0); gl_Position = uP * uMV * vec4(position, 1.0); }";

    private static final String F_AUR = "uniform highp float uT; uniform float uAsp, uK, uDay; uniform vec3 uC1, uC2, uC3; in vec2 vUv; out vec4 o;\n"
        + "float blob(vec2 p, vec2 c, float r) { vec2 d = p - c; return exp(-dot(d, d) / (r * r)); }\n"
        + "float curtain(vec2 p, float base, float amp, float f, float sp, float ph, float rays) {\n"
        + "  highp float y = base + amp * sin(p.x * f + uT * sp + ph) + amp * 0.45 * sin(p.x * f * 2.3 - uT * sp * 1.4 + ph * 1.7);\n"
        + "  float d = p.y - y;\n"
        + "  highp float r = 0.55 + 0.45 * sin(p.x * rays + sin(p.x * 3.1 + uT * 0.35 + ph) * 3.0);\n"
        + "  return (d > 0.0 ? exp(-d * 5.0) : exp(-d * d * 180.0)) * r;\n"
        + "}\n"
        + "void main() {\n"
        + "  vec2 p = vec2((vUv.x - 0.5) * uAsp, vUv.y - 0.5); highp float t = uT;\n"
        + "  float b1 = blob(p, vec2(sin(t * 0.13) * 0.36 * uAsp, cos(t * 0.11) * 0.22 + 0.06), 0.46);\n"
        + "  float b2 = blob(p, vec2(cos(t * 0.09 + 1.0) * 0.42 * uAsp, sin(t * 0.12 + 2.0) * 0.26 - 0.05), 0.4);\n"
        + "  float b3 = blob(p, vec2(sin(t * 0.07 + 4.0) * 0.3 * uAsp, sin(t * 0.1 + 0.5) * 0.3 - 0.16), 0.34);\n"
        + "  vec3 cur = mix(uC2, uC3, 0.5 + 0.5 * sin(p.x * 1.4 + t * 0.2)) * curtain(p, 0.1, 0.09, 2.1, 0.21, 0.0, 34.0) * 0.32\n"
        + "           + mix(uC1, uC2, 0.5 + 0.5 * sin(p.x * 1.9 - t * 0.17)) * curtain(p, -0.12, 0.07, 2.9, -0.16, 2.4, 52.0) * 0.18;\n"
        + "  vec3 night = vec3(0.012, 0.014, 0.026) + uC1 * b1 * 0.52 + uC2 * b2 * 0.46 + uC3 * b3 * 0.4 + cur;\n"
        + "  float w = clamp(b1 * 0.7 + b2 * 0.6 + b3 * 0.55, 0.0, 0.9);\n"
        + "  vec3 tint = (uC1 * b1 + uC2 * b2 + uC3 * b3) / max(b1 + b2 + b3, 0.001);\n"
        + "  vec3 dayc = mix(vec3(0.95, 0.955, 0.975), mix(tint, vec3(1.0), 0.42), w) + cur * 0.35;\n"
        + "  vec3 c = mix(night, dayc, uDay);\n"
        + "  o = vec4(mix(vec3(uDay), c, uK), 1.0);\n"
        + "}";

    private static final String F_BG = "uniform sampler2D uAur; uniform vec2 uShift; uniform float uDay; in vec2 vUv; out vec4 o;\n"
        + "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n"
        + "void main() {\n"
        + "  vec3 c = texture(uAur, vUv * 0.94 + 0.03 + uShift).rgb;\n"
        + "  float v = length((vUv - 0.5) * vec2(1.1, 1.0));\n"
        + "  c *= 1.0 - smoothstep(0.32, 0.95, v) * mix(0.6, 0.14, uDay);\n"
        + "  c += (hash(gl_FragCoord.xy) - 0.5) / 255.0 * 1.6;\n"
        + "  o = vec4(c, 1.0);\n"
        + "}";

    private static final String V_RIB = "layout(location = 0) in vec3 position; uniform mat4 uP, uMV; uniform highp float uT; uniform float uLen, uW, uY, uZ, uAmp, uPh, uTw; out float vX, vY;\n"
        + "void main() {\n"
        + "  highp float s = position.x, t = uT;\n"
        + "  float y = uY + sin(s * 3.1 + t * 0.23 + uPh) * uAmp + sin(s * 7.3 - t * 0.37 + uPh) * uAmp * 0.22;\n"
        + "  float z = uZ + cos(s * 2.3 + t * 0.19 + uPh) * uAmp * 2.4;\n"
        + "  float tw = sin(s * 3.7 + t * 0.29 + uPh) * uTw;\n"
        + "  vec3 p = vec3(s * uLen, y + position.y * uW * cos(tw), z + position.y * uW * sin(tw));\n"
        + "  vX = s; vY = position.y;\n"
        + "  gl_Position = uP * uMV * vec4(p, 1.0);\n"
        + "}";
    private static final String F_RIB = "uniform vec3 uC1, uC2; uniform float uK, uDay; in float vX, vY; out vec4 o;\n"
        + "void main() {\n"
        + "  float e = max(1.0 - abs(vY) * 2.0, 0.0);\n"
        + "  float a = pow(e, 1.6) * (1.0 - smoothstep(0.32, 0.5, abs(vX))) * uK;\n"
        + "  float lines = 0.7 + 0.3 * sin(vY * 46.0 + vX * 6.0);\n"
        + "  vec3 c = mix(uC1, uC2, vX + 0.5);\n"
        + "  o = vec4(c * a * lines * mix(0.42, 0.3, uDay) + vec3(0.06) * pow(e, 12.0) * a * (1.0 - uDay), a * lines * 0.22 * uDay);\n"
        + "}";

    private static final String V_WATER = "layout(location = 0) in vec3 position; uniform mat4 uP, uMV, uTexM; uniform vec2 uSize; out vec4 vR; out vec2 vP; out vec3 vV, vN;\n"
        + "void main() { vR = uTexM * vec4(position, 1.0); vP = position.xy * uSize; vec4 mv = uMV * vec4(position, 1.0); vV = mv.xyz; vN = normalize(mat3(uMV) * vec3(0.0, 0.0, 1.0)); gl_Position = uP * mv; }";
    private static final String F_WATER = "uniform sampler2D uRefl; uniform highp float uT; uniform float uK, uDay; uniform vec3 uTint; in vec4 vR; in vec2 vP; in vec3 vV, vN; out vec4 o;\n"
        + "void main() {\n"
        + "  highp vec2 p = vP * 0.011; highp float t = uT;\n"
        + "  vec2 n = vec2(cos(p.x * 0.9 + p.y * 0.5 + t * 0.8), sin(p.y * 1.2 - t * 1.0)) * 0.5\n"
        + "         + vec2(cos(p.x * 2.6 - p.y * 1.8 - t * 1.6), sin(p.x * 1.1 + p.y * 2.7 + t * 1.4)) * 0.28\n"
        + "         + vec2(sin(p.x * 5.1 + p.y * 0.7 + t * 2.4), cos(p.y * 5.9 - p.x * 0.9 - t * 2.1)) * 0.14;\n"
        + "  float dist = -vV.z;\n"
        + "  vec2 uv = vR.xy / vR.w + n * 0.016 * clamp(9.0 / dist, 0.2, 1.0);\n"
        + "  vec3 refl = texture(uRefl, uv).rgb * 0.5 + (texture(uRefl, uv + vec2(0.0, 0.005)).rgb + texture(uRefl, uv - vec2(0.0, 0.005)).rgb) * 0.25;\n"
        + "  float c = clamp(dot(normalize(vN), normalize(-vV)), 0.0, 1.0);\n"
        + "  float fres = 0.1 + 0.9 * pow(1.0 - c, 4.0);\n"
        + "  vec3 deep = mix(uTint * 0.05 + vec3(0.004, 0.008, 0.014), mix(uTint, vec3(0.8, 0.84, 0.9), 0.75), uDay);\n"
        + "  vec3 col = mix(deep, refl * (0.88 + 0.16 * n.y), clamp(0.3 + fres * 0.72, 0.0, 0.94));\n"
        + "  float lum = dot(refl, vec3(0.3, 0.59, 0.11));\n"
        + "  col += pow(max(n.y * 0.5 + 0.4, 0.0), 10.0) * lum * mix(0.8, 0.25, uDay);\n"
        + "  float a = (1.0 - smoothstep(13.0, 30.0, dist)) * uK;\n"
        + "  o = vec4(col * a, a);\n"
        + "}";

    // uRF / uRF2 / uRB：正面、正面叠的那张、背面各取贴图的哪一块（歌单的砖用封面图集里的一格，场景的封面是整张）；uArt：正面、背面的封面显示多少（0 是空玻璃）
    private static final String F_GLASS = "uniform sampler2D uFront, uFront2, uBack, uAur; uniform float uDim, uSheen, uHalf, uDay, uFMix; uniform highp vec4 uRF, uRF2, uRB; uniform vec2 uArt; uniform vec3 uTint; in vec3 vP, vN, vVN, vV; in highp vec4 vS; out vec4 o;\n"
        + "void main() {\n"
        + "  vec3 N = normalize(vVN), V = normalize(-vV), L = normalize(vec3(-0.45, 0.75, 0.55));\n"
        + "  float fres = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 3.0);\n"
        + "  float spec = pow(max(dot(reflect(-L, N), V), 0.0), 60.0);\n"
        + "  vec2 suv = vS.xy / vS.w * 0.5 + 0.5; vec3 col;\n"
        + "  if (abs(vN.z) > 0.9) {\n"
        + "    vec2 uv = vec2(vN.z > 0.0 ? vP.x : -vP.x, vP.y) / uHalf * 0.5 + 0.5;\n"
        + "    highp vec2 tv = vec2(uv.x, 1.0 - uv.y);\n"
        + "    vec3 art = vN.z > 0.0 ? mix(texture(uFront, uRF.xy + tv * uRF.zw).rgb, texture(uFront2, uRF2.xy + tv * uRF2.zw).rgb, uFMix) : texture(uBack, uRB.xy + tv * uRB.zw).rgb;\n"
        + "    float ak = vN.z > 0.0 ? uArt.x : uArt.y;\n"
        + "    float e = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));\n"
        + "    vec3 refr = texture(uAur, suv + N.xy * 0.03).rgb * mix(1.6, 1.05, uDay) + uTint * 0.12;\n"
        + "    col = mix(refr, art * uDim, smoothstep(0.004, 0.03, e) * ak);\n"
        + "    col += vec3(exp(-pow(uv.x * 0.8 + uv.y * 0.5 - uSheen, 2.0) * 26.0) * 0.1) + spec * 0.5;\n"
        + "  } else {\n"
        + "    col = texture(uAur, suv + N.xy * 0.08).rgb * mix(1.8, 1.08, uDay) + uTint * 0.14 + fres * mix(0.6, 0.35, uDay) + spec * 0.9;\n"
        + "  }\n"
        + "  o = vec4(col, 1.0);\n"
        + "}";

    private static final String F_HALO = "uniform vec3 uTint; uniform float uK, uDay; in vec2 vUv; out vec4 o;\n"
        + "float sdRR(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
        + "void main() {\n"
        + "  vec2 p = (vUv - 0.5) * 1.9;\n"
        + "  float sh = (1.0 - smoothstep(-0.06, 0.26, sdRR(p - vec2(0.0, -0.07), vec2(0.48), 0.06))) * mix(0.6, 0.3, uDay);\n"
        + "  float dH = sdRR(p, vec2(0.5), 0.06), glow = exp(-max(dH, 0.0) * 7.0) * step(0.0, dH) * 0.32 * (1.0 - uDay * 0.7);\n"
        + "  o = vec4(uTint * glow * uK, sh * uK);\n"
        + "}";

    // 背景封面最多三张一起混，uW 是每张的权重（加起来是 1）
    private static final String F_ART = "uniform sampler2D uA, uB, uC; uniform vec3 uW; uniform float uNarrow, uK, uDay; in vec2 vUv; in highp vec4 vS; out vec4 o;\n"
        + "void main() {\n"
        + "  vec2 tv = vec2(vUv.x, 1.0 - vUv.y);\n"
        + "  vec3 c = texture(uA, tv, 1.8).rgb * uW.x + texture(uB, tv, 1.8).rgb * uW.y + texture(uC, tv, 1.8).rgb * uW.z;\n"
        + "  vec2 s = vS.xy / vS.w * 0.5 + 0.5;\n"
        + "  float lyr = mix(smoothstep(0.38, 0.62, s.x), smoothstep(0.78, 0.48, s.y) * smoothstep(0.12, 0.36, s.y), uNarrow);\n"
        + "  float e = smoothstep(0.0, 0.2, vUv.x) * smoothstep(1.0, 0.8, vUv.x) * smoothstep(0.0, 0.2, vUv.y) * smoothstep(1.0, 0.8, vUv.y);\n"
        + "  vec3 night = c * 0.46 * (1.0 - 0.45 * lyr);\n"
        + "  vec3 dayc = mix(mix(c, vec3(1.0), 0.5), vec3(0.97), 0.45 * lyr);\n"
        + "  float al = e * uK * mix(0.8, 0.62, uDay);\n"
        + "  o = vec4(mix(night, dayc, uDay) * al, al);\n"
        + "}";

    private static final String V_LINE = "layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; uniform mat4 uP, uMV; uniform float uAct, uLift; out vec2 vUv;"
        + " void main() { vUv = uv; vec3 p = position; p.y += uLift * uAct; gl_Position = uP * uMV * vec4(p, 1.0); }";
    private static final String F_LINE = "uniform sampler2D uMap; uniform float uAlpha, uBlur, uPulse, uDay; uniform highp float uAct; uniform vec3 uTint, uInk, uHi; in vec2 vUv; out vec4 o;\n"
        + "void main() {\n"
        + "  vec3 t = texture(uMap, vec2(vUv.x, 1.0 - vUv.y)).rgb; float b = uBlur;\n"
        + "  float a = b < 0.5 ? mix(t.r, t.g, b * 2.0) : mix(t.g, t.b * 1.25, b * 2.0 - 1.0);\n"
        + "  float g = t.b * (uPulse * 0.9 + uAct * 0.14);\n"
        + "  float al = a * uAlpha;\n"
        + "  vec3 inkc = mix(uInk, uHi, uAct);\n"
        + "  o = vec4(inkc * al + uTint * g * uAlpha * (1.0 - uDay * 0.5), al + g * uAlpha * mix(0.08, 0.5, uDay));\n"
        + "}";
    // 歌词定位线：一条两头渐隐的细线（uGap 那段是对着的那句歌词，空出来）+ 右头的播放键（强调色的圆、白色三角）
    private static final String F_SEEK = "uniform vec2 uSize, uLine, uGap; uniform float uBx, uR, uPress, uK, uDay; uniform vec3 uInk, uAcc; in vec2 vUv; out vec4 o;\n"
        + "float sdTri(vec2 p, float r) { const float k = 1.7320508; p.x = abs(p.x) - r; p.y = p.y + r / k;\n"
        + "  if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0; p.x -= clamp(p.x, -2.0 * r, 0.0); return -length(p) * sign(p.y); }\n"
        + "float fill(float d) { float w = fwidth(d) * 0.75; return 1.0 - smoothstep(-w, w, d); }\n"
        + "void main() {\n"
        + "  vec2 p = (vUv - 0.5) * uSize;\n"
        + "  float t = clamp((p.x - uLine.x) / max(uLine.y - uLine.x, 1.0), 0.0, 1.0);\n"
        + "  float gap = smoothstep(uGap.x - 6.0, uGap.x, p.x) * (1.0 - smoothstep(uGap.y, uGap.y + 6.0, p.x));\n"
        + "  float ln = fill(abs(p.y) - 0.55) * smoothstep(0.0, 0.15, t) * (1.0 - smoothstep(0.85, 1.0, t)) * step(uLine.x, p.x) * step(p.x, uLine.y) * (1.0 - gap) * 0.5;\n"
        + "  vec2 c = (p - vec2(uBx, 0.0)) / uPress;\n"
        + "  float circ = fill(length(c) - uR) * 0.92;\n"
        + "  float tri = fill(sdTri(vec2(c.y, c.x - uR * 0.1), uR * 0.42) - uR * 0.05);\n"
        + "  vec3 accC = mix(uAcc, uAcc * 0.62, uDay);\n"
        + "  vec3 col = uInk * ln * (1.0 - circ) + mix(accC, vec3(1.0), tri) * circ;\n"
        + "  float al = ln * (1.0 - circ) + circ;\n"
        + "  o = vec4(col * uK, al * uK);\n"
        + "}";
    private static final String F_TEXT = "uniform sampler2D uMap; uniform float uA, uK; uniform vec3 uInk; in vec2 vUv; out vec4 o;"
        + " void main() { float a = texture(uMap, vec2(vUv.x, 1.0 - vUv.y)).a * uA * uK; o = vec4(uInk * a, a); }";

    private static final String F_PILL = "uniform sampler2D uAur; uniform vec2 uHalf; uniform vec3 uTint; uniform float uDay; in vec3 vP, vN, vVN, vV; in highp vec4 vS; out vec4 o;\n"
        + "void main() {\n"
        + "  vec3 N = normalize(vVN), V = normalize(-vV), L = normalize(vec3(-0.4, 0.8, 0.5));\n"
        + "  float fres = pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 3.0), spec = pow(max(dot(reflect(-L, N), V), 0.0), 50.0);\n"
        + "  vec2 suv = vS.xy / vS.w * 0.5 + 0.5;\n"
        + "  vec3 refr = texture(uAur, suv + N.xy * 0.05).rgb;\n"
        + "  float top = smoothstep(-0.2, 1.0, vP.y / uHalf.y);\n"
        + "  vec3 night = refr * 1.25 + uTint * 0.08 + vec3(0.05) + vec3(0.12) * top * top;\n"
        + "  vec3 dayc = mix(refr, vec3(1.0), 0.55) + uTint * 0.04 + vec3(0.06) * top;\n"
        + "  vec3 col = mix(night, dayc, uDay) + fres * mix(0.5, 0.3, uDay) + spec * 0.6;\n"
        + "  o = vec4(col, 1.0);\n"
        + "}";

    // uBx：上一首、下一首离中间多远；uHx：红心在哪（见 pillPlace）；uHeart：x 显示、y 实心（收藏了）、z 按下去的缩放
    private static final String F_ICONS = "uniform float uW, uH, uPlay, uProg, uK, uDay, uBx, uHx; uniform vec3 uPress, uInk, uAcc, uHeart; uniform sampler2D uTime; in vec2 vUv; out vec4 o;\n"
        + "float sdRB(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n"
        + "float sdHeart(vec2 p) { p.x = abs(p.x); if (p.y + p.x > 1.0) return length(p - vec2(0.25, 0.75)) - 0.35355339;\n"
        + "  vec2 a = p - vec2(0.0, 1.0), b = p - 0.5 * max(p.x + p.y, 0.0); return sqrt(min(dot(a, a), dot(b, b))) * sign(p.x - p.y); }\n"
        + "float sdTri(vec2 p, float r) { const float k = 1.7320508; p.x = abs(p.x) - r; p.y = p.y + r / k;\n"
        + "  if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0; p.x -= clamp(p.x, -2.0 * r, 0.0); return -length(p) * sign(p.y); }\n"
        + "float fill(float d) { float w = fwidth(d) * 0.75; return 1.0 - smoothstep(-w, w, d); }\n"
        + "void main() {\n"
        + "  vec2 p = (vUv - 0.5) * vec2(uW, uH); float h = uH;\n"
        + "  vec2 c = p / uPress.y; float r = h * 0.2;\n"
        + "  float dPlay = sdTri(vec2(c.y, c.x - r * 0.16), r) - r * 0.12;\n"
        + "  float dPause = min(sdRB(c - vec2(-r * 0.42, 0.0), vec2(r * 0.2, r * 0.82), r * 0.08), sdRB(c - vec2(r * 0.42, 0.0), vec2(r * 0.2, r * 0.82), r * 0.08));\n"
        + "  float d = mix(dPlay, dPause, uPlay);\n"
        + "  float bx = uBx, rs = h * 0.115;\n"
        + "  vec2 a = (p - vec2(-bx, 0.0)) / uPress.x, b = (p - vec2(bx, 0.0)) / uPress.z;\n"
        + "  float dPrev = min(sdTri(vec2(a.y, -(a.x + rs * 0.55)), rs), sdTri(vec2(a.y, -(a.x - rs * 0.75)), rs)) - rs * 0.1;\n"
        + "  float dNext = min(sdTri(vec2(b.y, b.x + rs * 0.75), rs), sdTri(vec2(b.y, b.x - rs * 0.55), rs)) - rs * 0.1;\n"
        + "  float inkA = max(fill(d), max(fill(dPrev), fill(dNext)));\n"
        + "  float R = h * 0.34, ring = abs(length(p) - R) - h * 0.022;\n"
        + "  float ang = atan(p.x, p.y) / 6.2831853; ang = ang < 0.0 ? ang + 1.0 : ang;\n"
        + "  float on = fill(ring) * step(ang, uProg), base = fill(ring) * 0.22;\n"
        + "  vec4 tm = texture(uTime, vec2(vUv.x, 1.0 - vUv.y));\n"
        + "  vec3 accC = mix(uAcc, uAcc * 0.62, uDay);\n"
        + "  float hs = h * 0.22 * uHeart.z, dH = sdHeart((p - vec2(uHx, -hs * 0.55)) / hs) * hs, hk = uHeart.x;\n"
        + "  float hf = fill(dH) * uHeart.y * hk, hl = fill(abs(dH) - h * 0.016) * (1.0 - uHeart.y) * hk * 0.95;\n"
        + "  float aInk = inkA * 0.95, aTm = tm.a * 0.62;\n"
        + "  vec3 col = uInk * (aInk + base + aTm + hl) + accC * (on + hf) * 1.1;\n"
        + "  float al = clamp(aInk + base + aTm + on + hl + hf, 0.0, 1.0);\n"
        + "  o = vec4(col * uK, al * uK);\n"
        + "}";

    private static final String V_DUST = "layout(location = 0) in vec3 position; layout(location = 1) in float aSeed; uniform mat4 uP, uMV; uniform highp float uT; uniform float uPx, uK; uniform vec2 uWH; out float vA;\n"
        + "void main() {\n"
        + "  highp float t = uT;\n"
        + "  vec3 p = vec3(position.x * uWH.x * 1.3, (fract(position.y + t * (0.004 + aSeed * 0.006)) - 0.5) * uWH.y * 1.3, position.z);\n"
        + "  p.x += sin(t * 0.3 + aSeed * 30.0) * 12.0;\n"
        + "  vec4 mv = uMV * vec4(p, 1.0);\n"
        + "  gl_PointSize = (1.2 + aSeed * 2.4) * uPx / -mv.z;\n"
        + "  vA = (0.25 + 0.5 * (0.5 + 0.5 * sin(t * (0.8 + aSeed) + aSeed * 40.0))) * uK;\n"
        + "  gl_Position = uP * mv;\n"
        + "}";
    private static final String F_DUST = "uniform float uDay; in float vA; out vec4 o;"
        + " void main() { vec2 d = gl_PointCoord - 0.5; float r = dot(d, d) * 4.0; if (r > 1.0) discard; float a = (1.0 - r) * (1.0 - r) * vA * (1.0 - uDay * 0.8); o = vec4(vec3(a), 0.0); }";

    private static final String V_PART = "layout(location = 0) in vec3 position; layout(location = 1) in vec3 aStart; layout(location = 2) in vec3 aCtl; layout(location = 3) in vec3 aD;"
        + " layout(location = 4) in vec3 aDay; layout(location = 5) in vec3 aNight; uniform mat4 uP, uMV, uHero; uniform float uT, uFade, uPx, uDay, uRel; uniform vec3 uEnd; out vec3 vC; out float vA;\n"
        + "void main() {\n"
        + "  float t = clamp((uT - aD.x) / 0.9, 0.0, 1.0);\n"
        + "  float e = t < 0.5 ? 4.0 * t * t * t : 1.0 - pow(-2.0 * t + 2.0, 3.0) * 0.5;\n"
        + "  vec3 st = mix(aStart, (uHero * vec4(aStart, 1.0)).xyz, uRel);\n"
        + "  vec3 p = mix(mix(st, aCtl, e), mix(aCtl, position, e), e);\n"
        + "  vec4 mv = uMV * vec4(p, 1.0);\n"
        + "  gl_PointSize = (2.0 + aD.y * 2.6) * (1.0 + (1.0 - e) * mix(1.8, 0.6, uDay)) * mix(1.0, 0.85, uDay) * uPx / -mv.z;\n"
        + "  vA = smoothstep(0.0, 0.12, t) * (1.0 - smoothstep(0.82, 1.0, t) * 0.85) * (1.0 - uFade) * (0.7 + 0.3 * aD.z);\n"
        + "  vC = mix(mix(aNight, aDay, uDay), uEnd, e * e * 0.55);\n"
        + "  gl_Position = uP * mv;\n"
        + "}";
    private static final String F_PART = "uniform highp float uDay; in vec3 vC; in float vA; out vec4 o;\n"
        + "void main() { vec2 d = gl_PointCoord - 0.5; float r = dot(d, d) * 4.0; if (r > 1.0) discard;\n"
        + "  float core = 1.0 - smoothstep(0.0, 0.3, r), halo = (1.0 - r) * (1.0 - r);\n"
        + "  float an = (halo * 0.75 + core * 0.6) * vA, ad = (smoothstep(1.0, 0.55, r) * 0.92) * vA;\n"
        + "  vec3 cn = vC * an + vec3(core * 0.35 * vA);\n"
        + "  o = vec4(mix(cn, vC * ad, uDay), ad * uDay);\n"
        + "}";

    // ================= GL 环境建好（第一次、或者环境丢了重建）：全部重新做 =================
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        G.resetCache(); // 新画布：GL 的状态全是初始的
        fontNow = wantFont;
        Txt.use(Txt.load(view.getContext(), fontNow));
        pAur = new G.Prog(V_FULL, F_AUR);
        pBg = new G.Prog("layout(location = 0) in vec3 position; layout(location = 1) in vec2 uv; out vec2 vUv; void main() { vUv = uv; gl_Position = vec4(position.xy, 0.999, 1.0); }", F_BG);
        pRib = new G.Prog(V_RIB, F_RIB);
        pWater = new G.Prog(V_WATER, F_WATER);
        pGlass = new G.Prog(V_SOLID, F_GLASS);
        pHalo = new G.Prog(V_UV, F_HALO);
        pArt = new G.Prog(V_UVS, F_ART);
        pLine = new G.Prog(V_LINE, F_LINE);
        pText = new G.Prog(V_UV, F_TEXT);
        pPill = new G.Prog(V_SOLID, F_PILL);
        pIcons = new G.Prog(V_UV, F_ICONS);
        pSeek = new G.Prog(V_UV, F_SEEK);
        pDust = new G.Prog(V_DUST, F_DUST);
        pPart = new G.Prog(V_PART, F_PART);
        mFull = G.quad(-1, -1, 1, 1);
        mUnit = G.quad(-0.5f, -0.5f, 0.5f, 0.5f);
        mHalo = G.quad(-0.95f, -0.95f, 0.95f, 0.95f);
        mStrip = G.strip(120);
        mGlass = G.extrude(G.roundRect(1 - BEV * 2, 1 - BEV * 2, 0.045f, 8), 0.1f, 0.03f, BEV, 4, 0.08f);
        mPill = null;
        pillKey = "";
        // 浮尘：240 个点（同网页版的随机数，位置一样）
        Rnd r = new Rnd(13);
        float[] dd = new float[240 * 4];
        for (int k = 0; k < 240; k++) { dd[k * 4] = r.next() - 0.5f; dd[k * 4 + 1] = r.next(); dd[k * 4 + 2] = -r.next() * 700 + 120; dd[k * 4 + 3] = r.next(); }
        mDust = G.points(dd, new int[] { 3, 1 });
        mPart = G.points(partData, new int[] { 3, 3, 3, 3, 3, 3 });
        shelf.initGL();
        // 环境丢了：贴图都没了，重新传；歌词按新环境重建
        // （旧编号都作废、不去删：新环境里同样的编号可能已经是刚建的东西）
        covers.clear();
        texFront = texFront2 = texBack = 0;
        texArt[0] = texArt[1] = texArt[2] = 0;
        aurFbo.forget(); waterFbo.forget();
        rain.initGL();
        timeTex = 0;
        seekTex = 0;
        mSeekLbl = null;
        seekText = "";
        LY.objs.clear();
        LY.leaving.clear();
        TT.cur = null;
        TT.old.clear();
        if (ready) { lyricsSong(clock, false); titleSong(false); }
        GLES30.glClearColor(0, 0, 0, 1);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        bw = Math.max(1, width);
        bh = Math.max(1, height);
        W = bw / DPR;
        H = bh / DPR;
        Matrix.perspectiveM(P, 0, FOV, W / H, 0.5f, 90);
        wpp = 2 * D * (float) Math.tan(FOV * Math.PI / 360) / H;
        dpx = D / wpp;
        aurFbo.size(Math.max(8, (int) Math.ceil(bw / 4f)), Math.max(8, (int) Math.ceil(bh / 4f)));
        waterFbo.size(Math.max(8, (int) Math.ceil(bw / 2f)), Math.max(8, (int) Math.ceil(bh / 2f)));
        rain.size(W, H, bw, bh, DPR);
        layout();
        if (ready) { measureSong(); lyricsSong(clock, false); titleSong(false); }
        wake();
    }

    // ================= 布局（同网页版：横屏封面在左、歌词在右转 30°；竖屏封面在上、歌词在下不转） =================
    boolean narrow, center;
    float C, water, cx, cy, ry, Fpx, maxW, px, py, R, fade0 = 0.85f, fade1 = 1.3f, yaw, tX, tY, tF, aF, tW, aY, gap, topY;
    private float pillX, pillW, pillH;

    private void layout() {
        narrow = W / H < 0.8f;
        if (!narrow) {
            C = H * 0.4f; water = -H * 0.21f; cx = -W / 2 + W * 0.08f + C / 2; cy = water + H * 0.035f + C / 2; ry = 0.42f;
            Fpx = H * 0.068f; maxW = W * 0.5f;
            px = W * 0.15f; py = H * 0.14f; R = H * 0.46f; yaw = (float) (-Math.PI / 6); center = true;
            tX = cx - C / 2; tY = H / 2 - H * 0.075f; tF = H * 0.05f; aF = H * 0.034f; tW = W * 0.4f; aY = tY - H * 0.06f;
            pillX = cx; pillW = Math.max(C * 1.12f, W * 0.34f); pillH = H * 0.1f;
        } else {
            C = H * 0.16f; cx = -W / 2 + W * 0.09f + C / 2; cy = H / 2 - H * 0.055f - C / 2; ry = 0.36f;
            Fpx = H * 0.058f; maxW = W * 0.84f;
            px = -W / 2 + W * 0.08f; py = H * 0.08f; R = H * 0.34f; water = -H * 0.3f; yaw = 0; center = false;
            tX = cx + C / 2 + W * 0.07f; tF = H * 0.04f; aF = H * 0.03f; tW = W / 2 - tX - W * 0.17f; tY = cy + C * 0.2f; aY = tY - H * 0.044f;
            pillX = 0; pillW = W * 0.8f; pillH = H * 0.1f;
        }
        gap = Fpx * 0.95f;
        topY = narrow ? cy - C / 2 - H * 0.03f : H / 2 - H * 0.08f;
        pillPlace(pillX, pillW, pillH);
        seekPlace();
        shelf.layout();
    }

    // ================= 极光 =================
    private final float[][] aFrom = new float[3][3], aTo = new float[3][3];
    private final float[] aC1 = new float[3], aC2 = new float[3], aC3 = new float[3];
    private final Spring aMix = new Spring(1, 6, 4.9f, 0.002f);
    private float aurK;

    private void aurColors(Palette p, boolean instant) {
        float[][] now = { aC1, aC2, aC3 };
        for (int k = 0; k < 3; k++) { aFrom[k] = (instant ? p.c[k] : now[k]).clone(); aTo[k] = p.c[k].clone(); }
        aMix.set(instant ? 1 : 0);
        aMix.t = 1;
    }

    private boolean aurTick(float dt) {
        boolean mv = aMix.step(dt);
        lerp3(aC1, aFrom[0], aTo[0], aMix.x); lerp3(aC2, aFrom[1], aTo[1], aMix.x); lerp3(aC3, aFrom[2], aTo[2], aMix.x);
        return mv;
    }

    private void aurRender() {
        aurFbo.bind();
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        pAur.use();
        pAur.f("uT", clock); pAur.f("uAsp", W / H); pAur.f("uK", aurK); pAur.f("uDay", day);
        pAur.c3("uC1", aC1); pAur.c3("uC2", aC2); pAur.c3("uC3", aC3);
        mFull.draw();
    }

    // ================= 封面玻璃砖 =================
    static final float BEV = 0.024f, HALF = 0.5f - BEV;
    private final float[] coverM = new float[16];
    float gFMix, gDim = 1, gSheen = 0.6f, haloK;
    final float[] gTint = new float[3], haloTint = new float[3];
    private final float[] tintTo = new float[3], haloTo = new float[3];

    /** 玻璃透出来的强调色、封面底下光晕的颜色：换歌、歌单里预览别的歌时慢慢过渡过去 */
    private void tintSet(Palette p, boolean instant) {
        System.arraycopy(p.accent, 0, tintTo, 0, 3);
        System.arraycopy(p.c[0], 0, haloTo, 0, 3);
        if (instant) { System.arraycopy(tintTo, 0, gTint, 0, 3); System.arraycopy(haloTo, 0, haloTint, 0, 3); }
    }

    private boolean tintTick(float dt) {
        float k = 1 - (float) Math.exp(-dt / 0.35);
        boolean mv = false;
        for (int i = 0; i < 3; i++) {
            float a = tintTo[i] - gTint[i], b = haloTo[i] - haloTint[i];
            if (Math.abs(a) > 0.002f) { gTint[i] += a * k; mv = true; } else gTint[i] = tintTo[i];
            if (Math.abs(b) > 0.002f) { haloTint[i] += b * k; mv = true; } else haloTint[i] = haloTo[i];
        }
        return mv;
    }
    final float[] cvPose = new float[7]; // 封面砖这一帧的姿态（x, y, z, rx, ry, rz, 大小）：歌单的 hero 砖从这里飞出去

    // ================= 背景封面：最多三张一起混。新来的那张从"现在看到的样子"慢慢淡进来——前一次还没淡完又来一张（歌单里翻得快）也不跳 =================
    private final Spring artMix = new Spring(1, 6, 4.9f, 0.002f); // 最新那张淡进来多少
    private final Bitmap[] artL = new Bitmap[3], artKeepB = new Bitmap[3]; // artL[0..artN-2] 是旧的（权重 artW，加起来是 1），artL[artN-1] 是最新的
    private final float[] artW = new float[3], artE = new float[3], artKeepW = new float[3]; // artE：这一帧每张显示多少
    private int artN;
    private final int[] texArt = new int[3];
    private float artK;
    private final float[] artM = new float[16];

    private boolean isArt(Bitmap b) { for (int i = 0; i < artN; i++) if (artL[i] == b) return true; return false; }

    private void artWeights() {
        float k = artMix.x;
        java.util.Arrays.fill(artE, 0);
        if (artN <= 1) { artE[0] = 1; return; }
        for (int i = 0; i < artN - 1; i++) artE[i] = (1 - k) * artW[i];
        artE[artN - 1] = k;
    }

    private void artSet(Bitmap b, boolean instant) {
        if (instant || artN == 0) { java.util.Arrays.fill(artL, null); artL[0] = b; artN = 1; artMix.set(1); return; }
        if (artL[artN - 1] == b) return;
        artWeights();
        // 现在看得见的（> 1%）当旧的留着，最多两张（多了去掉最淡的那张），再归一
        int m = 0;
        for (int i = 0; i < artN; i++) if (artE[i] > 0.01f) { artKeepB[m] = artL[i]; artKeepW[m] = artE[i]; m++; }
        while (m > 2) {
            int lo = 0;
            for (int i = 1; i < m; i++) if (artKeepW[i] < artKeepW[lo]) lo = i;
            for (int i = lo; i < m - 1; i++) { artKeepB[i] = artKeepB[i + 1]; artKeepW[i] = artKeepW[i + 1]; }
            m--;
        }
        float sum = 0;
        for (int i = 0; i < m; i++) sum += artKeepW[i];
        java.util.Arrays.fill(artL, null);
        for (int i = 0; i < m; i++) { artL[i] = artKeepB[i]; artW[i] = sum > 0 ? artKeepW[i] / sum : 1f / m; artKeepB[i] = null; }
        artL[m] = b;
        artN = m + 1;
        artMix.set(0);
        artMix.t = 1;
    }

    private boolean artTick(float dt, float k) {
        boolean mv = artMix.step(dt);
        if (artN > 1 && artMix.x >= 1) { Bitmap b = artL[artN - 1]; java.util.Arrays.fill(artL, null); artL[0] = b; artN = 1; } // 淡完了：只剩最新的
        artWeights();
        artK = k;
        float Z = -1700, f = (dpx - Z) / dpx, side = Math.max(W, H) * f * 1.3f;
        G.compose(artM, 0, H * 0.08f * f, Z, 0, 0, 0, side, side, 1);
        return mv;
    }

    // ================= 封面的动作：开场飞入、按下、暂停缩小、翻面换歌、拖着转 =================
    private final Spring cvPress = new Spring(1, 300, 22), cvPlay = new Spring(1, 110, 12.6f, 0.0005f), cvFlip = new Spring(0, 60, 12.5f, 0.0005f),
        cvRx = new Spring(0, 150, 16, 0.0005f), cvRy = new Spring(0, 150, 16, 0.0005f), cvDx = new Spring(0, 160, 20, 0.05f), cvFmix = new Spring(0, 40, 12.6f, 0.002f);
    private boolean cvNext, coverVisible = true;

    private boolean coverTick(float dt, float time) {
        boolean moving = false;
        for (Spring s : new Spring[] { cvPress, cvPlay, cvFlip, cvRx, cvRy, cvDx }) if (s.step(dt)) moving = true;
        cvPlay.t = MS.playing || !MS.on ? 1 : 0.86f;
        if (cvFmix.step(dt)) moving = true;
        gFMix = cvFmix.x;
        if (cvFmix.x == 1 && bFront2 != null) { bFront = bFront2; bFront2 = null; cvFmix.set(0); gFMix = 0; }
        if (cvNext && Math.abs(cvFlip.x - cvFlip.t) < 0.02f && Math.abs(cvFlip.v) < 0.5f) { setFront(bBack, true); cvFlip.set(0); cvNext = false; }
        boolean full = introFull();
        float k = full ? introCover.x : 1, z = mix(-2200, 0, k), ry0 = mix(-1.25f, 0, k), rx0 = mix(0.35f, 0, k), off = mix(W * 0.18f, 0, k);
        float sc = C * cvPress.x * cvPlay.x;
        float rx = rx0 + cvRx.x + 0.04f + (float) Math.sin(time * 0.5) * 0.03f;
        float ryy = ry0 + ry + cvRy.x + cvFlip.x + (float) Math.sin(time * 0.37) * 0.07f;
        float[] cp = cvPose;
        cp[0] = cx + off + cvDx.x; cp[1] = cy + mix(-60, 0, k) + (float) Math.sin(time * 0.6) * 4; cp[2] = z + 70; cp[3] = rx; cp[4] = ryy; cp[5] = (float) Math.sin(time * 0.29) * 0.012f; cp[6] = sc;
        G.compose(coverM, cp[0], cp[1], cp[2], cp[3], cp[4], cp[5], sc, sc, sc);
        coverVisible = !full || introT > 0.12f;
        gDim = mix(0.82f, 1, smooth(0.86f, 1, cvPlay.x));
        gSheen = 0.75f + ryy * 0.9f - rx * 0.6f;
        haloK = full ? smooth(0.5f, 1.2f, introT) : 1;
        return moving || introOn;
    }

    private void setFront(Bitmap b, boolean instant) {
        if (instant || bFront == null) { bFront = b; bFront2 = null; cvFmix.set(0); gFMix = 0; return; }
        if (bFront2 != null) bFront = bFront2;
        bFront2 = b;
        cvFmix.set(0);
        cvFmix.t = 1;
    }

    /** 用这张封面：取色给极光、高亮色、背景封面；flip 时印到背面（还在翻），不然正面交叉淡化 */
    private void useCover(Bitmap b, String mode) {
        Palette p = Palette.of(b);
        cover = b;
        pal = p;
        playPal = p;
        coverTitle = title;
        aurColors(p, "init".equals(mode));
        tintSet(p, "init".equals(mode));
        artSet(b, "init".equals(mode));
        if ("init".equals(mode)) { setFront(b, true); bBack = b; }
        else if ("flip".equals(mode) || cvNext) bBack = b;
        else { setFront(b, false); bBack = b; }
    }

    /** 歌单里停在别的歌上：天空（极光、背景封面、高亮色）换成那首的颜色；b 是 null：换回正在放的 */
    void shelfPreview(Bitmap b, Palette p) {
        if (b == null) { b = cover; p = playPal; }
        if (b == null || p == null) return;
        pal = p;
        aurColors(p, false);
        tintSet(p, false);
        artSet(b, false);
        wake();
    }

    private Bitmap coverNow() { return MS.cover != null && MS.track != null && MS.track.equals(MS.coverTrack) ? MS.cover : MediaState.makeCover(MS.title, MS.artist); }

    /** 宿主换歌、新封面也认准了（或者等够了）：封面翻面，歌词、极光颜色、背景封面跟着换（歌名在换歌的时候就先换了）；
     *  开了换歌动画时光点从新封面流出聚成新歌词（新歌的歌词还没到就等它到，最多 8 秒） */
    private void changeTrack(int dir, float time) {
        if (cvNext) { setFront(bBack, true); cvFlip.set(0); cvNext = false; }
        String t0 = title, a0 = artist;
        songText();
        useCover(coverNow(), "flip");
        seenCover = MS.revCover;
        cvNext = true;
        cvFlip.x += cvRy.x;
        cvRy.set(0);
        cvFlip.t = dir > 0 ? (float) -Math.PI : (float) Math.PI;
        cvFlip.v += dir > 0 ? -2 : 2;
        cvDx.t = 0;
        lines = songLines();
        lyricsSong(time, true);
        if (!title.equals(t0) || !artist.equals(a0)) titleSong(true);
        boolean sh = shelf.engaged(); // 歌单开着：歌词藏着，不放换歌的光点
        revealAt = stReveal && !sh ? time : -1;
        if (stReveal && !sh && MS.lines.length > 0) { introStart("song"); revealAt = -1; }
        wake();
    }

    private float revealAt = -1;

    private void syncMedia(float time) {
        MediaState.Snap s = MS;
        if (s.revTrack != seenTrack) {
            boolean first = seenTrack < 0;
            seenTrack = s.revTrack;
            if (first) { seenLines = s.revLines; return; }
            // 换歌：歌名先换；封面翻面、换歌词等这首的封面认准了再做（宿主头一两秒给的封面常常还是上一首的，见 MediaState），最多等 3.5 秒
            Pend p = new Pend();
            p.dir = "previous".equals(s.trackCmd) ? -1 : 1;
            p.at = time;
            p.st = pend != null ? pend.st : lastSt;
            pend = p;
            songText();
            titleSong(true);
        }
        if (pend != null) {
            boolean ok = s.cover != null && s.track != null && s.track.equals(s.coverTrack);
            if (!ok && s.on && time - pend.at < 3.5f) return; // 等着的时候歌词、封面先不动
            Pend p = pend;
            pend = null;
            seenLines = s.revLines;
            changeTrack(p.dir, time);
            return;
        }
        if (s.revLines != seenLines) {
            seenLines = s.revLines;
            String[] before = lines, now2 = songLines();
            boolean grew = before.length > 0 && now2.length > before.length && now2[before.length - 1].equals(before[before.length - 1]) && s.lines.length > 0 && !s.full;
            lines = now2;
            if (grew) measureSong();
            else if (revealAt >= 0 && !shelf.engaged() && s.lines.length > 0 && time - revealAt < 8) { revealAt = -1; lyricsSong(time, true); introStart("song"); }
            else { lyricsSong(time, true); LY.enterAt = time; }
        }
        if (s.revCover != seenCover) { seenCover = s.revCover; if (s.cover != null && s.track != null && s.track.equals(s.coverTrack)) useCover(s.cover, "late"); }
        if (s.on && !s.title.equals(title)) { songText(); titleSong(true); }
    }

    // ================= 歌词滚轮 =================
    private static final class Meta { float c, h, F; String mode; }

    private static final class LineObj {
        int i, tex;
        Txt.Line lt;
        G.Mesh mesh;
        Spring y, alpha, blur, act;
        float at, ty, fadeFrom, leave, pulseAt, uAlpha, uBlur, uAct, uPulse;
        boolean was;
        float[] pts;   // 字上的点（这句自己的坐标，x, y 交替），光点聚成 / 散开歌词用
        float bAlpha;  // 歌单淡掉之前的透明度（看得见的才取点）
        final float[] m = new float[16];
    }

    private static final class Ly {
        final List<Meta> meta = new ArrayList<>();
        final Map<Integer, LineObj> objs = new HashMap<>();
        final List<LineObj> leaving = new ArrayList<>();
        final Spring scroll = new Spring(0, 130, 19, 0.05f);
        float scrollV, idleAt, enterAt;
        boolean drag;
        int cur = -1, shown;
    }

    private final Ly LY = new Ly();
    private final float[] lygM = new float[16];

    private float[] fitLine(String text) {
        float F, min = Fpx * 0.6f;
        int rows;
        for (F = Fpx; ; F = Math.max(min, F * 0.93f)) { rows = Txt.rowsOf(text, maxW / (F / Txt.TF), "").size(); if (rows <= 3 || F <= min) break; }
        if (rows <= 3) return new float[] { F, rows, 0 };
        for (F = Fpx * 0.85f; ; F = Math.max(min, F * 0.93f)) { rows = Txt.rowsOf(text, maxW / (F / Txt.TF), "phrase").size(); if (rows <= 4 || F <= min) break; }
        return new float[] { F, rows, 1 };
    }

    private void measureSong() {
        float acc = 0;
        LY.meta.clear();
        for (String l : lines) {
            float[] f = fitLine(l);
            float h = f[1] * Txt.TF * Txt.RH * (f[0] / Txt.TF);
            Meta o = new Meta();
            o.c = acc + h / 2; o.h = h; o.F = f[0]; o.mode = f[2] > 0 ? "phrase" : "";
            LY.meta.add(o);
            acc += h + gap;
        }
    }

    private float lineTarget(int i, int cur) { return LY.meta.get(i).c - LY.meta.get(cur).c - LY.scroll.x; }

    /** 按弧长摆到滚轮上，返回转过的角度 */
    private float onWheel(LineObj o, float a) {
        float th = clamp(a / R, -1.55f, 1.55f);
        G.compose(o.m, center ? -o.lt.wPx / 2 : 0, -R * (float) Math.sin(th), -R * (1 - (float) Math.cos(th)), th, 0, 0, 1, 1, 1);
        return Math.abs(a / R);
    }

    private LineObj makeObj(int i, float time, boolean entering) {
        Meta mt = LY.meta.get(i);
        LineObj o = new LineObj();
        o.i = i;
        o.lt = Txt.line(lines[i], mt.F, maxW, mt.mode);
        Txt.Line lt = o.lt;
        o.tex = G.texture(lt.bmp, false);
        o.pts = samplePts(lt);
        lt.bmp.recycle();
        lt.bmp = null;
        o.mesh = G.quad(-lt.pad * lt.s, lt.hc - (lt.H - lt.pad) * lt.s, (lt.W - lt.pad) * lt.s, lt.hc + lt.pad * lt.s);
        float ty = lineTarget(i, LY.shown);
        o.ty = ty;
        o.y = new Spring(ty, 130, 19, 0.05f);
        o.alpha = new Spring(0, 120, 22);
        o.blur = new Spring(1, 120, 22);
        o.act = new Spring(0, 90, 18);
        if (entering) { o.y.set(ty + 120); o.at = LY.enterAt + Math.max(0, i - LY.shown + 1) * 0.06f; }
        onWheel(o, o.y.x);
        LY.objs.put(i, o);
        return o;
    }

    private final int[] rowBuf = new int[2048];
    private float[] ptsBuf = new float[8192];

    /** 字上的点：每隔 3 像素看一下 R 通道（清楚的字），最多留 900 个（均匀挑） */
    private float[] samplePts(Txt.Line lt) {
        Bitmap b = lt.bmp;
        int Wb = b.getWidth(), Hb = b.getHeight(), c = 0;
        for (int yy = 0; yy < Hb; yy += 3) {
            b.getPixels(rowBuf, 0, Wb, 0, yy, Wb, 1);
            for (int xx = 0; xx < Wb; xx += 3) {
                if (((rowBuf[xx] >> 16) & 255) <= 150) continue;
                if (c + 2 > ptsBuf.length) ptsBuf = java.util.Arrays.copyOf(ptsBuf, ptsBuf.length * 2);
                ptsBuf[c++] = (xx - lt.pad) * lt.s;
                ptsBuf[c++] = -(yy - lt.pad) * lt.s + lt.hc;
            }
        }
        int m = c / 2, keep = Math.min(m, 900);
        float[] out = new float[keep * 2];
        for (int k = 0; k < keep; k++) { int j = (int) ((long) k * m / keep); out[k * 2] = ptsBuf[j * 2]; out[k * 2 + 1] = ptsBuf[j * 2 + 1]; }
        return out;
    }

    private void dropObj(LineObj o) {
        G.free(o.tex);
        o.tex = 0;
        if (o.mesh != null) o.mesh.free();
        o.mesh = null;
    }

    private void lyricsSong(float time, boolean animate) {
        for (LineObj o : LY.objs.values()) {
            if (animate) { o.fadeFrom = o.uAlpha; o.alpha.set(1); o.leave = time + Math.max(0, o.i - LY.shown + 2) * 0.04f; LY.leaving.add(o); }
            else dropObj(o);
        }
        LY.objs.clear();
        LY.scroll.set(0);
        LY.scrollV = 0;
        measureSong();
        LY.shown = lyricState()[0];
        LY.cur = -1;
        LY.enterAt = time + (animate ? 0.3f : 0);
    }

    private boolean lyricsTick(int[] st, float dt, float time) {
        boolean moving = false;
        int n = lines.length;
        if (n == 0 || LY.meta.isEmpty()) { seekSel = -1; return leavingTick(dt, time); }
        if (!LY.drag) {
            if (Math.abs(LY.scrollV) > 5) { LY.scroll.set(LY.scroll.x + LY.scrollV * dt); LY.scrollV *= (float) Math.exp(-dt * 3.4); LY.idleAt = time; moving = true; }
            else {
                LY.scrollV = 0;
                if (LY.scroll.x != 0 && time - LY.idleAt > 3) LY.scroll.t = 0;
                else if (seekSel >= 0 && seekSel < n && LY.shown < n) LY.scroll.t = LY.meta.get(seekSel).c - LY.meta.get(LY.shown).c; // 松手停下来：对着的那句挪到定位线上
            }
        }
        int cur = Math.min(st[0], n - 1);
        if (LY.cur >= 0 && cur != LY.cur && LY.shown < n && (LY.drag || Math.abs(LY.scroll.x) > 6)) { // 翻着歌词时换了句（唱到下一句、点定位线跳过去）：翻到的地方不动
            float d = LY.meta.get(cur).c - LY.meta.get(LY.shown).c;
            LY.scroll.x -= d;
            LY.scroll.t -= d;
            if (grab != null) grab.s0 -= d;
        }
        float lo = -LY.meta.get(cur).c - 20, hiv = LY.meta.get(n - 1).c - LY.meta.get(cur).c + 20;
        if (LY.scroll.x < lo) { LY.scroll.t = lo; if (!LY.drag) LY.scrollV = 0; }
        else if (LY.scroll.x > hiv) { LY.scroll.t = hiv; if (!LY.drag) LY.scrollV = 0; }
        if (LY.scroll.step(dt)) moving = true;
        boolean browsing = LY.drag || Math.abs(LY.scroll.x) > 6;
        if (cur != LY.cur) {
            boolean first = LY.cur < 0;
            LY.cur = cur;
            LY.shown = cur;
            for (LineObj o : LY.objs.values()) o.at = first ? o.at : time + clamp(o.i - cur + 1, 0, 9) * 0.05f;
        }
        seekSel = seekPick(browsing, n);
        if (seekSel >= 0) seekMs = MS.times[seekSel];
        float lim = R * 1.35f;
        List<Integer> want = new ArrayList<>();
        for (int i = 0; i < n; i++) if (Math.abs(lineTarget(i, LY.shown)) < lim) want.add(i);
        Iterator<Map.Entry<Integer, LineObj>> it = LY.objs.entrySet().iterator();
        while (it.hasNext()) { Map.Entry<Integer, LineObj> e = it.next(); if (!want.contains(e.getKey())) { dropObj(e.getValue()); it.remove(); } }
        float stiff = LY.drag ? 420 : 130, damp = LY.drag ? 42 : 19;
        for (int i : want) {
            LineObj o = LY.objs.get(i);
            if (o == null) o = makeObj(i, time, time < LY.enterAt + 0.6f);
            boolean isCur = i == cur && st[1] == 1;
            if (time >= o.at) o.ty = lineTarget(i, LY.shown);
            o.y.t = o.ty; o.y.k = stiff; o.y.c = damp;
            float introK = introLines(i - LY.shown), th = onWheel(o, o.y.x);
            o.act.t = isCur ? 1 : 0;
            o.alpha.t = time < o.at && o.alpha.x == 0 ? 0 : (isCur ? 1 : (i == seekSel ? 0.95f : browsing ? 0.7f : 0.46f) * (1 - day * 0.3f));
            o.blur.t = time < o.at && o.blur.x == 1 ? 1 : (isCur || browsing ? 0 : Math.min(0.9f, 0.12f + th * 0.85f));
            if (o.y.step(dt)) moving = true;
            if (o.alpha.step(dt)) moving = true;
            if (o.blur.step(dt)) moving = true;
            if (o.act.step(dt)) moving = true;
            if (time < o.at) moving = true;
            if (isCur && !o.was) o.pulseAt = time;
            o.was = isCur;
            float pulse = isCur ? (float) Math.exp(-(time - o.pulseAt) * 2.4) * smooth(0, 0.12f, time - o.pulseAt) : 0;
            if (pulse > 0.01f) moving = true;
            float sy = py + o.m[13];
            o.uAlpha = o.alpha.x * (1 - smooth(fade0, fade1, th)) * (1 - smooth(topY - 50, topY, sy)) * introK;
            o.uBlur = clamp(o.blur.x + (1 - introK), 0, 1);
            o.bAlpha = o.uAlpha;
            float shK = shelf.lyK(i - LY.shown); // 歌单开着：散成光点流进封面
            o.uAlpha *= 1 - shK;
            o.uBlur = Math.max(o.uBlur, shK);
            o.uAct = o.act.x;
            o.uPulse = pulse;
        }
        if (leavingTick(dt, time)) moving = true;
        if (st[1] == 1 && MS.playing) moving = true;
        return moving;
    }

    private boolean leavingTick(float dt, float time) {
        boolean moving = false;
        for (int j = LY.leaving.size() - 1; j >= 0; j--) {
            LineObj o = LY.leaving.get(j);
            if (time >= o.leave) { o.alpha.t = 0; o.blur.t = 1; o.y.t = o.ty - R * 0.9f; }
            o.y.step(dt); o.alpha.step(dt); o.blur.step(dt);
            float th = onWheel(o, o.y.x);
            o.uAlpha = o.alpha.x * o.fadeFrom * (1 - smooth(fade0, fade1, th)) * (1 - shelf.lyK(o.i - LY.shown));
            o.uBlur = o.blur.x;
            o.uAct = 0; o.uPulse = 0;
            if (o.alpha.x < 0.01f && time > o.leave + 0.2f) { dropObj(o); LY.leaving.remove(j); } else moving = true;
        }
        return moving;
    }

    // ================= 歌词定位线：上下翻歌词时滚轮正中出现一条线，右头是对着的那句的时间和播放键，点了从那一句开始放 =================
    // 只在宿主给了带时间的整首歌词时有（MS.times）；跳过去发给「多媒体」的导出入口（见 Player，零权限）
    private final Spring seekVis = new Spring(0, 160, 20, 0.002f), seekPress = new Spring(1, 300, 18);
    private int seekSel = -1; // 定位线对着第几句（-1：没在翻、这首没有时间）
    private long seekMs;      // 那一句开始的时间（淡出时留着）
    private String seekText = "";
    private int seekTex;
    private G.Mesh mSeekLbl;
    private float seekLh, seekX0, seekX1, seekBx, seekR;
    private final float[] seekM = new float[16], seekLM = new float[16];

    /** 摆在歌词这一组的坐标里（滚轮正中 y = 0）：线和歌词一样宽（横屏居中、竖屏左对齐），播放键在线的右头，竖屏不出画面 */
    private void seekPlace() {
        seekR = Fpx * 0.36f;
        seekX0 = center ? -maxW / 2 : 0;
        float x1 = center ? maxW / 2 : maxW;
        seekBx = x1 + seekR * 1.7f;
        if (!center) seekBx = Math.min(seekBx, W / 2 - px - seekR * 1.4f);
        seekX1 = Math.min(x1, seekBx - seekR * 1.8f);
    }

    /** 翻着歌词时对着正中的是哪一句（最近的那句） */
    private int seekPick(boolean browsing, int n) {
        if (!browsing || pend != null || shelf.engaged() || !MS.on || MS.times == null || MS.times.length != n) return -1;
        float at = LY.meta.get(LY.shown).c + LY.scroll.x, best = Float.MAX_VALUE;
        int sel = -1;
        for (int i = 0; i < n; i++) { float d = Math.abs(LY.meta.get(i).c - at); if (d < best) { best = d; sel = i; } }
        return sel;
    }

    private boolean seekTick(float dt) {
        boolean moving = false;
        seekVis.t = seekSel >= 0 ? 1 : 0;
        if (seekVis.step(dt)) moving = true;
        if (seekPress.step(dt)) moving = true;
        String t = fmt(seekMs);
        if (seekVis.x > 0.002f && (!t.equals(seekText) || seekTex == 0)) {
            seekText = t;
            Txt.Label l = Txt.label(t, Fpx * 0.3f, W, false);
            if (seekTex != 0) G.free(seekTex);
            seekTex = G.texture(l.bmp, false);
            l.bmp.recycle();
            if (mSeekLbl != null) mSeekLbl.free();
            mSeekLbl = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
            seekLh = l.h;
        }
        return moving;
    }

    /** 手指按在定位线的播放键上没有（播放键投到画面上，按得大一点） */
    private boolean seekHit(float ex, float ey) {
        if (seekSel < 0 || seekVis.x < 0.5f) return false;
        float[] PS = new float[16], M = new float[16], out = new float[3];
        G.mul(PS, P, S);
        G.mul(M, PS, lygM);
        G.project(out, M, seekBx, 0, 0);
        float dx = (out[0] * 0.5f + 0.5f) * W - ex, dy = (0.5f - out[1] * 0.5f) * H - ey, r = seekR * 2.4f;
        return dx * dx + dy * dy < r * r;
    }

    private void seekTap() {
        if (seekSel < 0) return;
        host.seek(seekMs);
        LY.idleAt = clock - 2.6f; // 滚轮马上转回正在唱的那句（跳过去换了句时，翻到的位置会接上，不会跳）
    }

    private void drawSeek(float[] V, float[] Pp) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        float x0 = seekX0 - 2, x1 = seekBx + seekR * 1.2f, h = seekR * 2.6f, cxq = (x0 + x1) / 2;
        G.compose(seekM, cxq, 0, 0, 0, 0, 0, x1 - x0, h, 1);
        G.mul(seekLM, lygM, seekM);
        pSeek.use();
        mv(V, seekLM);
        pSeek.m4("uP", Pp); pSeek.m4("uMV", MV);
        // 线只画在对着的那句两边（不从字上划过去）
        LineObj so = seekSel >= 0 ? LY.objs.get(seekSel) : null;
        float tw = so != null && so.lt != null ? so.lt.wPx : 0, tl = center ? -tw / 2 : 0, gp = tw > 0 ? Fpx * 0.4f : 0;
        pSeek.f2("uSize", x1 - x0, h); pSeek.f2("uLine", seekX0 - cxq, seekX1 - cxq); pSeek.f2("uGap", tl - gp - cxq, tl + tw + gp - cxq); pSeek.f("uBx", seekBx - cxq); pSeek.f("uR", seekR);
        pSeek.f("uPress", seekPress.x); pSeek.f("uK", seekVis.x); pSeek.f("uDay", day);
        pSeek.c3("uInk", ink); pSeek.c3("uAcc", pal.accent);
        mUnit.draw();
        if (seekTex != 0 && mSeekLbl != null) { // 时间：播放键上面
            G.compose(seekM, seekBx, seekR * 1.1f + seekLh * 0.42f, 0, 0, 0, 0, 1, 1, 1);
            G.mul(seekLM, lygM, seekM);
            pText.use();
            mv(V, seekLM);
            pText.m4("uP", Pp); pText.m4("uMV", MV); pText.c3("uInk", ink);
            pText.tex("uMap", 0, seekTex); pText.f("uA", seekVis.x * 0.8f); pText.f("uK", 1);
            mSeekLbl.draw();
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }

    // ================= 歌名、歌手 =================
    private static final class Title {
        Txt.Label t, a;
        int tt, ta;
        G.Mesh mt, ma;
        Spring k = new Spring(0, 110, 20), y;
        float delay;
        final float[] Mt = new float[16], Ma = new float[16];
        float alpha;
    }

    private static final class Tt { Title cur; final List<Title> old = new ArrayList<>(); }

    private final Tt TT = new Tt();

    private void titleSong(boolean animate) {
        if (TT.cur != null) { if (animate) TT.old.add(TT.cur); else freeTitle(TT.cur); }
        Title o = new Title();
        o.t = Txt.label(title, tF, tW, true);
        o.a = Txt.label(artist, aF, tW, false);
        o.tt = G.texture(o.t.bmp, false); o.t.bmp.recycle();
        o.ta = G.texture(o.a.bmp, false); o.a.bmp.recycle();
        o.mt = G.quad(-o.t.w / 2, -o.t.h / 2, o.t.w / 2, o.t.h / 2);
        o.ma = G.quad(-o.a.w / 2, -o.a.h / 2, o.a.w / 2, o.a.h / 2);
        o.y = new Spring(animate ? -14 : 0, 110, 20, 0.05f);
        o.k.t = 1;
        o.y.t = 0;
        o.delay = animate ? 0.25f : 0;
        TT.cur = o;
    }

    private void freeTitle(Title o) { G.free(o.tt); G.free(o.ta); o.mt.free(); o.ma.free(); }

    private boolean titleTick(float dt, float k0) {
        boolean moving = false;
        if (TT.cur != null) {
            Title o = TT.cur;
            if (o.delay > 0) { o.delay -= dt; moving = true; }
            else { if (o.k.step(dt)) moving = true; if (o.y.step(dt)) moving = true; }
            placeTitle(o, k0);
        }
        for (int j = TT.old.size() - 1; j >= 0; j--) {
            Title o = TT.old.get(j);
            o.k.t = 0; o.y.t = 16;
            o.k.step(dt); o.y.step(dt);
            placeTitle(o, k0);
            if (o.k.x < 0.01f) { freeTitle(o); TT.old.remove(j); } else moving = true;
        }
        return moving;
    }

    private void placeTitle(Title o, float k0) {
        float x = tX + (narrow ? (-W / 2 + W * 0.08f - tX) * shelf.titleK() : 0); // 竖屏开着歌单：封面走了，歌名挪到左边
        G.compose(o.Mt, x - o.t.pad + o.t.w / 2, tY - o.t.h / 2 + o.y.x, 1, 0, 0, 0, 1, 1, 1);
        G.compose(o.Ma, x - o.a.pad + o.a.w / 2, aY - o.a.h / 2 + o.y.x, 1, 0, 0, 0, 1, 1, 1);
        o.alpha = o.k.x * k0 * (narrow ? 1 : 1 - shelf.searchK()); // 横屏搜索时砖那一行让到画面上沿，左上角的歌名淡掉
    }

    // ================= 控制条：一块浮在水面上的玻璃 =================
    private final Spring[] press = { new Spring(1, 300, 18), new Spring(1, 300, 18), new Spring(1, 300, 18), new Spring(1, 300, 18) }; // 上一首、播放、下一首、红心
    private final Spring play = new Spring(1, 160, 18, 0.002f), rise = new Spring(1, 90, 11, 0.0005f);
    private final Spring heartVis = new Spring(0, 160, 20, 0.002f), heartFill = new Spring(0, 220, 16, 0.002f);
    private float pillBaseY, pillZ, pillBv, pillK;
    float pillLw = 1, pillLh = 1, pillF = 1, iconsZ, pillVis; // pillVis：控制条这块玻璃显示多少（开场浮上来）
    private float pillBx, pillHx, pillFontK = 1; // 上一首、下一首离中间多远，红心在哪，时间字缩到几成（见 pillPlace）
    private String pillKey = "", timeText = "";
    final float[] pillM = new float[16], iconsM = new float[16];

    private boolean hasSongs;

    /** 有没有歌单（歌单那边看到变了叫）：有歌单时点封面打开歌单 */
    void songsAvail(boolean on) { hasSongs = on; }
    private int timeTex;
    private Bitmap timeBmp;
    private float pillSx;

    private void pillPlace(float sx, float w, float h) {
        float zp = H * 0.6f, f = dpx / (dpx - zp), lw = w / f, lh = h / f;
        String key = Math.round(lw) + "x" + Math.round(lh);
        pillF = f;
        pillBaseY = water + lh * 0.62f + H * 0.012f;
        pillSx = sx / f;
        pillZ = zp;
        if (!key.equals(pillKey)) {
            pillKey = key;
            if (mPill != null) mPill.free();
            float bv = lh * 0.12f;
            mPill = G.extrude(G.stadium(lw - bv * 2, lh - bv * 2, 16), lh * 0.18f, bv * 0.8f, bv, 3, 0);
            pillBv = bv;
            iconsZ = bv * 0.8f + 0.5f;
            int th = Math.max(16, Math.round(512 * lh / lw));
            if (timeBmp == null || timeBmp.getHeight() != th) {
                timeBmp = Bitmap.createBitmap(512, th, Bitmap.Config.ARGB_8888);
                if (timeTex != 0) { G.free(timeTex); timeTex = 0; }
            }
            timeText = "";
        }
        pillLw = lw;
        pillLh = lh;
        // 红心放在左边时间和"上一首"中间。窗口"瘦"（7 分屏、竖屏小窗口）时控制条按宽度算、字和按钮按高度算，会挤：
        // 时间字按窄的程度缩小（最小八成），还放不下就把上一首、下一首往中间收（不碰播放键外圈的进度）
        float q = lw / lh, g = lh * 0.14f, hw = lh * 0.13f, pw = lh * 0.16f;
        pillFontK = clamp(q / 5.6f, 0.8f, 1);
        float tEnd = -lw / 2 + lw * 0.075f + Txt.timeWidth(lh * 0.24f * pillFontK); // 左边时间的右头
        float hMin = tEnd + g + hw;
        pillBx = Math.min(lw * 0.19f, Math.max(lh * 0.58f, -(hMin + hw + g + pw)));
        pillHx = Math.min((hMin - pillBx - pw - g - hw) / 2, -pillBx - pw - g - hw);
        pillHx = Math.max(pillHx, hMin);
    }

    /** 手指点在哪个键上：0 上一首 / 1 播放暂停 / 2 下一首 / 3 红心 / -1 没点到控制条（cx、cy 是画面上的位置） */
    private int pillHit(float cxp, float cyp) {
        float[] PS = new float[16], out = new float[3];
        G.mul(PS, P, S);
        float[] PM = new float[16];
        G.mul(PM, PS, pillM);
        int best = -1;
        float bd = 1e9f;
        float[] xs = { -pillBx, 0, pillBx, pillHx };
        for (int k = 0; k < (MS.heart != BydMedia.HIDE ? 4 : 3); k++) {
            G.project(out, PM, xs[k], 0, iconsZ);
            float dx = (out[0] * 0.5f + 0.5f) * W - cxp, dy = (0.5f - out[1] * 0.5f) * H - cyp, dd = dx * dx + dy * dy;
            if (dd < bd) { bd = dd; best = k; }
        }
        G.project(out, PM, 0, 0, 0);
        float ppx = (out[0] * 0.5f + 0.5f) * W, ppy = (0.5f - out[1] * 0.5f) * H;
        return Math.abs(cxp - ppx) < pillLw * pillF * 0.5f && Math.abs(cyp - ppy) < pillLh * pillF * 0.75f ? best : -1;
    }

    private void pillPress(int k, boolean down) { press[k].t = down ? 0.8f : 1; if (!down) press[k].v += 3; }

    private boolean pillTick(float dt, long ms, float k0, float time) {
        boolean moving = false;
        for (Spring s : press) if (s.step(dt)) moving = true;
        play.t = MS.playing ? 1 : 0;
        if (play.step(dt)) moving = true;
        int hs = MS.heart;
        heartVis.t = hs != BydMedia.HIDE ? 1 : 0;
        if (hs != BydMedia.HIDE) heartFill.t = hs == BydMedia.ON ? 1 : 0; // 藏起来的时候保持原样淡出
        if (heartVis.step(dt)) moving = true;
        if (heartFill.step(dt)) moving = true;
        rise.t = k0 > 0.02f ? 1 : 0;
        if (rise.step(dt)) moving = true;
        float y = pillBaseY - (1 - rise.x) * pillLh * 1.9f - shelf.searchK() * pillLh * 2.2f; // 歌单里搜索时沉下去（键盘在那儿）
        // 位置抵掉场景整体的微微摆动（S 的转动）：控制条离镜头近，不抵的话左右晃得多，窄窗口里会晃出画面、点的时候也会挪；朝向照样跟着场景转
        float ca = (float) Math.cos(sRx), sa = (float) Math.sin(sRx), cb = (float) Math.cos(sRy), sb = (float) Math.sin(sRy);
        float y1 = y * ca + pillZ * sa, z1 = -y * sa + pillZ * ca;
        G.compose(pillM, pillSx * cb - z1 * sb, y1, pillSx * sb + z1 * cb, 0.06f + (float) Math.sin(time * 0.4) * 0.02f, 0.16f + (float) Math.sin(time * 0.31) * 0.03f, 0, 1, 1, 1);
        G.compose(iconsM, 0, 0, iconsZ, 0, 0, 0, pillLw, pillLh, 1);
        G.mul(iconsM, pillM, iconsM.clone());
        pillVis = clamp(k0, 0, 1) * clamp(rise.x * 1.5f - 0.5f, 0, 1);
        pillK = pillVis * (1 - shelf.ctlK()); // 歌单开着：按键淡掉，同一块玻璃上换成刻度条（Shelf 画）
        String dTxt = dur > 0 ? fmt(dur) : "--:--", txt = fmt(ms) + "|" + dTxt;
        if (!txt.equals(timeText) && timeBmp != null) {
            timeText = txt;
            float pxw = pillLw / 512;
            Txt.time(timeBmp, fmt(ms), dTxt, pillLh * 0.24f * pillFontK / pxw);
            if (timeTex == 0) timeTex = G.texture(timeBmp, false); else G.update(timeTex, timeBmp);
        }
        return moving;
    }

    private static String fmt(long ms) { long s = ms / 1000; return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60); }

    // ================= 光点：从封面流出，划着弧线聚成歌词（开场、换歌都用） =================
    private static final int PN = 2200;
    private final float[] partData = new float[PN * 18];
    private float partT = -1, partFade;
    private boolean partVisible;

    /** 从看得见的几句歌词的字上取点当终点（按每句在滚轮上的姿态换算），封面上随机取点当起点；每颗带它流出位置的封面色相 */
    private void aim() {
        List<float[]> pts = new ArrayList<>();
        Rnd r = new Rnd(99);
        Bitmap small = Bitmap.createScaledBitmap(cover, 48, 48, true);
        int[] cd = new int[48 * 48];
        small.getPixels(cd, 0, 48, 0, 0, 48, 48);
        if (small != cover) small.recycle();
        float[] tmp = new float[4], v = new float[4], lm = new float[16];
        for (LineObj o : LY.objs.values()) {
            if (o.pts == null) continue;
            G.mul(lm, lygM, o.m);
            for (int k = 0; k < o.pts.length; k += 2) {
                v[0] = o.pts[k]; v[1] = o.pts[k + 1]; v[2] = 0; v[3] = 1;
                Matrix.multiplyMV(tmp, 0, lm, 0, v, 0);
                pts.add(new float[] { tmp[0], tmp[1], o.i - LY.shown, tmp[2] });
            }
        }
        int m2 = pts.size();
        float[] hsl = new float[3];
        for (int i = 0; i < PN; i++) {
            float[] pt = m2 > 0 ? pts.get((int) Math.floor(r.next() * m2)) : new float[] { 0, 0, 0, 0 };
            float ex = pt[0], ey = pt[1], row = pt[2], ez = pt[3];
            int k = i * 18;
            partData[k] = ex + (r.next() - 0.5f) * 2; partData[k + 1] = ey + (r.next() - 0.5f) * 2; partData[k + 2] = ez;
            float ux = r.next(), uy = r.next(), sx = cx + (ux - 0.5f) * C * 0.9f, sy = cy + (0.5f - uy) * C * 0.9f;
            int ci = Math.min(47, (int) Math.floor(uy * 48)) * 48 + Math.min(47, (int) Math.floor(ux * 48));
            int col = cd[ci];
            Palette.toHsl(((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, hsl);
            float sat = hsl[1] < 0.12f ? hsl[1] : Math.max(hsl[1], 0.7f);
            float[] cday = Palette.fromHsl(hsl[0], sat, 0.4f), cnight = Palette.fromHsl(hsl[0], sat, 0.72f);
            partData[k + 3] = sx; partData[k + 4] = sy; partData[k + 5] = 90;
            partData[k + 6] = mix(sx, ex, 0.5f) + (r.next() - 0.5f) * 160; partData[k + 7] = mix(sy, ey, 0.5f) + 60 + r.next() * 140; partData[k + 8] = 120 + r.next() * 220;
            partData[k + 9] = 0.1f * Math.max(0, row) + clamp((ex - px) / Math.max(1, maxW) + (center ? 0.5f : 0), 0, 1) * 0.35f + r.next() * 0.15f;
            partData[k + 10] = r.next(); partData[k + 11] = r.next();
            partData[k + 12] = cday[0]; partData[k + 13] = cday[1]; partData[k + 14] = cday[2];
            partData[k + 15] = cnight[0]; partData[k + 16] = cnight[1]; partData[k + 17] = cnight[2];
        }
        mPart.update(partData);
    }

    /** 歌单进出用的光点（见 Shelf）：终点是现在看得见的歌词的字，起点在 hero 砖的正面上（砖自己的坐标，画的时候乘砖的姿态，砖在飞也跟得上）；
     *  控制点在长廊中间那块（hx, hy, hz）和字的半路上。正在放的换歌光点先收掉。返回有没有点 */
    boolean shelfAim(float hx, float hy, float hz, float size) {
        if (introOn) { introOn = false; partVisible = false; introCover.set(1); btnWait(false); }
        revealAt = -1;
        if (cover == null) return false;
        List<float[]> pts = new ArrayList<>();
        float[] tmp = new float[4], v = new float[4], lm = new float[16];
        for (LineObj o : LY.objs.values()) {
            if (o.pts == null || o.bAlpha < 0.05f) continue;
            G.mul(lm, lygM, o.m);
            for (int k = 0; k < o.pts.length; k += 2) {
                v[0] = o.pts[k]; v[1] = o.pts[k + 1]; v[2] = 0; v[3] = 1;
                Matrix.multiplyMV(tmp, 0, lm, 0, v, 0);
                pts.add(new float[] { tmp[0], tmp[1], o.i - LY.shown, tmp[2] });
            }
        }
        int m2 = pts.size();
        if (m2 == 0) return false;
        Rnd r = new Rnd(77);
        Bitmap small = Bitmap.createScaledBitmap(cover, 48, 48, true);
        int[] cd = new int[48 * 48];
        small.getPixels(cd, 0, 48, 0, 0, 48, 48);
        if (small != cover) small.recycle();
        float[] hsl = new float[3];
        float maxD = 0;
        for (int i = 0; i < PN; i++) {
            float[] pt = pts.get((int) Math.floor(r.next() * m2));
            float ex = pt[0] + (r.next() - 0.5f) * 2, ey = pt[1] + (r.next() - 0.5f) * 2, row = pt[2];
            int k = i * 18;
            partData[k] = ex; partData[k + 1] = ey; partData[k + 2] = pt[3];
            float ux = r.next(), uy = r.next();
            int col = cd[Math.min(47, (int) Math.floor(uy * 48)) * 48 + Math.min(47, (int) Math.floor(ux * 48))];
            Palette.toHsl(((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, hsl);
            float sat = hsl[1] < 0.12f ? hsl[1] : Math.max(hsl[1], 0.7f);
            float[] cday = Palette.fromHsl(hsl[0], sat, 0.4f), cnight = Palette.fromHsl(hsl[0], sat, 0.72f);
            partData[k + 3] = (ux - 0.5f) * 0.9f; partData[k + 4] = (0.5f - uy) * 0.9f; partData[k + 5] = 0.09f; // 砖自己的坐标
            partData[k + 6] = mix(hx, ex, 0.5f) + (r.next() - 0.5f) * 160; partData[k + 7] = mix(hy, ey, 0.5f) + 60 + r.next() * 140; partData[k + 8] = mix(hz, pt[3], 0.5f) + 120 + r.next() * 220;
            float dl = 0.1f * Math.max(0, row) + clamp((ex - px) / Math.max(1, maxW) + (center ? 0.5f : 0), 0, 1) * 0.35f + r.next() * 0.15f;
            partData[k + 9] = dl; maxD = Math.max(maxD, dl);
            partData[k + 10] = r.next(); partData[k + 11] = r.next();
            partData[k + 12] = cday[0]; partData[k + 13] = cday[1]; partData[k + 14] = cday[2];
            partData[k + 15] = cnight[0]; partData[k + 16] = cnight[1]; partData[k + 17] = cnight[2];
        }
        mPart.update(partData);
        shelf.partMax = maxD + 0.9f;
        return true;
    }

    // 开场 intro：极光亮起 → 封面从深处飞来 → 光点聚成歌词；换歌 song：封面翻面的同时光点从新封面流出
    private boolean introOn, introAimed;
    private float introT;
    private String introMode = "intro";
    private final Spring introCover = new Spring(1, 70, 11.5f, 0.0005f);

    private float[] introTimes() { return "intro".equals(introMode) ? new float[] { 0.75f, 1.45f, 2.7f, 3.25f } : new float[] { 0.3f, 0.95f, 2.15f, 2.7f }; }
    private boolean introFull() { return introOn && "intro".equals(introMode); }

    // ================= 新手引导要知道封面在画面上的哪儿 =================
    private volatile boolean trackCover;
    private final float[] cvC = new float[4], cvWd = new float[4], cvClip = new float[4], cvPS = new float[16], cvRect = new float[4];

    /** 新手引导开着：每帧算封面在画面上的范围，挪了一个像素以上才交出去 */
    void trackCover(boolean on) { trackCover = on; cvRect[0] = -1; wake(); }

    private void reportCover() {
        G.mul(cvPS, P, S);
        float l = 1e9f, t = 1e9f, r = -1e9f, b = -1e9f;
        for (int i = 0; i < 4; i++) { // 玻璃砖正面的四个角
            cvC[0] = (i & 1) == 0 ? -0.5f : 0.5f; cvC[1] = (i & 2) == 0 ? -0.5f : 0.5f; cvC[2] = 0.11f; cvC[3] = 1;
            Matrix.multiplyMV(cvWd, 0, coverM, 0, cvC, 0);
            Matrix.multiplyMV(cvClip, 0, cvPS, 0, cvWd, 0);
            float x = cvClip[0] / cvClip[3] * 0.5f + 0.5f, y = 0.5f - cvClip[1] / cvClip[3] * 0.5f;
            l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); b = Math.max(b, y);
        }
        if (cvRect[0] >= 0 && Math.abs(l - cvRect[0]) * W < 1 && Math.abs(t - cvRect[1]) * H < 1 && Math.abs(r - cvRect[2]) * W < 1 && Math.abs(b - cvRect[3]) * H < 1) return;
        cvRect[0] = l; cvRect[1] = t; cvRect[2] = r; cvRect[3] = b;
        host.coverAt(l, t, r, b);
    }

    private void introStart(String mode) {
        introMode = mode;
        introOn = true;
        introT = 0;
        introAimed = false;
        if ("intro".equals(mode)) { introCover.set(0); lyricsSong(0, false); rise.set(0); }
        btnWait("intro".equals(mode)); // 开场还没走完就换了歌（换歌动画顶掉开场）：键也要出来
        LY.enterAt = -10;
        for (LineObj o : LY.objs.values()) dropObj(o);
        LY.objs.clear();
        partVisible = true;
        partT = -1;
        partFade = 0;
        wake();
    }

    private float introLines(int row) {
        if (!introOn) return 1;
        float[] T = introTimes();
        float r = Math.max(0, row) * 0.1f;
        return smooth(T[1] + r, T[1] + 0.7f + r, introT);
    }

    private boolean introTick(float dt) {
        if (!introOn) return false;
        introT += dt;
        float t = introT;
        float[] T = introTimes();
        if ("intro".equals(introMode)) { if (t > 0.15f) introCover.t = 1; introCover.step(dt); if (t > 2.2f) btnWait(false); }
        if (!introAimed && t > 0.12f && !LY.objs.isEmpty()) { aim(); introAimed = true; }
        partT = t - T[0];
        partFade = smooth(T[2], T[2] + 0.5f, t);
        if (t > T[3]) { introOn = false; partVisible = false; introCover.set(1); btnWait(false); }
        return true;
    }

    // ================= 手势（同网页版） =================
    private final Spring tiltX = new Spring(0, 90, 15, 0.0002f), tiltY = new Spring(0, 90, 15, 0.0002f);

    private static final class Grab { float x, y, lx, ly, vx, vy, s0, ty0; long t, lt; String mode = ""; boolean cover, seek; int key; }

    /** 歌单开着时的一次按下：0 别处（左右拖转整个场景）、1 封面那一行、刻度条、键盘（交给歌单） */
    private static final class ShelfGrab { float x, y; long t; int mode; boolean moved; }

    private ShelfGrab sg;

    private Grab grab;

    private boolean onCover(float x, float y) { return Math.abs(x - cx) < C * 0.55f && Math.abs(y - cy) < C * 0.55f; }

    /** 手势（GL 线程里）：a 0 按下 / 1 移动 / 2 抬起 / 3 取消；nx、ny 是在画布上的比例（0～1）——画布不一定按密度缩（嵌在多窗口里时系统不缩），按比例换算最稳 */
    void touch(int a, float nx, float ny, long tms) {
        float ex = nx * W, ey = ny * H;
        float lx = ex - W / 2, ly = H / 2 - ey, fx = ex / W, fy = ey / H;
        if (shelf.engaged()) { // 歌单开着（或者在开 / 关的路上）：按在封面那一行、刻度条、键盘上交给歌单，别处左右拖转整个场景（松手弹回）
            if (grab != null) { grab = null; LY.drag = false; cvPress.t = 1; }
            if (a == 0) {
                sg = new ShelfGrab();
                sg.x = ex; sg.y = ey; sg.t = tms;
                sg.mode = shelf.claims(ex, ey) ? 1 : 0;
            }
            ShelfGrab q = sg;
            if (q == null) return;
            if (q.mode == 1) shelf.touch(a, ex, ey, tms);
            else if (a == 1) {
                float dx = ex - q.x;
                if (!q.moved && (Math.abs(dx) > 8 || Math.abs(ey - q.y) > 8)) q.moved = true;
                if (q.moved && q.mode == 0) tiltY.t = clamp(dx / W * 0.9f, -0.38f, 0.38f);
            } else if (a >= 2) {
                tiltY.t = 0;
            }
            if (a >= 2) sg = null;
            wake();
            return;
        }
        if (a == 0) {
            if (introFull() || grab != null || !ready) return;
            Grab g = new Grab();
            g.x = ex; g.y = ey; g.t = g.lt = tms; g.lx = ex; g.ly = ey;
            g.seek = seekHit(ex, ey);
            g.cover = !g.seek && onCover(lx, ly);
            g.key = g.seek ? -1 : pillHit(ex, ey);
            g.s0 = LY.scroll.x;
            g.ty0 = (fx - 0.5f) * 0.12f;
            grab = g;
            if (g.seek) { seekPress.t = 0.8f; LY.idleAt = clock; }
            else if (g.key >= 0) pillPress(g.key, true);
            else if (g.cover) cvPress.t = 0.95f;
            tiltX.t = (fy - 0.5f) * 0.09f;
            tiltY.t = (fx - 0.5f) * 0.12f;
            wake();
            return;
        }
        Grab g = grab;
        if (g == null) return;
        if (a == 1) {
            float dx = ex - g.x, dy = ey - g.y, dtm = Math.max(1, tms - g.lt);
            g.vx = g.vx * 0.4f + (ex - g.lx) / dtm * 0.6f;
            g.vy = g.vy * 0.4f + (ey - g.ly) / dtm * 0.6f;
            g.lx = ex; g.ly = ey; g.lt = tms;
            if (g.mode.isEmpty() && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) { g.mode = Math.abs(dx) > Math.abs(dy) ? "h" : "v"; cvPress.t = 1; seekPress.t = 1; if (g.key >= 0) pillPress(g.key, false); }
            tiltX.t = (fy - 0.5f) * 0.09f;
            tiltY.t = (fx - 0.5f) * 0.12f;
            if ("v".equals(g.mode) && g.cover) cvRx.t = clamp(dy / H * 1.8f, -0.7f, 0.7f);
            else if ("v".equals(g.mode)) { LY.drag = true; LY.scroll.set(g.s0 - dy); LY.scrollV = 0; LY.idleAt = clock; }
            else if ("h".equals(g.mode) && g.cover) { cvRy.t = clamp(dx / W * 2.4f, -1.4f, 1.4f); cvDx.t = dx * 0.18f; }
            else if ("h".equals(g.mode)) tiltY.t = clamp(g.ty0 + dx / W * 0.9f, -0.38f, 0.38f);
            wake();
            return;
        }
        boolean cancel = a == 3;
        if (g.mode.isEmpty() && !cancel && tms - g.t < 500) {
            // 点播放键才暂停 / 播放；点封面打开歌单（没有歌单时暂停 / 播放）；别处点一下不管（免得误触）
            if (g.seek) seekTap();
            else if (g.key == 0) host.press("previous"); else if (g.key == 2) host.press("next"); else if (g.key == 3) host.press("heart");
            else if (g.key == 1) host.press("toggle");
            else if (g.cover) { cvPress.v -= 1.5f; if (hasSongs) shelf.request(1); else host.press("toggle"); }
        } else if ("v".equals(g.mode) && g.cover) cvRx.t = 0;
        else if ("v".equals(g.mode)) { LY.scrollV = -g.vy * 1000; LY.idleAt = clock; }
        else if ("h".equals(g.mode) && g.cover) {
            float dx = ex - g.x;
            if (!cancel && (Math.abs(dx) > W * 0.22f || Math.abs(g.vx) > 0.5f)) host.press((dx != 0 ? dx : -g.vx) < 0 ? "next" : "previous");
            cvRy.t = 0;
            cvDx.t = 0;
        }
        if (g.key >= 0) pillPress(g.key, false);
        if (g.seek) { seekPress.t = 1; seekPress.v += 3; }
        LY.drag = false;
        cvPress.t = 1;
        tiltX.t = 0;
        tiltY.t = 0;
        grab = null;
        wake();
    }

    // ================= 出帧 =================
    float clock;
    private long t0 = -1, lastT;
    private int fps = 60;
    private float gapAvg = 16.7f;
    private long retryAt, retryGap = 20000;

    // sceneDirty：外面交进来的（媒体、深浅色、设置、手势……）都要重画场景；雨珠自己的定时器叫醒（rainWake）不用
    private volatile boolean sceneDirty = true;
    private boolean rainOnly;
    void wake() { sceneDirty = true; view.requestRender(); }
    private final Runnable rainWake = new Runnable() { @Override public void run() { view.requestRender(); } };

    // ================= 玻璃上的雨珠（见 Rain） =================
    private final Rain rain = new Rain();

    private volatile boolean bootWanted, bootIntro = true, fpsReset;
    private boolean btnHeld = true;

    private void btnWait(boolean w) { if (w != btnHeld) { btnHeld = w; host.btnWait(w); } }

    /** 主线程：数据有了（或者等够了），下一帧开始（建贴图、网格要在 GL 线程里）；intro 假的不放开场动画（换了画布重建时） */
    void boot(boolean intro) { bootIntro = intro; bootWanted = true; wake(); }

    private void doBoot() {
        if (ready) return;
        ready = true;
        seenTrack = MS.revTrack; seenLines = MS.revLines; seenCover = MS.revCover;
        songText();
        lines = songLines();
        useCover(coverNow(), "init");
        layout();
        DAY.set(dayWanted ? 1 : 0);
        rainToggle = true;
        if (stIntro && bootIntro) { titleSong(false); introStart("intro"); } else { lyricsSong(0, false); titleSong(false); btnWait(false); }
        wake();
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long t = SystemClock.uptimeMillis();
        if (t0 < 0) t0 = t;
        if (lastT != 0 && (fps == 30 || rainOnly) && t - lastT < 30) { try { Thread.sleep(33 - (t - lastT)); } catch (InterruptedException ignored) { } t = SystemClock.uptimeMillis(); }
        float dt = lastT != 0 ? Math.min(0.05f, (t - lastT) / 1000f) : 1 / 60f;
        // 帧率：设置里定了 60 / 30 就按设置；自动时画不过来降 30，过一阵再试 60，又没成就隔得更久再试（最长 5 分钟）。画中画窗口里连空场景都到不了稳定的 60
        if (fpsReset) { fpsReset = false; fps = "30".equals(fpsMode) ? 30 : 60; gapAvg = 16.7f; retryGap = 20000; retryAt = t + retryGap; }
        if (lastT != 0 && "auto".equals(fpsMode) && !rainOnly) {
            gapAvg = gapAvg * 0.95f + (t - lastT) * 0.05f;
            if (fps == 60 && gapAvg > 24) { fps = 30; retryAt = t + retryGap; retryGap = Math.min(300000, retryGap * 2); }
            else if (fps == 30 && t > retryAt) { fps = 60; gapAvg = 16.7f; }
            else if (fps == 60 && t > retryAt + 20000) retryGap = 20000; // 60 帧稳住了：下次降了从头算
        }
        lastT = t;
        clock = (t - t0) / 1000f;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, bw, bh);
        if (!ready && bootWanted && W > 1) doBoot();
        if (!ready || pal == null) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); lastT = 0; return; }
        boolean moving0 = false;
        String wf = wantFont;
        if (!wf.equals(fontNow)) { // 换了字体：歌词、歌名重画（同网页版 retext）
            fontNow = wf;
            Txt.use(Txt.load(view.getContext(), wf));
            measureSong(); lyricsSong(clock, false); titleSong(false);
        }

        syncMedia(clock);
        if (shelf.tickP(dt)) moving0 = true;
        long ms = MS.pos();
        int[] st = lyricState();
        boolean moving = moving0, full = introFull();
        float it = introT;
        if (DAY.step(dt)) moving = true;
        day = DAY.x;
        lerp3(ink, INK_N, INK_D, day);
        lerp3(hi, pal.hiN, pal.deep, day);
        if (introTick(dt)) moving = true;
        if (aurTick(dt)) moving = true;
        if (tintTick(dt)) moving = true;
        aurK = full ? smooth(0, 1.2f, it) : 1;
        if (tiltX.step(dt)) moving = true;
        if (tiltY.step(dt)) moving = true;
        sRx = tiltX.x + (float) Math.sin(clock * 0.13) * 0.025f;
        sRy = tiltY.x + (float) Math.sin(clock * 0.17) * 0.07f;
        G.compose(S, 0, 0, -D, sRx, sRy, 0, wpp, wpp, wpp);
        G.compose(lygM, px, py, 0, 0, yaw, 0, 1, 1, 1);
        float ribK = full ? smooth(0.3f, 1.6f, it) : 1;
        if (artTick(dt, full ? smooth(0.2f, 1.4f, it) : 1)) moving = true;
        float waterK = full ? smooth(0.2f, 1.4f, it) : 1;
        if (coverTick(dt, clock)) moving = true;
        if (trackCover) reportCover();
        if (lyricsTick(st, dt, clock)) moving = true;
        if (seekTick(dt)) moving = true;
        if (titleTick(dt, full ? smooth(1.4f, 2.1f, it) : 1)) moving = true;
        if (pillTick(dt, ms, full ? smooth(1.6f, 2.3f, it) : 1, clock)) moving = true;
        float dustK = full ? smooth(0.6f, 1.6f, it) : 1;

        // 封面贴图（只留正在用的）
        texFront = tex(bFront); texFront2 = tex(bFront2 != null ? bFront2 : bFront); texBack = tex(bBack);
        for (int i = 0; i < 3; i++) texArt[i] = tex(artL[i < artN ? i : 0]);
        if (shelf.tick(dt, clock)) moving = true; // 歌单的砖（用到上面的封面贴图）
        gcCovers();

        if (rainToggle) { rainToggle = false; rain.toggle(stRain, clock); }
        boolean rainMv = rain.tick(dt, clock), busy = MS.playing || moving || grab != null || shelf.touching();
        rainOnly = rain.on() && !busy; // 只有水珠在动：30 帧就够
        boolean still = rain.on() && !busy && !sceneDirty && rain.valid(); // 只有水珠在动：不重画场景，用拷下来的画面合成
        sceneDirty = false;
        if (!still) {
            aurRender();
            if (stWater) waterRender(waterK, ribK);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, bw, bh);
            GLES30.glDepthMask(true);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            drawScene(I, P, false, bw, bh, ribK, waterK, dustK);
            if (rain.on()) rain.grab(bw, bh);
        }
        if (rain.on()) rain.draw(day, bw, bh);

        if (busy || rainMv) view.requestRender();
        else {
            lastT = 0;
            if (rain.on()) { view.removeCallbacks(rainWake); view.postDelayed(rainWake, (long) Math.max(60, (rain.next() - clock) * 1000)); } // 水珠都挂着不动：到下一颗该滑的时候再叫醒
        }
    }

    // ================= 水面：真的平面倒影（同网页版：镜像一台相机，斜裁剪面，只画照进水里的那些） =================
    private final float[] waterM = new float[16], texM = new float[16], Vr = new float[16], Pr = new float[16];
    private boolean waterOk;

    // 每帧要用的小数组都先建好（不在画的时候新建，免得一直产生垃圾）
    private final float[] wLocal = new float[16], wWP = new float[3], wN = new float[3], wVW = new float[3], wTG = new float[3], wUP = new float[3],
        wA1 = new float[16], wA2 = new float[16], wNv = new float[4], wPv = new float[4], wIn = new float[4], wCL = new float[4], wQ4 = new float[4];
    private static final float[] BIAS = { 0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0.5f, 0, 0.5f, 0.5f, 0.5f, 1 };

    private void waterRender(float waterK, float ribK) {
        float[] local = wLocal;
        G.compose(local, 0, water, -1800, (float) (-Math.PI / 2), 0, 0, W * 4, 5300, 1);
        G.mul(waterM, S, local);
        float[] WP = wWP, N = wN;
        WP[0] = waterM[12]; WP[1] = waterM[13]; WP[2] = waterM[14];
        N[0] = waterM[8]; N[1] = waterM[9]; N[2] = waterM[10];
        float nl = (float) Math.sqrt(N[0] * N[0] + N[1] * N[1] + N[2] * N[2]);
        N[0] /= nl; N[1] /= nl; N[2] /= nl;
        // 相机在原点、朝 -z 看
        float[] VW = wVW;
        VW[0] = WP[0]; VW[1] = WP[1]; VW[2] = WP[2];
        waterOk = dot(VW, N) <= 0;
        if (!waterOk) return;
        reflect(VW, N); neg(VW); add(VW, WP);
        float[] TG = wTG; // WP − LA，LA = (0, 0, −1)
        TG[0] = WP[0]; TG[1] = WP[1]; TG[2] = WP[2] + 1;
        reflect(TG, N); neg(TG); add(TG, WP);
        float[] UP = wUP;
        UP[0] = 0; UP[1] = 1; UP[2] = 0;
        reflect(UP, N);
        Matrix.setLookAtM(Vr, 0, VW[0], VW[1], VW[2], TG[0], TG[1], TG[2], UP[0], UP[1], UP[2]);
        System.arraycopy(P, 0, Pr, 0, 16);
        float[] a1 = wA1, a2 = wA2;
        G.mul(a1, BIAS, Pr);
        G.mul(a2, a1, Vr);
        G.mul(texM, a2, waterM);
        // 斜裁剪面：水面下面的东西不进倒影
        float[] nv = wNv, pv = wPv, in = wIn;
        in[0] = N[0]; in[1] = N[1]; in[2] = N[2]; in[3] = 0;
        Matrix.multiplyMV(nv, 0, Vr, 0, in, 0);
        in[0] = WP[0]; in[1] = WP[1]; in[2] = WP[2]; in[3] = 1;
        Matrix.multiplyMV(pv, 0, Vr, 0, in, 0);
        float[] CL = wCL;
        CL[0] = nv[0]; CL[1] = nv[1]; CL[2] = nv[2]; CL[3] = -(nv[0] * pv[0] + nv[1] * pv[1] + nv[2] * pv[2]);
        float[] e = Pr;
        float[] Q4 = wQ4;
        Q4[0] = (Math.signum(CL[0]) + e[8]) / e[0]; Q4[1] = (Math.signum(CL[1]) + e[9]) / e[5]; Q4[2] = -1; Q4[3] = (1 + e[10]) / e[14];
        float sc = 2 / (CL[0] * Q4[0] + CL[1] * Q4[1] + CL[2] * Q4[2] + CL[3] * Q4[3]);
        for (int i = 0; i < 4; i++) CL[i] *= sc;
        e[2] = CL[0]; e[6] = CL[1]; e[10] = CL[2] + 1; e[14] = CL[3];
        waterFbo.bind();
        GLES30.glClearColor(0, 0, 0, 1);
        GLES30.glDepthMask(true); // 能写深度才清得掉（上一帧画透明的东西时关了）
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        drawScene(Vr, Pr, true, waterFbo.w, waterFbo.h, ribK, waterK, 0);
    }

    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static void reflect(float[] v, float[] n) { float d = 2 * dot(v, n); v[0] -= d * n[0]; v[1] -= d * n[1]; v[2] -= d * n[2]; }
    private static void neg(float[] v) { v[0] = -v[0]; v[1] = -v[1]; v[2] = -v[2]; }
    private static void add(float[] v, float[] w) { v[0] += w[0]; v[1] += w[1]; v[2] += w[2]; }

    // ================= 画一遍：主画面，或者倒影（只画照进水里的：背景、背景封面、光带、封面、歌词、控制条） =================
    final float[] MV = new float[16], M1 = new float[16], SM = new float[16], rC1 = new float[3], rC2 = new float[3], hHm = new float[16], hT2 = new float[16], dLm = new float[16];
    private static final float[][] RIB = { { 0, 0.02f, -420, 0.1f, 0, 1.1f }, { 1, 0.22f, -760, 0.08f, 2.3f, 1.6f } };
    private static final float[] ONE3 = { 1, 1, 1 };

    void mv(float[] V, float[] model) { G.mul(M1, S, model); G.mul(MV, V, M1); }
    private void mvS(float[] V) { G.mul(MV, V, S); }

    private void blendPremul(boolean on) {
        if (on) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA); } else GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawScene(float[] V, float[] Pp, boolean refl, int rw, int rh, float ribK, float waterK, float dustK) {
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        // 背景：极光铺满（跟着场景转的方向反着挪一点），四角压暗
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(false);
        blendPremul(false);
        pBg.use();
        pBg.tex("uAur", 0, aurFbo.tex);
        pBg.f2("uShift", -sRy * 0.08f, sRx * 0.08f);
        pBg.f("uDay", day);
        mFull.draw();
        // 不透明的：封面玻璃、控制条玻璃
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(true);
        boolean showCover = coverVisible && !shelf.engaged(); // 歌单开着：封面砖交给歌单（hero 砖）
        if (showCover) {
            pGlass.use();
            mv(V, coverM);
            pGlass.m4("uP", Pp); pGlass.m4("uMV", MV);
            pGlass.tex("uFront", 0, texFront); pGlass.tex("uFront2", 1, texFront2); pGlass.tex("uBack", 2, texBack); pGlass.tex("uAur", 3, aurFbo.tex);
            G.mul(SM, P, M1); pGlass.m4("uSM", SM);
            pGlass.f("uDim", gDim); pGlass.f("uSheen", gSheen); pGlass.f("uHalf", HALF); pGlass.f("uDay", day); pGlass.f("uFMix", gFMix);
            pGlass.c3("uTint", gTint);
            pGlass.f4("uRF", 0, 0, 1, 1); pGlass.f4("uRF2", 0, 0, 1, 1); pGlass.f4("uRB", 0, 0, 1, 1); pGlass.f2("uArt", 1, 1);
            mGlass.draw();
        }
        if (rise.x > 0.001f && mPill != null) {
            pPill.use();
            mv(V, pillM);
            pPill.m4("uP", Pp); pPill.m4("uMV", MV);
            pPill.tex("uAur", 0, aurFbo.tex);
            G.mul(SM, P, M1); pPill.m4("uSM", SM);
            pPill.f2("uHalf", pillLw / 2, pillLh / 2); pPill.f("uDay", day);
            pPill.c3("uTint", pal.c[0]);
            mPill.draw();
        }
        shelf.drawSolid(V, Pp); // 歌单的砖
        // 半透明的：按网页版的先后（背景封面、光带、水面、影子、浮尘、歌词、歌名、控制条的图形、光点）
        GLES30.glDepthMask(false);
        blendPremul(true);
        if (stArt) {
            pArt.use();
            mv(V, artM);
            pArt.m4("uP", Pp); pArt.m4("uMV", MV);
            pArt.tex("uA", 0, texArt[0]); pArt.tex("uB", 1, texArt[1]); pArt.tex("uC", 2, texArt[2]);
            pArt.f3("uW", artE[0], artE[1], artE[2]); pArt.f("uNarrow", narrow ? 1 : 0); pArt.f("uK", artK); pArt.f("uDay", day); G.mul(SM, P, M1); pArt.m4("uSM", SM);
            mUnit.draw();
        }
        if (stRibbons) {
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            pRib.use();
            mvS(V);
            pRib.m4("uP", Pp); pRib.m4("uMV", MV);
            float[] w = ONE3, c1 = rC1, c2 = rC2;
            for (float[] o : RIB) {
                boolean second = o[0] > 0;
                lerp3(c1, second ? aC2 : aC1, w, 0.25f * (1 - day));
                lerp3(c2, second ? aC3 : aC2, w, 0.25f * (1 - day));
                pRib.f("uT", clock); pRib.f("uLen", W * 2.2f); pRib.f("uW", H * (second ? 0.16f : 0.24f)); pRib.f("uY", H * o[1]); pRib.f("uZ", o[2]);
                pRib.f("uAmp", H * o[3]); pRib.f("uPh", o[4]); pRib.f("uTw", o[5]); pRib.c3("uC1", c1); pRib.c3("uC2", c2); pRib.f("uK", ribK); pRib.f("uDay", day);
                mStrip.draw();
            }
            GLES30.glEnable(GLES30.GL_CULL_FACE);
        }
        if (!refl && stWater && waterOk) {
            GLES30.glDepthMask(true);
            pWater.use();
            G.mul(MV, V, waterM);
            pWater.m4("uP", Pp); pWater.m4("uMV", MV); pWater.m4("uTexM", texM);
            pWater.f2("uSize", W * 4, 5300);
            pWater.tex("uRefl", 0, waterFbo.tex);
            pWater.f("uT", clock); pWater.f("uK", waterK); pWater.f("uDay", day); pWater.c3("uTint", aC1);
            mUnit.draw();
            GLES30.glDepthMask(false);
        }
        if (!refl && showCover) {
            pHalo.use();
            float[] hm = hHm, t2 = hT2;
            Matrix.setIdentityM(t2, 0);
            Matrix.translateM(t2, 0, 0, 0, -0.09f);
            G.mul(hm, coverM, t2);
            mv(V, hm);
            pHalo.m4("uP", Pp); pHalo.m4("uMV", MV);
            pHalo.c3("uTint", haloTint); pHalo.f("uK", haloK); pHalo.f("uDay", day);
            mHalo.draw();
        }
        if (!refl) shelf.drawUnder(V, Pp); // 水面上的光、中间那块的影子
        if (!refl && dustK > 0) {
            pDust.use();
            mvS(V);
            pDust.m4("uP", Pp); pDust.m4("uMV", MV);
            pDust.f("uT", clock); pDust.f2("uWH", W, H); pDust.f("uPx", D * DPR); pDust.f("uK", dustK); pDust.f("uDay", day);
            mDust.draw();
        }
        // 歌词定位线（在歌词后面，不照进水里）、歌词
        if (!refl && seekVis.x > 0.002f) drawSeek(V, Pp);
        pLine.use();
        pLine.m4("uP", Pp);
        pLine.f("uDay", day); pLine.c3("uInk", ink); pLine.c3("uHi", hi); pLine.c3("uTint", day > 0.5f ? pal.deep : pal.accent); pLine.f("uLift", Fpx * 0.09f);
        float[] lm = dLm;
        for (LineObj o : LY.leaving) drawLine(o, V, lm);
        for (LineObj o : LY.objs.values()) drawLine(o, V, lm);
        // 歌名、歌手（不照进水里）
        if (!refl) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            pText.use();
            pText.m4("uP", Pp); pText.c3("uInk", ink);
            if (TT.cur != null) drawTitle(TT.cur, V);
            for (Title o : TT.old) drawTitle(o, V);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        }
        shelf.drawText(V, Pp); // 歌单：中间那首的歌名、刻度条上的线和点
        // 控制条上的图形、进度圈、时间
        if (rise.x > 0.001f && pillK > 0.002f && timeTex != 0) {
            pIcons.use();
            mv(V, iconsM);
            pIcons.m4("uP", Pp); pIcons.m4("uMV", MV);
            pIcons.f("uW", pillLw); pIcons.f("uH", pillLh); pIcons.f("uPlay", play.x); pIcons.f("uProg", dur > 0 ? clamp(MS.pos() / (float) dur, 0, 1) : 0);
            pIcons.f3("uPress", press[0].x, press[1].x, press[2].x); pIcons.f3("uHeart", heartVis.x, heartFill.x, press[3].x); pIcons.f("uBx", pillBx); pIcons.f("uHx", pillHx); pIcons.c3("uInk", ink); pIcons.c3("uAcc", pal.accent); pIcons.tex("uTime", 0, timeTex);
            pIcons.f("uK", pillK); pIcons.f("uDay", day);
            mUnit.draw();
        }
        // 光点
        boolean shP = !partVisible && shelf.partOn(); // 歌单进出的光点
        if (!refl && (partVisible || shP)) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            pPart.use();
            mvS(V);
            pPart.m4("uP", Pp); pPart.m4("uMV", MV);
            pPart.f("uT", shP ? shelf.partT() : partT); pPart.f("uFade", shP ? shelf.partFade() : partFade); pPart.f("uPx", D * DPR); pPart.f("uDay", day); pPart.c3("uEnd", hi);
            pPart.f("uRel", shP ? 1 : 0); if (shP) pPart.m4("uHero", shelf.heroM);
            mPart.draw();
        }
        if (!refl) shelf.drawPad(Pp); // 歌单的搜索键盘：最上面
        blendPremul(false);
        GLES30.glDepthMask(true);
    }

    private void drawLine(LineObj o, float[] V, float[] lm) {
        if (o.mesh == null || o.tex == 0 || o.uAlpha <= 0.002f) return;
        G.mul(lm, lygM, o.m);
        mv(V, lm);
        pLine.m4("uMV", MV);
        pLine.tex("uMap", 0, o.tex);
        pLine.f("uAlpha", o.uAlpha); pLine.f("uBlur", o.uBlur); pLine.f("uAct", o.uAct); pLine.f("uPulse", o.uPulse);
        o.mesh.draw();
    }

    private void drawTitle(Title o, float[] V) {
        if (o.alpha <= 0.002f) return;
        mv(V, o.Mt);
        pText.m4("uMV", MV); pText.tex("uMap", 0, o.tt); pText.f("uA", o.alpha); pText.f("uK", 1);
        o.mt.draw();
        mv(V, o.Ma);
        pText.m4("uMV", MV); pText.tex("uMap", 0, o.ta); pText.f("uK", 0.6f);
        o.ma.draw();
    }
}
