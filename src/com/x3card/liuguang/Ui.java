package com.x3card.liuguang;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 叠在 3D 场景上面的提示：右上角的设置键、全屏键画在场景里（TopKeys），这里只定什么时候显示（开场动画快完了才出来、曲库或设置页开着时藏起来）；设置页也在场景里（SetPage）。
 * 歌单里正在放的那首跑出画面时，那一边出一个"正在放"，点了回到它；底部中间的提示条几秒后淡出（玻璃胶囊）。
 * 尺寸按窗口高度算（1rem = 高度 / 10.52，同网页版）。深浅色跟着场景，强调色跟着封面。没有按键可点的地方不接手势（交给下面的场景）
 */
@SuppressLint("ViewConstructor")
final class Ui extends FrameLayout {
    interface Actions {
        void locate(); // 歌单里回到正在放的那首
        void keys(boolean show, String fs); // 右上角的键显不显示、全屏键是哪种（画在场景里，见 TopKeys）
    }

    private final Actions act;
    private final TextView toast, locChip;
    private final PathInterpolator ease = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private boolean day, narrow, wait = true, blocked, shelfOn, lib, prefs;
    private int locDir;
    private Guide guide;
    private float locY = 0.5f;
    private String fsMode = "";
    private float rem = 10;
    private int w = 1, h = 1;

    // 颜色：深浅色跟着场景，强调色跟着封面（setPalette）
    private int ink, acc, accN = 0xffd8c8a0, accD = 0xff4a3c20;

    Ui(Context c, Actions act) {
        super(c);
        this.act = act;
        locChip = new TextView(c); // 歌单里的"正在放"：正在放的那首跑出画面时出在那一边
        locChip.setGravity(Gravity.CENTER);
        locChip.setSingleLine(true);
        locChip.setAlpha(0);
        locChip.setVisibility(INVISIBLE);
        locChip.setOnClickListener(v -> act.locate());
        addView(locChip);

        toast = new TextView(c);
        toast.setGravity(Gravity.CENTER);
        toast.setAlpha(0);
        addView(toast);
        colors();
        fade();
    }

    // ================= 外面交进来的 =================
    void setDay(boolean d) { if (d == day) return; day = d; colors(); if (guide != null) guide.setDay(d); }

    /** 封面的强调色：accent 夜里的、deep 白天的（压深的） */
    void setPalette(int accent, int deep) {
        if (accent == accN && deep == accD) return;
        accN = accent; accD = deep;
        colors();
    }

    /** 开场动画还没走完：右上角的键先藏着 */
    void setWait(boolean w0) { if (w0 == wait) return; wait = w0; fade(); }
    /** 曲库开着：右上角的键藏起来（不压住顶上那排音源） */
    void setLib(boolean l) { if (l == lib) return; lib = l; fade(); }
    /** 设置页开着：右上角的键藏起来 */
    void setPrefs(boolean p) { if (p == prefs) return; prefs = p; fade(); }
    /** 没连上宿主、没授权（上面盖着说明）：键藏起来 */
    void setBlocked(boolean b) { if (b == blocked) return; blocked = b; fade(); }
    /** 全屏键：full 在小窗口里（点了全屏打开）、small 在全屏页里（点了恢复小屏）、空的不显示 */
    void setFsMode(String m) { fsMode = m; fade(); }

    /** 换了一块画布（场景重建）：键的状态再交一次 */
    void pushKeys() { fade(); }

    /** 新手引导（Guide）：看过了叫 done（收起来不算） */
    void showGuide(Runnable done) {
        if (guide != null) return;
        guide = new Guide(getContext(), day, () -> { guide = null; done.run(); });
        addView(guide, new LayoutParams(-1, -1));
        guide.size(w, h, rem);
    }

    boolean guiding() { return guide != null; }

    /** 封面在画面上的范围（占画面的比例） */
    void guideHole(float l, float t, float r, float b) { if (guide != null) guide.hole(l * w, t * h, r * w, b * h); }

    /** 返回键：算看过 */
    void guideOk() { if (guide != null) guide.finish(); }

    /** 应用藏起来了：收起来，不算看过 */
    void guideHide() { if (guide != null) { guide.dismiss(); guide = null; } }

    /** 开场走完、右上角的键出来了 */
    boolean keysUp() { return !wait; }

    /** 歌单开着（返回键先关歌单） */
    void setShelfOn(boolean on) { shelfOn = on; }

    boolean shelfOn() { return shelfOn; }

    /** 歌单里正在放的那首跑出画面了：dir -1 在左边、1 在右边、0 不显示；y 是砖的中间在画面上多高（0～1） */
    void setLocate(int dir, float y) {
        if (dir != 0) { locY = y; if (dir != locDir) { locChip.setText(dir < 0 ? "‹  正在放" : "正在放  ›"); locDir = dir; } placeChip(); }
        else locDir = 0;
        boolean show = dir != 0;
        locChip.animate().cancel();
        locChip.setEnabled(show);
        if (show) locChip.setVisibility(VISIBLE);
        locChip.animate().alpha(show ? 1 : 0).setDuration(show ? 320 : 220).withEndAction(() -> { if (locChip.getAlpha() == 0) locChip.setVisibility(INVISIBLE); }).start();
    }

    private void placeChip() {
        LayoutParams q = new LayoutParams(-2, -2, Gravity.TOP | (locDir < 0 ? Gravity.START : Gravity.END));
        q.leftMargin = q.rightMargin = px(0.26f);
        locChip.setLayoutParams(q);
        locChip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        locChip.setTranslationY(locY * h - locChip.getMeasuredHeight() / 2f);
    }

    private Runnable toastOff;

    void toast(String msg) {
        toast.setText(msg);
        toast.animate().cancel();
        toast.setTranslationY(px(0.12f) * (1 - toast.getAlpha()));
        toast.animate().alpha(1).translationY(0).setDuration(350).setInterpolator(ease).start();
        if (toastOff != null) removeCallbacks(toastOff);
        toastOff = () -> toast.animate().alpha(0).setDuration(350).start();
        postDelayed(toastOff, 3200);
    }

    /** 键的显示：开场没完、上面盖着说明、曲库或设置页开着时藏起来（同网页版 .wait） */
    private void fade() { act.keys(!wait && !blocked && !lib && !prefs, fsMode); }

    // ================= 尺寸（1rem = 高度 / 10.52） =================
    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        super.onSizeChanged(nw, nh, ow, oh);
        w = Math.max(1, nw);
        h = Math.max(1, nh);
        post(this::relayout); // 在布局过程中改子视图的尺寸要等这一轮布局完
    }

    private int px(float r) { return Math.round(r * rem); }

    private void relayout() {
        rem = h / 10.52f;
        narrow = (float) w / h < 0.8f;
        LayoutParams t = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        t.bottomMargin = px(1.4f);
        toast.setLayoutParams(t);
        toast.setMaxWidth(narrow ? Math.round(w * 0.9f) : px(6));
        toast.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
        toast.setLineSpacing(0, 1.2f);
        toast.setPadding(px(0.3f), px(0.14f), px(0.3f), px(0.14f));
        locChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        locChip.setPadding(px(0.26f), px(0.13f), px(0.26f), px(0.13f));
        if (locDir != 0) placeChip();
        if (guide != null) guide.size(w, h, rem);
        colors();
    }

    // ================= 颜色（夜里深色玻璃、白天浅色玻璃；强调色跟着封面） =================
    private static int alpha(int c, float a) { return (Math.round(a * 255) << 24) | (c & 0xffffff); }

    /** 一块玻璃胶囊：上下渐变的底 + 一圈细边 */
    private GradientDrawable glass(int top, int bottom, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        d.setCornerRadius(9999);
        if (stroke != 0) d.setStroke(Math.max(1, px(0.012f)), stroke);
        return d;
    }

    private void colors() {
        ink = day ? 0xff151922 : 0xffffffff;
        acc = day ? accD : accN;
        int gt = day ? 0xebffffff : 0xd9141720, gb = day ? 0xe0f2f4f8 : 0xe00b0d12, gs = day ? 0x1f141822 : 0x26ffffff;
        toast.setBackground(glass(gt, gb, gs));
        toast.setTextColor(ink);
        locChip.setBackground(glass(gt, gb, alpha(acc, 0.7f)));
        locChip.setTextColor(ink);
    }
}
