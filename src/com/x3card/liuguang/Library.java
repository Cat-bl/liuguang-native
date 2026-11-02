package com.x3card.liuguang;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.opengl.Matrix;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 曲库（3D，"分层唱片架"）：左上角一颗返回键（同点空白、返回键：关掉曲库），后面一排「多媒体」的音源（车机媒体控制器给的图标，点了切源），下面一排一个分类，每排是立在水面上的玻璃砖（同歌单长廊的砖）：
 * 我的（我喜欢、每日推荐……）/ 歌手（放过的歌里攒的，ArtistBook；排头"找歌手"升起拼音键盘筛）/ 风格 / 语种 / 心情。
 * 上下滑换一排（按整排停），左右滑这一排（惯性、两头有阻力），两头的砖微微转向里、往后退，像一面弧形的唱片架。
 * 左边一条是分类索引（每排自己不带标题）：五排的名字，正在看的几排亮、左边一道强调色的竖条跟着滚，点哪个滚到哪排；砖往左滑到它跟前缩小收进去。
 * 一排往右滑开一块多，这一排最左边（索引旁边）浮出一颗"回到最前面"的小圆键，点了这一排滑回第一块。
 * "我的"那排最前面是"搜歌"：升起拼音键盘，停手 0.6 秒（车上点大键盘，两个键之间常常隔半秒以上）按当前（或者要切到的）在线音乐搜（主线程：SongFinder），结果一排砖挪到键盘上面（别的排藏起来），
 * 滑到快到头时接着要下一页（排尾一块"加载中"）；点一首插进播放队列的下一首马上放（主线程：Player.insertSong / searchMusic），留在搜歌里，放上的那块外圈一直亮着。
 * 打的是拼音（字母）时停手 0.25 秒要联想词，在输入行里打的字后面排成一排候选词（中文），点一个换成它马上搜——首字母各家的搜索认不全（sjzx 搜不到世界中心），换成中文就准了。
 * 这些都是在线音乐（QQ、网易云、酷狗、酷我）里的内容：当前不是在线音乐时（本地、蓝牙、电台……），砖排上方一行"点下面的内容，会切到 X 播放"，
 * 点砖时主线程先切到 X 再发（Data.target）。
 * 点一块：它外圈呼吸，发给「多媒体」（主线程：Player.voice），等媒体控制器推来新的播放队列——主线程交回 result(1)，曲库收起、长廊打开停在正在放的那首；
 * 没换成交回 result(2)（主线程提示原因）。点音源：切过去（主线程：BydMedia.changeSource），曲库不关。
 * 进出：一个进度 q（同长廊的 p：按速度走，中途反悔原路退回）。进：歌词页 / 长廊沉下去让开，音源那排从上面落下，分类一排排从水里浮上来；出：倒着放。
 * 从长廊进的：长廊一直开着，只是沉在水下（Shelf.hideK），出来时浮回去。
 * 砖的图（256 见方）在用到时才做：生成的（渐变 + 图形 + 名字）在 GL 线程里画，一帧最多两块；歌手的照片按名字查（ArtistPics，查不到用他最近一首歌的封面），图走 SongCovers（后台线程），取到了叠上名字。
 * 两秒没画到的砖贴图还掉；关完了全部释放。全在 GL 线程里（数据、开关、结果由主线程交进来）
 */
final class Library {
    private final Scene sc;
    volatile SongCovers art;

    Library(Scene sc) { this.sc = sc; }

    // ================= 主线程交进来的 =================
    /** 音源（控制器的音源号）、现在的音源、各音源图标（iconSeq 变了才重传）、攒的歌手（artistSeq 变了才重排） */
    static final class Data {
        final int[] sources;
        final int src, target, iconSeq, artistSeq; // target：当前不是在线音乐时点砖会切到的在线音乐（-1：就在当前音源里放）
        final android.util.SparseArray<Bitmap> icons;
        final String[] names, covers;
        Data(int[] sources, int src, int target, android.util.SparseArray<Bitmap> icons, int iconSeq, String[] names, String[] covers, int artistSeq) {
            this.sources = sources; this.src = src; this.target = target; this.icons = icons; this.iconSeq = iconSeq; this.names = names; this.covers = covers; this.artistSeq = artistSeq;
        }
    }

    private volatile Data in;
    private volatile int req;    // 1 从歌词页打开、2 从长廊打开、3 关上、4 返回（先收键盘）
    private volatile int result; // 1 换好了、2 没换成

    void setData(Data d) { in = d; }
    void request(int r) { req = r; }
    void result(int r) { result = r; }

    // ================= 分类 =================
    private static final int K_PLAY = 0, K_SEARCH = 1, K_FIND = 2, K_SONGS = 3, K_HIT = 4, K_MORE = 5;
    private static final String[][] FOR = { { "我喜欢", "playFavorite" }, { "每日推荐", "playRecommend" }, { "猜你喜欢", "playGuessYouLike" },
        { "最近播放", "playLately" }, { "第一个歌单", "playUserCreateSongList" } }; // 创建的歌单里排第一的那个（「多媒体」只认第一个；playMySongList 是收藏的歌单）
    private static final String[][] GENRE = { { "流行", "genre" }, { "摇滚", "genre" }, { "民谣", "genre" }, { "电子", "genre" }, { "说唱", "genre" }, { "R&B", "genre" },
        { "爵士", "genre" }, { "古典", "genre" }, { "轻音乐", "genre" }, { "古风", "genre" } };
    private static final String[][] LANG = { { "华语", "lang" }, { "粤语", "lang" }, { "欧美", "area" }, { "日语", "lang" }, { "韩语", "lang" }, { "闽南语", "lang" } };
    private static final String[][] MOOD = { { "伤感", "tags" }, { "快乐", "tags" }, { "治愈", "tags" }, { "放松", "tags" }, { "浪漫", "tags" }, { "安静", "tags" },
        { "励志", "tags" }, { "开车", "tags" }, { "夜晚", "tags" }, { "运动", "tags" } };
    private static final int[] FOR_HUE = { 340, 28, 268, 200, 160 };

    private static final class Item {
        final String key, name, method, field, value;
        final int kind, glyph;
        final float hue;
        String cover = "";
        int tex, used;
        float at, picAt; // picAt：开始等歌手照片的时候
        boolean asked;
        SongFinder.Hit hit; // 搜歌搜到的那首（K_HIT）
        final Spring press = new Spring(1, 300, 18);
        Item(String key, String name, int kind, String method, String field, String value, int glyph, float hue) {
            this.key = key; this.name = name; this.kind = kind; this.method = method; this.field = field; this.value = value; this.glyph = glyph; this.hue = hue;
        }
    }

    private static final class Row {
        final String title;
        List<Item> items = new ArrayList<>();
        final Spring pos = new Spring(0, 49, 14, 0.0004f);
        int lt;           // 索引里的名字
        G.Mesh ml;
        float tw, tpad, y, vis = 1;
        float hk;         // "回到最前面"的键显示多少；hm 键的玻璃、him 键上的图形
        final float[] hm = new float[16], him = new float[16];
        Row(String t) { title = t; }
    }

    private final Row[] rows = { new Row("我的"), new Row("歌手"), new Row("风格"), new Row("语种"), new Row("心情") };
    private final Map<String, Item> artistItems = new HashMap<>();
    private final Item find = new Item("find", "找歌手", K_FIND, null, null, null, 6, 210);
    private final Item songs = new Item("songs", "搜歌", K_SONGS, null, null, null, 6, 190);
    private final Item more = new Item("more", "加载中", K_MORE, null, null, null, 7, 220);
    private static final String ARTIST_HINT = "打歌手的拼音，比如 zhoujielun、zjl（周杰伦）", SONG_HINT = "打拼音，点上面的字，比如 shijiezhongxin → 世界中心";
    private String[] artistNames = new String[0];
    private int artistSeen = -1;

    {
        for (int i = 0; i < FOR.length; i++) rows[0].items.add(new Item("for:" + FOR[i][1], FOR[i][0], K_PLAY, FOR[i][1], null, null, i, FOR_HUE[i]));
        addWords(rows[2], GENRE);
        addWords(rows[3], LANG);
        addWords(rows[4], MOOD);
        rows[1].items.add(find);
        rows[0].items.add(0, songs);
    }

    /** 第 i 排：rows 后面多一排搜歌的结果（不在索引里） */
    private Row row(int i) { return i == rows.length ? hits : rows[i]; }

    private static void addWords(Row r, String[][] w) {
        for (String[] x : w) r.items.add(new Item(x[1] + ":" + x[0], x[0], K_SEARCH, "searchMusic", x[1], x[0], -1, (x[0].hashCode() & 0x7fffffff) % 360));
    }

    // ================= 状态 =================
    float q;               // 0 关、1 开
    private float qv;
    private boolean want, fromShelf;
    private static final float DUR = 1.15f;
    private Item pend;     // 点了、在等新队列的那块
    private float pendAt;
    private int srcPend = -1;
    private float srcPendAt;

    boolean engaged() { return want || q > 0; }
    boolean opening() { return want; }
    /** 歌词页 / 长廊让开多少：只占 q 靠 0 的那一段（开的时候歌词页先退场、砖再起来；关的时候砖差不多沉完了歌词页才回来） */
    float coverK() { return q <= 0 ? 0 : smooth(0, 0.35f, q); }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
    private static float smooth(float a, float b, float v) { float t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static float easeOut(float t) { float u = 1 - t; return 1 - u * u * u; }

    // ================= 布局（世界坐标：曲库立在 z = Z 处；1 个单位在画面上是 fz 个场景像素） =================
    private static final float Z = 40;
    private float fz = 1, T = 1, pitch = 1, rowGap = 1, xL, rowsTop, rowsBot, titleH, srcY, sh = 1, sw = 1, sPitch = 1, xMax = 1;
    private float capF = 1; // 音源那排挤一挤缩小多少（这一帧）
    private String meshKey = "";
    private G.Mesh mCap, mBack;
    private float capZ, backX;
    private int backTex, homeTex; // 返回键上的"‹"、回到最前面的"|‹"
    private final float[] backM = new float[16], backIm = new float[16];
    // 左边的索引：名字的字号 ils（场景像素），竖条在 ixBar，名字从 ixText 起，两个名字隔 iStep，中心高 iy；砖排的左界 xEdge
    private float ils = 1, iStep = 1, ixBar, ixText, iy, xEdge;
    private int white; // 画竖条用的白贴图
    // 提示"点下面的内容，会切到 X 播放"：给哪个音源做的、贴图、显示多少
    private float hintY, hintA, hintW, hintPad;
    private int hintFor = -1, hintTex;
    private G.Mesh hintMesh;

    void layout() {
        float W = sc.W, H = sc.H;
        boolean nar = sc.narrow;
        fz = sc.dpx / (sc.dpx - Z);
        xMax = W / 2 / fz;
        srcY = (H / 2 - H * (nar ? 0.075f : 0.085f)) / fz;
        sh = H * (nar ? 0.058f : 0.068f) / fz;
        sw = sh * 1.5f;
        sPitch = sw * 1.2f;
        rowsTop = srcY - sh * 0.5f - H * 0.065f / fz; // 音源那排底下留出一行提示的位置
        hintY = srcY - sh * 0.5f - H * 0.04f / fz;
        rowsBot = (sc.water + H * 0.015f) / fz;
        titleH = H * 0.012f / fz; // 每排不带标题（名字在左边的索引里），只留一点空
        ils = H * (nar ? 0.027f : 0.03f);
        iStep = ils * 2.3f / fz;
        ixBar = (-W / 2 + W * (nar ? 0.04f : 0.035f)) / fz;
        ixText = ixBar + ils * 0.75f / fz;
        xEdge = ixText + ils * 2.6f / fz; // 两个字宽，再空一点
        iy = (rowsTop + rowsBot) / 2;
        // 砖按宽度最大多大（一排露两块多 / 四块），再看水面以上放得下几整排（玻璃砖不透明，淡不掉：半排露在外面会被水面切开）：
        // 砖不比按宽度的小太多就多放一排，窄窗口竖着也排满
        float area = rowsTop - rowsBot, Tw = (xMax - xEdge) / (nar ? 2.4f : 4f) / 1.16f;
        rowsFit = 2;
        for (int k = 4; k > 2; k--) if (area / k * 0.9f - titleH >= Tw * 0.85f) { rowsFit = k; break; }
        rowGap = area / rowsFit;
        T = Math.min(rowGap * 0.9f - titleH, Tw);
        pitch = T * 1.16f;
        xL = xEdge + T * 0.62f;
        backX = ixBar + sh * 0.5f - ils * 0.3f / fz; // 返回键左边和索引的竖条对齐
        String key = Math.round(sw * 10) + "x" + Math.round(sh * 10);
        if (!key.equals(meshKey)) { if (mCap != null) { mCap.free(); mCap = null; } if (mBack != null) { mBack.free(); mBack = null; } }
        meshKey = key;
        for (Row r : rows) freeLabel(r); // 字号变了：重写
    }

    /** 第 r 排的砖中心高度：在自己那一格里上下居中（rowPos：滚到第几排在最上面） */
    private float rowY(int r, float rp) { return rowsTop - (r - rp + 0.5f) * rowGap; }

    private int rowsFit = 2;
    private int fitRows() { return rowsFit; }
    private float maxRow() { return Math.max(0, rows.length - fitRows()); }
    /** 索引里第 i 个名字离胶囊中心多高 */
    private float idxY(float i) { return iy + ((rows.length - 1) * 0.5f - i) * iStep; }

    // ================= 开 / 关 =================
    private final Spring rowS = new Spring(0, 49, 14, 0.0004f), srcS = new Spring(0, 49, 14, 0.0004f);

    private void open(boolean shelf) {
        if (want) return;
        if (q == 0) { fromShelf = shelf; engage(); }
        want = true;
        sc.host.libraryState(true);
    }

    private void close() {
        if (!want) return;
        want = false;
        float top = Math.max(0.8f, 0.7f + 0.09f * (fitRows() - 1)); // 索引、看得见的每一排都到位时的 q（见 layoutAll）：再往上画面不变
        if (q > top) q = top; // 关的时候从这里退，不空等
        if (sc.kb.isOpen(kbo)) closePad();
        sc.host.libraryState(false);
    }

    // ================= 每帧 =================
    /** 先走：开关、结果、进度 q（场景其它东西要用 coverK） */
    boolean tickQ(float dt) {
        int r = req;
        if (r != 0) {
            req = 0;
            if (r == 1 || r == 2) open(r == 2);
            else if (r == 4 && sc.kb.isOpen(kbo)) closePad();
            else close();
        }
        int res = result;
        if (res != 0) {
            result = 0;
            if (res == 1 && want) { // 换好了：回长廊（从长廊进的长廊还开着，跟着浮回去；从歌词页进的打开长廊）
                pend = null;
                close();
                if (sc.shelf.engaged()) sc.shelf.locate(); else sc.shelf.request(1);
            } else pend = null;
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

    private int frame;
    private Data data;
    private int iconSeen = -1;
    private final android.util.SparseIntArray iconTex = new android.util.SparseIntArray();

    /** 摆砖、做图、键盘 */
    boolean tick(float dt, float time) {
        if (!engaged()) return false;
        frame++;
        boolean moving = q < 1 || want != (q == 1);
        takeData();
        drain(time);
        SongIndex b = built;
        if (b != null) { built = null; indexing = false; index = b; if (sc.kb.isOpen(kbo) && !songMode) applyQuery(); }
        boolean hDrag = g != null && g.axis == 1; // 拖着的那一排跟手（在 touch 里改），不走弹簧
        if (!(g != null && g.axis == 2) && rowS.step(dt)) moving = true;
        if (!(hDrag && g.row == -1) && srcS.step(dt)) moving = true;
        for (int i = 0; i <= rows.length; i++) if (!(hDrag && g.row == i) && row(i).pos.step(dt)) moving = true;
        if (songMode && songTick(time)) moving = true;
        if (pend != null && time - pendAt > 12) pend = null; // 主线程会先交回结果；万一没交回来也别一直闪
        if (srcPend >= 0 && (data != null && data.src == srcPend || time - srcPendAt > 6)) srcPend = -1;
        if (pend != null || srcPend >= 0) moving = true;
        if (layoutAll(dt, time)) moving = true;
        if (frame % 30 == 0) trimTex();
        return moving;
    }

    private void takeData() {
        Data d = in;
        if (d == null || d == data) return;
        data = d;
        if (d.iconSeq != iconSeen) { // 图标换了：重传
            iconSeen = d.iconSeq;
            for (int i = 0; i < iconTex.size(); i++) G.free(iconTex.valueAt(i));
            iconTex.clear();
        }
        if (d.artistSeq != artistSeen) { artistSeen = d.artistSeq; artistNames = d.names; rebuildArtists(null); index = null; }
    }

    /** 歌手那一排：排头"找歌手"，后面是攒的歌手（在搜时只排搜到的） */
    private void rebuildArtists(int[] only) {
        Data d = data;
        List<Item> l = new ArrayList<>();
        if (only == null) l.add(find);
        int n = d == null ? 0 : d.names.length;
        if (only == null) for (int i = 0; i < n; i++) l.add(artist(d.names[i], d.covers[i]));
        else for (int i : only) if (i < n) l.add(artist(d.names[i], d.covers[i]));
        rows[1].items = l;
        Row r = rows[1];
        if (r.pos.x > l.size() - 1) r.pos.set(Math.max(0, l.size() - 1));
    }

    private Item artist(String name, String cover) {
        Item it = artistItems.get(name);
        if (it == null) { it = new Item("artist:" + name, name, K_SEARCH, "searchMusic", "artist", name, -1, 0); artistItems.put(name, it); }
        if (!cover.equals(it.cover) && !it.asked) it.cover = cover;
        return it;
    }

    // ================= 摆 =================
    private static final class Tile { Item it; int row; float halo, dim; final float[] m = new float[16]; }
    private final Tile[] tiles = new Tile[64];
    { for (int i = 0; i < tiles.length; i++) tiles[i] = new Tile(); }
    private int nt;
    private static final class Cap { int src, idx; float k, halo; final float[] m = new float[16], im = new float[16]; }
    private final Cap[] caps = new Cap[16];
    { for (int i = 0; i < caps.length; i++) caps[i] = new Cap(); }
    private int nc;
    private int made; // 这一帧做了几块生成的图
    private float kS, kI; // 音源那排、索引显示多少
    private final float[] tmp = new float[16];

    private boolean layoutAll(float dt, float time) {
        boolean moving = false;
        nt = 0; nc = 0; made = 0;
        float padK = sc.kb.k(kbo), rp = rowS.x;
        // ---- 音源 ----
        Data d = data;
        kS = easeOut(smooth(0.08f, 0.6f, q)) * (1 - padK);
        if (d != null && d.sources.length > 0 && kS > 0.002f) {
            if (mCap == null) { float bv = sh * 0.12f; mCap = G.extrude(G.stadium(sw - bv * 2, sh - bv * 2, 16), sh * 0.18f, bv * 0.8f, bv, 3, 0); capZ = bv * 0.8f + 0.5f; }
            int n = Math.min(d.sources.length, caps.length);
            // 放在返回键右边：放得下就在画面上居中（碰到返回键就往右挪）；差一点放不下就整排缩小一点、靠紧（一成五以内），
            // 还放不下就照原样从返回键右边排起、能左右滑
            float lo = backX + sh * 0.5f + sPitch - sw * 0.5f, hiC = xMax * 0.94f - sw / 2, total = (n - 1) * sPitch;
            float f = n > 1 ? (hiC - lo) / total : 1;
            capF = f >= 1 ? 1 : f >= 0.85f ? f : 1;
            float pitchS = sPitch * capF;
            total = (n - 1) * pitchS;
            float x0 = Math.max(lo, -total / 2);
            boolean scroll = x0 + total > hiC + sw * 0.05f;
            if (scroll) x0 = lo;
            if (!scroll && srcS.x != 0) srcS.set(0);
            float y = srcY + (1 - kS) * sh * 3;
            for (int i = 0; i < n; i++) {
                float x = x0 + (i - (scroll ? srcS.x : 0)) * pitchS;
                if (Math.abs(x) > xMax + sw) continue;
                float over = lo - x, ek = over > 0 ? 1 - smooth(0, sw * 0.9f, over) : 1; // 滑到返回键跟前缩小收进去
                if (ek <= 0.02f) continue;
                Cap c = caps[nc++];
                c.src = d.sources[i]; c.idx = i;
                boolean cur = c.src == d.src;
                c.k = kS * (cur ? 1 : 0.62f);
                c.halo = c.src == srcPend ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(time * 6)) : cur ? 1 : 0;
                float s = (cur ? 1.08f : 1) * ek * capF;
                G.compose(c.m, x, y, Z + (cur ? sh * 0.3f : 0), 0.04f, -x / xMax * 0.25f, 0, s, s, s);
                G.compose(tmp, 0, 0, capZ, 0, 0, 0, sh * 0.62f, sh * 0.62f, 1);
                G.mul(c.im, c.m, tmp);
                if (iconTex.get(c.src, 0) == 0 && d.icons.get(c.src) != null && !d.icons.get(c.src).isRecycled()) iconTex.put(c.src, G.texture(d.icons.get(c.src), true));
            }
        }
        // ---- 返回键 ----
        if (kS > 0.002f) {
            if (mBack == null) { float bv = sh * 0.12f; mBack = G.extrude(G.stadium(sh - bv * 2, sh - bv * 2, 16), sh * 0.18f, bv * 0.8f, bv, 3, 0); capZ = bv * 0.8f + 0.5f; }
            if (backTex == 0) backTex = glyph(false);
            G.compose(backM, backX, srcY + (1 - kS) * sh * 3, Z, 0.04f, -backX / xMax * 0.25f, 0, 1, 1, 1);
            G.compose(tmp, 0, 0, capZ, 0, 0, 0, sh * 0.5f, sh * 0.5f, 1);
            G.mul(backIm, backM, tmp);
        }
        // ---- 分类 ----
        for (int r = 0; r <= rows.length; r++) {
            Row row = row(r);
            if (r == rows.length && (!songMode || padK <= 0.002f)) { row.vis = 0; row.hk = 0; continue; }
            float y = rowY(r, rp);
            float vis = 1 - smooth(0, rowGap * 0.5f, y + T / 2 + titleH - rowsTop) - smooth(0, rowGap * 0.5f, rowsBot - (y - T / 2));
            float hideK = 0; // 键盘升起来时别的排让开多少：边沉边缩到没有（砖不透明，淡不掉），不压暗
            if (padK > 0 && r == (songMode ? rows.length : 1)) { // 找歌手、搜歌：这一排挪到键盘上面
                float yp = (sc.kb.top() + sc.H * 0.03f) / fz + T / 2;
                y = mix(y, yp, padK);
                vis = mix(vis, 1, padK);
            } else hideK = padK;
            int vr = (int) Math.max(0, r - Math.floor(rp));
            float a = 0.2f + 0.09f * vr, rk = easeOut(smooth(a, a + 0.5f, q));
            row.y = y - (1 - rk) * rowGap * 0.9f - hideK * hideK * rowGap * 0.5f;
            row.vis = clamp(vis, 0, 1) * rk * (1 - hideK);
            row.hk = 0;
            if (row.vis <= 0.03f) continue;
            if (mBack != null) { // 往右滑开一块多：最左边浮出"回到最前面"
                row.hk = smooth(0.6f, 1.6f, row.pos.x) * row.vis;
                if (row.hk > 0.002f) {
                    float hs = 0.8f * (0.6f + 0.4f * row.hk);
                    G.compose(row.hm, xEdge + sh * 0.42f, row.y, Z + T * 0.15f, 0.03f, 0.1f, 0, hs, hs, hs);
                    G.compose(tmp, 0, 0, capZ, 0, 0, 0, sh * 0.5f, sh * 0.5f, 1);
                    G.mul(row.him, row.hm, tmp);
                    if (homeTex == 0) homeTex = glyph(true);
                }
            }
            float rs = (0.5f + 0.5f * clamp(vis, 0, 1)) * (1 - hideK); // 滚出去的那排缩小（砖不透明，不能淡掉）
            float ps = row.pos.x, sx = row.hk * sh * 0.75f; // "回到最前面"出来时整排往右让出它的位置
            for (int i = 0; i < row.items.size(); i++) {
                float x = xL + sx + (i - ps) * pitch;
                if (x - T > xMax || x + T < -xMax) continue;
                if (nt >= tiles.length) break;
                float over = xEdge + sx + T * 0.1f - (x - T / 2), ek = over > 0 ? 1 - smooth(0, T * 0.8f, over) : 1; // 滑到索引（或"回到最前面"）跟前缩小收进去
                if (ek <= 0.02f) continue;
                Item it = row.items.get(i);
                if (it.press.step(dt)) moving = true;
                float u = clamp(x / xMax, -1.2f, 1.2f), ti = easeOut(smooth(a + 0.02f * Math.min(Math.max(0, i - ps), 8), a + 0.5f, q));
                if (ti <= 0.002f) continue;
                Tile t = tiles[nt++];
                t.it = it; t.row = r;
                float s = T * it.press.x * (0.75f + 0.25f * ti) * smooth(0, 0.45f, ti) * rs * ek; // 进出时边沉边缩到没有（砖不透明，淡不掉）
                float yy = row.y - (1 - ti) * rowGap * 0.4f;
                G.compose(t.m, x, yy, Z - u * u * T * 0.35f, 0.03f, -u * 0.32f, 0, s, s, s);
                t.dim = clamp(vis, 0, 1); // 只按滚出去多少压暗，进出时不压黑
                t.halo = it == pend ? 0.55f + 0.45f * (float) Math.sin(time * 6) : it == nowHit ? 0.5f : 0; // 在等的闪、刚放上的一直亮
                it.used = frame;
                if (ensureTex(it, time)) moving = true;
                if (it.tex != 0 && time - it.at < 0.3f) moving = true;
            }
        }
        // ---- 提示：要切到哪个在线音乐 ----
        int tg = d == null ? -1 : d.target;
        float goal = tg >= 0 ? 1 : 0;
        hintA += (goal - hintA) * (1 - (float) Math.exp(-dt / 0.12f));
        if (Math.abs(goal - hintA) < 0.003f) hintA = goal; else moving = true;
        if (tg >= 0 && tg != hintFor) { // 换了一个（消失时留着旧的淡出）
            freeHint();
            hintFor = tg;
            Txt.Label l = Txt.label("点下面的内容，会切到" + BydMedia.onlineName(tg) + "播放", ils * 0.78f, sc.W * 0.8f, false);
            hintTex = G.texture(l.bmp, false);
            l.bmp.recycle();
            hintMesh = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
            hintW = l.w; hintPad = l.pad;
        }
        if (hintFor >= 0 && iconTex.get(hintFor, 0) == 0 && d != null && d.icons.get(hintFor) != null && !d.icons.get(hintFor).isRecycled()) iconTex.put(hintFor, G.texture(d.icons.get(hintFor), true));
        // ---- 索引（从左边滑进来） ----
        kI = easeOut(smooth(0.3f, 0.8f, q)) * (1 - padK);
        if (kI > 0.002f) {
            if (white == 0) { Bitmap b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE); white = G.texture(b, false); b.recycle(); }
            for (Row r : rows) if (ensureLabel(r)) moving = true;
        }
        return moving;
    }

    // ================= 砖的图 =================
    private static final int ART = 256;
    private int gen;
    private volatile boolean alive;

    private static final class Got { final int gen; final String key; final Bitmap b; Got(int g, String k, Bitmap b) { gen = g; key = k; this.b = b; } }
    private final ConcurrentLinkedQueue<Got> got = new ConcurrentLinkedQueue<>();
    private final Map<String, List<Item>> waiting = new HashMap<>(); // 封面的键（Song.art）→ 在等它的歌手（几个人可能同一张专辑封面）

    private final SongCovers.Sink sink = new SongCovers.Sink() {
        @Override public void got(String key, boolean big, Bitmap[] mips, Palette pal, boolean fake) {
            Bitmap b = mips == null ? null : mips[0];
            if (mips != null) for (int l = 1; l < mips.length; l++) if (mips[l] != null) mips[l].recycle();
            if (!alive) { if (b != null) b.recycle(); return; }
            Library.this.got.add(new Got(gen, key, b));
            sc.wake();
        }
    };

    /** 这块的图：生成的这一帧做（最多两块）；歌手的去取封面。还没有返回真（要接着画） */
    private boolean ensureTex(Item it, float time) {
        if (it.tex != 0) return false;
        if (it.kind == K_HIT) { // 搜到的歌：取封面（没有封面地址的 SongCovers 按歌名生成），取到了叠上歌名歌手
            if (!it.asked) {
                SongCovers a = art;
                BydMedia.Song s = new BydMedia.Song(it.key, it.hit.title, it.hit.artist, it.cover, 0);
                List<Item> w = waiting.get(s.art);
                if (w != null) { if (!w.contains(it)) w.add(it); it.asked = true; }
                else if (a != null && a.load(s, false, sink)) { w = new ArrayList<>(); w.add(it); waiting.put(s.art, w); it.asked = true; }
            }
            return false;
        }
        if (it.kind == K_SEARCH && "artist".equals(it.field)) {
            if (!it.asked) {
                String pic = ArtistPics.get(it.name, sc::wake);
                if (pic == null) { // 在查歌手照片：等一会儿（最多 3 秒，还没查到先用他最近一首歌的封面）
                    if (it.picAt == 0) it.picAt = time;
                    if (time - it.picAt < 3) return true;
                    pic = "";
                }
                it.picAt = 0;
                String url = pic.isEmpty() ? it.cover : pic;
                SongCovers a = art;
                BydMedia.Song s = new BydMedia.Song("artist:" + it.name, it.name, "", url == null ? "" : url, 0);
                List<Item> w = waiting.get(s.art);
                if (w != null) { if (!w.contains(it)) w.add(it); it.asked = true; } // 同一张已经在取了：一起等
                else if (a != null && a.load(s, false, sink)) { w = new ArrayList<>(); w.add(it); waiting.put(s.art, w); it.asked = true; }
            }
            return false;
        }
        if (made >= 2) return true;
        made++;
        Bitmap b = paint(it, null);
        it.tex = G.texture(b, true);
        b.recycle();
        it.at = time;
        return true;
    }

    /** 取到的歌手封面：叠上名字做成砖的图 */
    private void drain(float time) {
        Got x;
        int n = 0;
        while (n < 2 && (x = got.poll()) != null) {
            List<Item> w = waiting.remove(x.key);
            if (x.gen != gen || x.b == null || w == null) { if (x.b != null) x.b.recycle(); continue; }
            for (Item it : w) {
                if (it.tex != 0) continue;
                Bitmap b = paint(it, x.b);
                it.tex = G.texture(b, true);
                b.recycle();
                it.at = time;
                n++;
            }
            x.b.recycle();
        }
    }

    /** 两秒没画到的砖：贴图还掉（下次用到再做） */
    private void trimTex() {
        for (Row r : rows) for (Item it : r.items) freeIfOld(it);
        for (Item it : artistItems.values()) freeIfOld(it);
        for (Item it : hitItems.values()) freeIfOld(it);
        freeIfOld(more);
    }

    private void freeIfOld(Item it) { if (it.tex != 0 && frame - it.used > 120) { G.free(it.tex); it.tex = 0; it.asked = false; } }

    /** 画一块砖的图：有封面铺封面、底下压暗写名字；没有就是渐变 + 图形 + 名字 */
    private static Bitmap paint(Item it, Bitmap cover) {
        int n = ART;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (cover != null) {
            c.drawBitmap(cover, null, new RectF(0, 0, n, n), p);
            p.setShader(new LinearGradient(0, n * 0.45f, 0, n, 0x00000000, 0xcc000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, n, n, p);
            p.setShader(null);
        } else {
            int c1 = Color.HSVToColor(new float[] { it.hue, 0.55f, 0.92f }), c2 = Color.HSVToColor(new float[] { (it.hue + 40) % 360, 0.7f, 0.5f });
            p.setShader(new LinearGradient(0, 0, n, n, c1, c2, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, n, n, p);
            p.setShader(new LinearGradient(0, 0, 0, n, 0x30ffffff, 0x00ffffff, Shader.TileMode.CLAMP)); // 上面亮一点（像打了光）
            c.drawRect(0, 0, n, n * 0.5f, p);
            p.setShader(null);
        }
        p.setColor(Color.WHITE);
        if (it.kind == K_PLAY || it.kind == K_FIND || it.kind == K_SONGS || it.kind == K_MORE) {
            glyph(c, it.glyph, n * 0.5f, n * 0.4f, n * 0.34f, p);
            label(c, it.name, n * 0.5f, n * 0.84f, n * 0.135f, Typeface.DEFAULT_BOLD, true, p, n);
        } else if (it.kind == K_HIT && cover != null) { // 歌名、歌手两行
            label(c, it.name, n * 0.08f, n * 0.8f, n * 0.12f, Typeface.DEFAULT_BOLD, false, p, n);
            p.setColor(0xccffffff);
            label(c, it.hit.artist, n * 0.08f, n * 0.92f, n * 0.085f, Typeface.DEFAULT, false, p, n);
        } else if (cover != null) {
            label(c, it.name, n * 0.08f, n * 0.88f, n * 0.13f, Typeface.DEFAULT_BOLD, false, p, n);
        } else {
            label(c, it.name, n * 0.5f, n * 0.58f, n * (it.name.length() <= 2 ? 0.26f : 0.2f), Txt.face(), true, p, n);
        }
        return b;
    }

    private static void label(Canvas c, String t, float x, float y, float size, Typeface tf, boolean center, Paint p, int n) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        p.setShadowLayer(size * 0.2f, 0, size * 0.06f, 0x66000000);
        String s = t;
        float max = n * (center ? 0.88f : 0.84f);
        while (s.length() > 1 && p.measureText(s) > max) s = s.substring(0, s.length() - 1);
        if (!s.equals(t)) s = s.substring(0, Math.max(1, s.length() - 1)) + "…";
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
    }

    /** 我的那几块的图形：0 心、1 日历（今天几号）、2 星光、3 钟、4 歌单、5 随机、6 放大镜（找歌手、搜歌）、7 三个点（加载中） */
    private static void glyph(Canvas c, int g, float cx, float cy, float s, Paint p) {
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setColor(Color.WHITE);
        ln.setStyle(Paint.Style.STROKE);
        ln.setStrokeWidth(s * 0.09f);
        ln.setStrokeCap(Paint.Cap.ROUND);
        ln.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.FILL);
        Path a = new Path();
        float h = s / 2;
        switch (g) {
            case 0: // 心
                a.moveTo(cx, cy + h * 0.8f);
                a.cubicTo(cx - h * 1.25f, cy - h * 0.05f, cx - h * 0.55f, cy - h * 1.05f, cx, cy - h * 0.38f);
                a.cubicTo(cx + h * 0.55f, cy - h * 1.05f, cx + h * 1.25f, cy - h * 0.05f, cx, cy + h * 0.8f);
                c.drawPath(a, p);
                break;
            case 1: { // 日历：框、顶上两个环、今天几号
                c.drawRoundRect(new RectF(cx - h, cy - h * 0.75f, cx + h, cy + h * 0.95f), h * 0.22f, h * 0.22f, ln);
                c.drawLine(cx - h, cy - h * 0.3f, cx + h, cy - h * 0.3f, ln);
                c.drawLine(cx - h * 0.5f, cy - h * 1.0f, cx - h * 0.5f, cy - h * 0.55f, ln);
                c.drawLine(cx + h * 0.5f, cy - h * 1.0f, cx + h * 0.5f, cy - h * 0.55f, ln);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(h * 0.95f);
                c.drawText(String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)), cx, cy + h * 0.72f, p);
                break;
            }
            case 2: // 星光：大小两颗四角星
                star(a, cx - h * 0.15f, cy + h * 0.1f, h * 0.85f);
                star(a, cx + h * 0.7f, cy - h * 0.6f, h * 0.38f);
                c.drawPath(a, p);
                break;
            case 3: // 钟
                c.drawCircle(cx, cy, h * 0.9f, ln);
                c.drawLine(cx, cy, cx, cy - h * 0.55f, ln);
                c.drawLine(cx, cy, cx + h * 0.42f, cy + h * 0.22f, ln);
                break;
            case 4: // 歌单：三行
                for (int k = 0; k < 3; k++) {
                    float y = cy - h * 0.55f + k * h * 0.55f;
                    c.drawCircle(cx - h * 0.75f, y, h * 0.1f, p);
                    c.drawLine(cx - h * 0.4f, y, cx + h * 0.85f, y, ln);
                }
                break;
            case 5: // 随机：两条交叉的线，右头带箭头
                a.moveTo(cx - h, cy - h * 0.5f); a.cubicTo(cx - h * 0.1f, cy - h * 0.5f, cx + h * 0.1f, cy + h * 0.5f, cx + h * 0.75f, cy + h * 0.5f);
                a.moveTo(cx - h, cy + h * 0.5f); a.cubicTo(cx - h * 0.1f, cy + h * 0.5f, cx + h * 0.1f, cy - h * 0.5f, cx + h * 0.75f, cy - h * 0.5f);
                c.drawPath(a, ln);
                for (int sg = -1; sg <= 1; sg += 2) {
                    Path t = new Path();
                    float y = cy + sg * h * 0.5f;
                    t.moveTo(cx + h * 1.05f, y); t.lineTo(cx + h * 0.7f, y - h * 0.24f); t.lineTo(cx + h * 0.7f, y + h * 0.24f); t.close();
                    c.drawPath(t, p);
                }
                break;
            case 7: // 三个点（加载中）
                for (int k = -1; k <= 1; k++) c.drawCircle(cx + k * h * 0.6f, cy, h * 0.17f, p);
                break;
            default: // 放大镜
                c.drawCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.6f, ln);
                c.drawLine(cx + h * 0.3f, cy + h * 0.3f, cx + h * 0.8f, cy + h * 0.8f, ln);
                break;
        }
    }

    private static void star(Path a, float x, float y, float r) {
        a.moveTo(x, y - r);
        a.quadTo(x, y, x + r, y);
        a.quadTo(x, y, x, y + r);
        a.quadTo(x, y, x - r, y);
        a.quadTo(x, y, x, y - r);
        a.close();
    }

    // ================= 每排的标题 =================
    private boolean ensureLabel(Row r) {
        if (r.lt != 0) return false;
        Txt.Label l = Txt.label(r.title, ils, sc.W * 0.2f, true);
        r.lt = G.texture(l.bmp, false);
        l.bmp.recycle();
        r.ml = G.quad(-l.w / 2, -l.h / 2, l.w / 2, l.h / 2);
        r.tw = l.w; r.tpad = l.pad;
        return true;
    }

    /** 圆键上的图形："‹"（返回）或 "«"（回到最前面）；白色，pText 按 uInk 上色 */
    private static int glyph(boolean home) {
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
        if (home) {
            for (float dx : new float[] { -n * 0.11f, n * 0.11f }) { a.moveTo(n * 0.6f + dx, n * 0.28f); a.lineTo(n * 0.4f + dx, n * 0.5f); a.lineTo(n * 0.6f + dx, n * 0.72f); }
        } else {
            a.moveTo(n * 0.6f, n * 0.24f);
            a.lineTo(n * 0.36f, n * 0.5f);
            a.lineTo(n * 0.6f, n * 0.76f);
        }
        c.drawPath(a, p);
        int t = G.texture(b, true);
        b.recycle();
        return t;
    }

    private void freeHint() {
        if (hintTex != 0) { G.free(hintTex); hintTex = 0; }
        if (hintMesh != null) { hintMesh.free(); hintMesh = null; }
        hintFor = -1;
    }

    private static void freeLabel(Row r) {
        if (r.lt != 0) { G.free(r.lt); r.lt = 0; }
        if (r.ml != null) { r.ml.free(); r.ml = null; }
    }

    // ================= 拼音找歌手、搜歌的键盘（全局的 Scene.kb） =================
    private final Keyboard.Owner kbo = new Keyboard.Owner() {
        @Override public void changed() { if (songMode) songChanged(); else applyQuery(); }
        @Override public void hide() { closePad(); }
    };
    private SongIndex index;
    private volatile SongIndex built;
    private boolean indexing;

    private void openPad() {
        if (sc.kb.isOpen(kbo)) return;
        sc.kb.open(kbo, ARTIST_HINT);
        ensureIndex();
    }

    private void closePad() {
        if (sc.kb.isOpen(kbo)) sc.kb.close();
        if (songMode) { songClosing = true; songSeq++; songLoading = false; if (pend != null && pend.kind == K_HIT) pend = null; return; } // 结果那排跟着键盘沉下去，收完再清
        rebuildArtists(null);
    }

    private void ensureIndex() {
        if (indexing || index != null) return;
        indexing = true;
        List<BydMedia.Song> l = new ArrayList<>();
        for (String n : artistNames) l.add(new BydMedia.Song(n, n, "", "", 0)); // 名字当"歌名"搜（首字母、全拼）
        SongIndex.build(l, x -> { built = x; sc.wake(); });
    }

    private void applyQuery() {
        Keyboard kb = sc.kb;
        if (kb.text().trim().isEmpty() && !kb.composing()) { rebuildArtists(null); kb.setInfo(kbo, ""); return; }
        if (index == null) { kb.setInfo(kbo, "…"); ensureIndex(); return; }
        int[] r = index.match(kb.text(), kb.keyFull(), kb.keyInit(), kb.lettered());
        rebuildArtists(r);
        rows[1].pos.set(0);
        kb.setInfo(kbo, r.length == 0 ? "没有找到" : "找到 " + r.length + " 位");
    }

    // ================= 搜歌（主线程搜、放：SongFinder、Host.songSearch / songPick） =================
    private final Row hits = new Row("搜歌");
    private final Map<String, Item> hitItems = new HashMap<>();
    private final ConcurrentLinkedQueue<SongFinder.Page> pages = new ConcurrentLinkedQueue<>();
    private List<Item> songList = new ArrayList<>();
    private boolean songMode, songClosing, songMore, songLoading;
    private String songQuery = "";
    private int songSeq, songPage;
    private float songDirtyAt = -1; // 上屏的字（去掉两头空格，就是 songQuery）变了的时候
    private volatile int songRes; // 主线程交回：1 放上了、2 没放成
    private Item nowHit;          // 刚放上的那块

    void songPage(SongFinder.Page p) { pages.add(p); }
    void songResult(int r) { songRes = r; }

    private void openSongs() {
        if (sc.kb.isOpen(kbo)) return;
        songMode = true;
        songClosing = false;
        songQuery = "";
        songDirtyAt = -1;
        nowHit = null;
        songShow(new ArrayList<Item>(), false);
        hits.pos.set(0);
        sc.kb.open(kbo, SONG_HINT);
        songInfo("");
    }

    /** 搜歌：上屏的字变了才重新搜（停手 0.6 秒，见 songTick） */
    private void songChanged() {
        String q = sc.kb.text().trim();
        if (q.equals(songQuery)) return;
        songQuery = q;
        songDirtyAt = sc.clock;
        songInfo(songQuery.isEmpty() ? "" : "…");
    }

    private void songInfo(String s) { sc.kb.setInfo(kbo, songNote(s)); }

    /** 键盘右头的字：后面带上"会切到 X"（当前不是在线音乐时） */
    private String songNote(String s) {
        Data d = data;
        int tg = d == null ? -1 : d.target;
        if (tg < 0) return s;
        return (s.isEmpty() ? "" : s + " · ") + "会切到" + BydMedia.onlineName(tg);
    }

    private void songShow(List<Item> l, boolean hasMore) {
        songList = l;
        songMore = hasMore;
        List<Item> all = new ArrayList<>(l);
        if (hasMore) all.add(more);
        hits.items = all;
        if (hits.pos.x > Math.max(0, all.size() - 1)) hits.pos.set(Math.max(0, all.size() - 1));
    }

    private Item hitItem(SongFinder.Hit h) {
        String k = "hit:" + h.src + ":" + h.id;
        Item it = hitItems.get(k);
        if (it == null) {
            if (hitItems.size() >= 400) { for (Item x : hitItems.values()) freeTex(x); hitItems.clear(); }
            it = new Item(k, h.title, K_HIT, null, null, null, -1, 0);
            hitItems.put(k, it);
        }
        it.hit = h;
        if (!it.asked) it.cover = h.cover;
        return it;
    }

    /** 搜歌每帧：停手 0.6 秒才搜（字删光了马上清空）、收结果、滑到快到头要下一页、主线程交回的放没放上；收完键盘清掉 */
    private boolean songTick(float time) {
        boolean moving = false;
        if (songClosing) {
            if (sc.kb.k(kbo) <= 0.002f) {
                songMode = songClosing = false;
                songQuery = "";
                pages.clear();
                songShow(new ArrayList<Item>(), false);
                nowHit = null;
            }
            return true;
        }
        if (songDirtyAt >= 0) {
            moving = true;
            if (sc.kb.composing()) songDirtyAt = time; // 拼音还没选完：选完了再等
            else if (songQuery.isEmpty() || time - songDirtyAt > 0.6f) {
                songDirtyAt = -1;
                songSeq++;
                if (songQuery.isEmpty()) { songLoading = false; songShow(new ArrayList<Item>(), false); songInfo(""); }
                else { songLoading = true; sc.host.songSearch(songQuery, 0, songSeq); songInfo("搜索中…"); }
            }
        }
        SongFinder.Page p;
        while ((p = pages.poll()) != null) {
            if (p.seq != songSeq || !p.query.equals(songQuery)) continue; // 打了新的字以后才回来的旧结果
            songLoading = false;
            moving = true;
            if (p.err != null) {
                if (p.page == 0) songShow(new ArrayList<Item>(), false); else songShow(songList, false);
                songInfo(p.err);
                continue;
            }
            List<Item> l = p.page == 0 ? new ArrayList<Item>() : new ArrayList<>(songList);
            for (SongFinder.Hit h : p.hits) { Item it = hitItem(h); if (!l.contains(it)) l.add(it); }
            songPage = p.page;
            if (p.page == 0) hits.pos.set(0);
            songShow(l, p.more);
            songInfo(l.isEmpty() ? "没有找到" : "找到 " + l.size() + (p.more ? "+" : "") + " 首");
        }
        if (songMore && !songLoading && !songList.isEmpty() && hits.pos.t > songList.size() - 5) { // 快到头了：要下一页
            songLoading = true;
            sc.host.songSearch(songQuery, songPage + 1, songSeq);
        }
        int r = songRes;
        if (r != 0) {
            songRes = 0;
            if (pend != null && pend.kind == K_HIT) { if (r == 1) nowHit = pend; pend = null; }
        }
        if (songLoading) moving = true;
        return moving;
    }

    // ================= 画 =================
    private final float[] SM2 = new float[16], hm = new float[16], t2 = new float[16], lm = new float[16];
    private static final float[] FULL = { 0, 0, 1, 1 };

    /** 不透明的：砖、音源的玻璃（主画面、倒影都画） */
    void drawSolid(float[] V, float[] Pp) {
        if (!engaged()) return;
        G.Prog pg = sc.pGlass;
        if (nt > 0) {
            pg.use();
            pg.m4("uP", Pp);
            pg.tex("uAur", 3, sc.aurFbo.tex);
            pg.f("uHalf", Scene.HALF); pg.f("uDay", sc.day);
            pg.c3("uTint", sc.gTint);
            pg.f4("uRF", FULL[0], FULL[1], FULL[2], FULL[3]); pg.f4("uRF2", FULL[0], FULL[1], FULL[2], FULL[3]); pg.f4("uRB", FULL[0], FULL[1], FULL[2], FULL[3]);
            pg.f("uFMix", 0);
            for (int k = 0; k < nt; k++) {
                Tile t = tiles[k];
                sc.mv(V, t.m);
                pg.m4("uMV", sc.MV);
                G.mul(SM2, sc.P, sc.M1); pg.m4("uSM", SM2);
                int tx = t.it.tex;
                float a = tx == 0 ? 0 : clamp((sc.clock - t.it.at) / 0.3f, 0, 1);
                pg.tex("uFront", 0, tx); pg.tex("uFront2", 1, tx); pg.tex("uBack", 2, tx);
                pg.f2("uArt", a, a);
                pg.f("uDim", t.dim); pg.f("uSheen", 0.75f);
                sc.mGlass.draw();
            }
        }
        if (mBack != null) {
            if (kS > 0.002f) round(V, Pp, backM);
            for (int i = 0; i <= rows.length; i++) if (row(i).hk > 0.002f) round(V, Pp, row(i).hm);
        }
        if (nc > 0 && mCap != null) {
            G.Prog pp = sc.pPill;
            pp.use();
            pp.m4("uP", Pp);
            pp.tex("uAur", 0, sc.aurFbo.tex);
            pp.f2("uHalf", sw / 2, sh / 2); pp.f("uDay", sc.day);
            pp.c3("uTint", sc.pal.c[0]);
            for (int k = 0; k < nc; k++) {
                sc.mv(V, caps[k].m);
                pp.m4("uMV", sc.MV);
                G.mul(SM2, sc.P, sc.M1); pp.m4("uSM", SM2);
                mCap.draw();
            }
        }
    }

    /** 一颗圆玻璃键（返回、回到最前面） */
    private void round(float[] V, float[] Pp, float[] m) {
        G.Prog pp = sc.pPill;
        pp.use();
        pp.m4("uP", Pp);
        pp.tex("uAur", 0, sc.aurFbo.tex);
        pp.f2("uHalf", sh / 2, sh / 2); pp.f("uDay", sc.day);
        pp.c3("uTint", sc.pal.c[0]);
        sc.mv(V, m);
        pp.m4("uMV", sc.MV);
        G.mul(SM2, sc.P, sc.M1); pp.m4("uSM", SM2);
        mBack.draw();
    }

    /** 点了在等的那块、现在的音源：外圈的光（只在主画面里） */
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
        if (pDot == null) return;
        pDot.use();
        pDot.m4("uP", Pp);
        float[] col = sc.day > 0.5f ? sc.playPal.deep : sc.playPal.accent;
        pDot.c3("uCol", col); pDot.f("uDay", sc.day);
        for (int k = 0; k < nc; k++) { // 现在的音源（在切的那个闪着）：底下一个发光的点
            Cap c = caps[k];
            if (c.halo <= 0.01f) continue;
            G.compose(tmp, 0, -sh * 0.78f, 0, 0, 0, 0, sw * 0.55f, sh * 0.32f, 1);
            G.mul(hm, c.m, tmp);
            sc.mv(V, hm);
            pDot.m4("uMV", sc.MV); pDot.f("uK", Math.min(1, c.halo) * kS);
            sc.mUnit.draw();
        }
    }

    private static final String F_DOT = "uniform vec3 uCol; uniform float uK, uDay; in vec2 vUv; out vec4 o;\n"
        + "void main() { vec2 p = (vUv - 0.5) * 2.0; float a = (exp(-dot(p, p) * 3.5) * 0.6 + exp(-dot(p, p) * 30.0)) * uK;\n"
        + "  o = vec4(uCol * a, a * uDay * 0.7); }";
    private G.Prog pDot;

    /** 左边的索引、音源图标（主画面、倒影都画） */
    void drawText(float[] V, float[] Pp) {
        if (!engaged()) return;
        G.depthTest(false);
        G.Prog pt = sc.pText;
        pt.use();
        pt.m4("uP", Pp); pt.c3("uInk", sc.ink); pt.f("uK", 0.85f);
        if (kI > 0.002f && white != 0) {
            float rp = rowS.x, fit = fitRows(), dx = -(1 - kI) * ils * 3 / fz;
            float top = idxY(0) + iStep * 0.4f, bot = idxY(rows.length - 1) - iStep * 0.4f;
            bar(V, ixBar + dx, (top + bot) / 2, 3 / fz, top - bot, 0.14f * kI); // 整根淡淡的一道
            pt.c3("uInk", sc.day > 0.5f ? sc.playPal.deep : sc.playPal.accent);
            bar(V, ixBar + dx, idxY(rp + (fit - 1) * 0.5f), 5 / fz, iStep * (fit - 0.25f), kI); // 正在看的那几排
            pt.c3("uInk", sc.ink);
            for (int i = 0; i < rows.length; i++) {
                Row r = rows[i];
                if (r.lt == 0) continue;
                float in = clamp(1 - Math.max(rp - i, i - (rp + fit - 1)), 0, 1);
                G.compose(lm, ixText + dx + (r.tw / 2 - r.tpad) / fz, idxY(i), Z, 0, 0, 0, 1 / fz, 1 / fz, 1);
                sc.mv(V, lm);
                pt.m4("uMV", sc.MV); pt.tex("uMap", 0, r.lt); pt.f("uA", kI * (0.35f + 0.65f * in));
                r.ml.draw();
            }
        }
        pt.use();
        pt.c3("uInk", sc.ink);
        if (kS > 0.002f && backTex != 0) {
            sc.mv(V, backIm);
            pt.m4("uMV", sc.MV); pt.tex("uMap", 0, backTex); pt.f("uA", kS);
            sc.mUnit.draw();
        }
        if (homeTex != 0) for (int i = 0; i <= rows.length; i++) {
            Row r = row(i);
            if (r.hk <= 0.002f) continue;
            sc.mv(V, r.him);
            pt.m4("uMV", sc.MV); pt.tex("uMap", 0, homeTex); pt.f("uA", r.hk);
            sc.mUnit.draw();
        }
        float hk = hintA * kS;
        int hIcon = hintFor >= 0 ? iconTex.get(hintFor, 0) : 0;
        float ic = ils * 0.95f / fz; // 提示前面的音源图标多大
        if (hk > 0.002f && hintTex != 0) {
            pt.use();
            G.compose(lm, ixBar + ic * 1.35f + (hintW / 2 - hintPad) / fz, hintY, Z, 0, 0, 0, 1 / fz, 1 / fz, 1);
            sc.mv(V, lm);
            pt.m4("uMV", sc.MV); pt.tex("uMap", 0, hintTex); pt.f("uA", hk * 0.85f);
            hintMesh.draw();
        }
        if (hk > 0.002f && hIcon != 0) {
            G.Prog pi = sc.pImg;
            pi.use();
            pi.m4("uP", Pp);
            G.compose(lm, ixBar + ic * 0.5f, hintY, Z, 0, 0, 0, ic, ic, 1);
            sc.mv(V, lm);
            pi.m4("uMV", sc.MV); pi.tex("uMap", 0, hIcon); pi.f("uA", hk);
            sc.mUnit.draw();
        }
        if (nc > 0) {
            G.Prog pi = sc.pImg;
            pi.use();
            pi.m4("uP", Pp);
            for (int k = 0; k < nc; k++) {
                Cap c = caps[k];
                int tx = iconTex.get(c.src, 0);
                if (tx == 0) continue;
                sc.mv(V, c.im);
                pi.m4("uMV", sc.MV); pi.tex("uMap", 0, tx); pi.f("uA", c.k);
                sc.mUnit.draw();
            }
        }
        G.depthTest(true);
    }

    /** 一道竖条（pText 正在用，颜色是它的 uInk） */
    private void bar(float[] V, float x, float y, float w, float h, float a) {
        G.Prog pt = sc.pText;
        G.compose(lm, x, y, Z, 0, 0, 0, w, h, 1);
        sc.mv(V, lm);
        pt.m4("uMV", sc.MV); pt.tex("uMap", 0, white); pt.f("uA", a);
        sc.mUnit.draw();
    }

    /** 键盘（场景最上面，只在主画面里） */

    // ================= 建 / 释放 =================
    private void engage() {
        gen++;
        alive = true;
        rowS.set(0);
        srcS.set(0);
        pend = null;
        srcPend = -1;
    }

    /** 关完了：贴图、标题、玻璃、排着没做的全部还掉 */
    private void release() {
        alive = false;
        for (Row r : rows) { for (Item it : r.items) freeTex(it); freeLabel(r); }
        for (Item it : artistItems.values()) freeTex(it);
        freeTex(find);
        for (Item it : hitItems.values()) freeTex(it);
        hitItems.clear();
        freeTex(more);
        songMode = songClosing = false;
        songQuery = "";
        songSeq++;
        pages.clear();
        songShow(new ArrayList<Item>(), false);
        nowHit = null;
        for (int i = 0; i < iconTex.size(); i++) G.free(iconTex.valueAt(i));
        iconTex.clear();
        if (mCap != null) { mCap.free(); mCap = null; }
        if (mBack != null) { mBack.free(); mBack = null; }
        if (backTex != 0) { G.free(backTex); backTex = 0; }
        if (homeTex != 0) { G.free(homeTex); homeTex = 0; }
        if (white != 0) { G.free(white); white = 0; }
        freeHint();
        hintA = 0;
        Got x;
        while ((x = got.poll()) != null) if (x.b != null) x.b.recycle();
        waiting.clear();
        nt = 0; nc = 0; kI = 0;
        pend = null;
        g = null;
    }

    private static void freeTex(Item it) { if (it.tex != 0) { G.free(it.tex); it.tex = 0; } it.asked = false; }

    /** GL 环境建好（第一次、或者丢了重建）：旧的编号都作废、不去删 */
    void initGL() {
        pDot = new G.Prog(Scene.V_UV, F_DOT);
        for (Row r : rows) { for (Item it : r.items) { it.tex = 0; it.asked = false; } r.lt = 0; r.ml = null; }
        for (Item it : artistItems.values()) { it.tex = 0; it.asked = false; }
        for (Item it : hitItems.values()) { it.tex = 0; it.asked = false; }
        find.tex = 0;
        more.tex = 0;
        iconTex.clear();
        mCap = null;
        mBack = null;
        backTex = 0;
        homeTex = 0;
        white = 0;
        hintTex = 0;
        hintMesh = null;
        hintFor = -1;
        Got x;
        while ((x = got.poll()) != null) if (x.b != null) x.b.recycle();
        waiting.clear();
        gen++;
    }

    // ================= 手势 =================
    private static final class Grab { float x, y, lx, ly, vx, vy, pos0, row0; long t, lt; int row = -2, axis, src = -1, idx = -1, home = -1; boolean pad, back; Item item; }
    private Grab g;

    boolean touching() { return g != null; }

    /** a：0 按下 / 1 移动 / 2 抬起 / 3 取消；ex、ey 画面上的位置（场景像素）。开着的时候全归曲库管 */
    void touch(int a, float ex, float ey, long tms) {
        if (a == 0) {
            if (!want || q < 0.6f) { g = null; return; }
            Grab q0 = new Grab();
            q0.x = q0.lx = ex; q0.y = q0.ly = ey; q0.t = q0.lt = tms;
            if (sc.kb.hit(kbo, ex, ey)) { q0.pad = true; sc.kb.down(ex, ey); g = q0; return; }
            q0.back = backAt(ex, ey);
            q0.home = q0.back ? -1 : homeAt(ex, ey);
            q0.idx = q0.back || q0.home >= 0 ? -1 : idxAt(ex, ey);
            q0.src = q0.idx >= 0 || q0.home >= 0 ? -1 : capAt(ex, ey);
            q0.row = q0.src >= 0 ? -1 : q0.home >= 0 ? q0.home : q0.idx >= 0 || q0.back ? -2 : rowAt(ey);
            q0.item = q0.src >= 0 || q0.idx >= 0 || q0.back || q0.home >= 0 ? null : tileAt(ex, ey);
            if (q0.item != null) q0.item.press.t = 0.92f;
            q0.row0 = rowS.x;
            rowS.set(rowS.x);
            if (q0.row >= 0) { q0.pos0 = row(q0.row).pos.x; row(q0.row).pos.set(q0.pos0); }
            else if (q0.row == -1) { q0.pos0 = srcS.x; srcS.set(q0.pos0); }
            g = q0;
            return;
        }
        Grab q1 = g;
        if (q1 == null) return;
        boolean cancel = a == 3;
        if (q1.pad) {
            if (a == 1) sc.kb.move(ex, ey);
            else { sc.kb.up(ex, ey, cancel); g = null; }
            return;
        }
        if (a == 1) {
            float dx = ex - q1.x, dy = ey - q1.y, dtm = Math.max(1, tms - q1.lt);
            q1.vx = q1.vx * 0.4f + (ex - q1.lx) / dtm * 0.6f;
            q1.vy = q1.vy * 0.4f + (ey - q1.ly) / dtm * 0.6f;
            q1.lx = ex; q1.ly = ey; q1.lt = tms;
            if (q1.axis == 0 && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                q1.axis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                if (q1.item != null) { q1.item.press.t = 1; q1.item = null; }
                if (q1.axis == 2 && sc.kb.isOpen(kbo)) q1.axis = 3; // 键盘开着：不换排
            }
            if (q1.axis == 1 && q1.row >= 0) {
                Row r = row(q1.row);
                float raw = q1.pos0 - dx / (pitch * fz), hi = Math.max(0, r.items.size() - 1);
                r.pos.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw);
            } else if (q1.axis == 1 && q1.row == -1) {
                float raw = q1.pos0 - dx / (sPitch * capF * fz), hi = data == null ? 0 : Math.max(0, data.sources.length - 3);
                srcS.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw);
            } else if (q1.axis == 2) {
                float raw = q1.row0 - dy / (rowGap * fz), hi = maxRow();
                rowS.set(raw < 0 ? raw * 0.35f : raw > hi ? hi + (raw - hi) * 0.35f : raw);
            }
            sc.wake();
            return;
        }
        if (q1.item != null) { q1.item.press.t = 1; q1.item.press.v += 3; }
        if (q1.axis == 0 && !cancel && tms - q1.t < 500) tap(q1);
        else if (q1.axis == 1 && q1.row >= 0) {
            Row r = row(q1.row);
            float v = cancel ? 0 : -q1.vx * 1000 / (pitch * fz);
            r.pos.v = v;
            r.pos.t = clamp(Math.round(r.pos.x + v * 0.22f), 0, Math.max(0, r.items.size() - 1));
        } else if (q1.axis == 1 && q1.row == -1) {
            srcS.t = clamp(Math.round(srcS.x), 0, data == null ? 0 : Math.max(0, data.sources.length - 3));
        } else if (q1.axis == 2) {
            float v = cancel ? 0 : -q1.vy * 1000 / (rowGap * fz);
            rowS.v = v;
            rowS.t = clamp(Math.round(rowS.x + v * 0.18f), 0, maxRow());
        }
        g = null;
        sc.wake();
    }

    private void tap(Grab q1) {
        if (q1.back) { close(); return; }
        if (q1.home >= 0) { row(q1.home).pos.t = 0; return; } // 这一排滑回第一块
        if (q1.idx >= 0) { rowS.t = clamp(q1.idx, 0, maxRow()); return; } // 索引：滚到那排在最上面
        if (q1.src >= 0) { pickSource(q1.src); return; }
        Item it = q1.item;
        if (it == null) { if (!sc.kb.isOpen(kbo) && !hintAt(q1.x, q1.y)) close(); return; } // 点空白：回去（点在提示那行字上不算）
        if (it.kind == K_FIND) { openPad(); return; }
        if (it.kind == K_SONGS) { openSongs(); return; }
        if (it.kind == K_MORE) return;
        if (it.kind == K_HIT) { // 搜到的歌：插放，留在搜歌里
            if (pend != null && pend.kind == K_HIT) return; // 上一首还没放上：不接着插（主线程最晚十几秒交回结果）
            pend = it;
            pendAt = sc.clock;
            sc.host.songPick(it.hit);
            return;
        }
        if (it == pend) return; // 正在等它的结果：不重发
        pend = it;
        pendAt = sc.clock;
        sc.host.libPick(it.method, it.field, it.value);
        if (sc.kb.isOpen(kbo)) closePad();
    }

    private void pickSource(int idx) {
        Data d = data;
        if (d == null || idx >= d.sources.length) return;
        int s = d.sources[idx];
        if (s == d.src || s == srcPend) return; // 已经是它、或者正在切过去
        srcPend = s;
        srcPendAt = sc.clock;
        sc.host.libSource(s);
    }

    private final float[] PS = new float[16], PM = new float[16], o3 = new float[3], c4 = new float[8];

    private void screen(float[] m, float x, float y, float z) {
        G.mul(PM, PS, m);
        G.project(o3, PM, x, y, z);
        o3[0] = (o3[0] * 0.5f + 0.5f) * sc.W;
        o3[1] = (0.5f - o3[1] * 0.5f) * sc.H;
    }

    /** 按在哪个音源上（caps 里的序号 → 音源列表里的序号；没有是 -1） */
    private int capAt(float ex, float ey) {
        G.mul(PS, sc.P, sc.S);
        for (int k = 0; k < nc; k++) {
            screen(caps[k].m, 0, 0, 0);
            if (Math.abs(ex - o3[0]) < sw * capF * fz * 0.6f && Math.abs(ey - o3[1]) < sh * fz * 0.8f) return caps[k].idx;
        }
        return -1;
    }

    /** 按在哪一排的"回到最前面"上（没有是 -1） */
    private int homeAt(float ex, float ey) {
        G.mul(PS, sc.P, sc.S);
        for (int r = 0; r <= rows.length; r++) {
            if (row(r).hk < 0.5f) continue;
            screen(row(r).hm, 0, 0, 0);
            if (Math.abs(ex - o3[0]) < sh * fz * 0.6f && Math.abs(ey - o3[1]) < sh * fz * 0.6f) return r;
        }
        return -1;
    }

    /** 按在返回键上 */
    private boolean backAt(float ex, float ey) {
        if (kS < 0.5f || mBack == null) return false;
        G.mul(PS, sc.P, sc.S);
        screen(backM, 0, 0, 0);
        return Math.abs(ex - o3[0]) < sh * fz * 0.75f && Math.abs(ey - o3[1]) < sh * fz * 0.8f;
    }

    /** 按在提示那一行上 */
    private boolean hintAt(float ex, float ey) {
        if (hintA * kS < 0.5f || hintTex == 0) return false;
        G.mul(PS, sc.P, sc.S);
        Matrix.setIdentityM(tmp, 0);
        float h = ils * 0.9f / fz;
        screen(tmp, ixBar, hintY + h, Z);
        float x0 = o3[0], y0 = o3[1];
        screen(tmp, ixBar + ils * 1.3f / fz + hintW / fz, hintY - h, Z);
        return ex >= x0 && ex <= o3[0] && ey >= y0 && ey <= o3[1];
    }

    /** 按在索引上：离哪个名字最近（不在索引上是 -1） */
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

    /** 按在哪一排的高度上（-2：哪排都不是） */
    private int rowAt(float ey) {
        G.mul(PS, sc.P, sc.S);
        Matrix.setIdentityM(tmp, 0);
        for (int r = 0; r <= rows.length; r++) {
            if (row(r).vis < 0.5f) continue;
            screen(tmp, 0, row(r).y + T / 2 + titleH, Z);
            float top = o3[1];
            screen(tmp, 0, row(r).y - T / 2, Z);
            if (ey > top && ey < o3[1]) return r;
        }
        return -2;
    }

    /** 点在哪块砖上（按砖的正面投到画面上的四边形算） */
    private Item tileAt(float ex, float ey) {
        G.mul(PS, sc.P, sc.S);
        for (int k = 0; k < nt; k++) {
            Tile t = tiles[k];
            if (t.dim < 0.5f || row(t.row).vis < 0.5f) continue; // 滚出去的、键盘升起来让开的排点不到
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
