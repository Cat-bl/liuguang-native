package com.x3card.liuguang;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 叠在 3D 场景上面的按键和面板（照网页版的 index.html / style.css）：右上角齿轮、全屏键在它左边（竖屏在它下面），开场动画快完了才淡入、开着面板时藏起来；
 * 设置面板从右边滑出（竖屏整张盖住），每项一排分段按钮； * 歌单里正在放的那首跑出画面时，那一边出一个"正在放"，点了回到它；
 * 底部中间的提示条几秒后淡出。尺寸按窗口高度算（1rem = 高度 / 10.52，同网页版）。
 * 深浅色跟着场景。没有按键、面板可点的地方不接手势（交给下面的场景）
 */
@SuppressLint("ViewConstructor")
final class Ui extends FrameLayout {
    interface Actions {
        void set(String key, Object value);
        void fullscreen();
        void locate(); // 歌单里回到正在放的那首
    }

    private static final Object[][] ON = { { true, "开" }, { false, "关" } };
    /** 键、名字、选项、说明（同网页版的 ROWS） */
    private static final Object[][] ROWS = {
        { "font", "歌词字体", new Object[][] { { "smiley", "得意黑" }, { "system", "系统字体" } }, null },
        { "theme", "深浅色", new Object[][] { { "auto", "跟随车机" }, { "light", "浅色" }, { "dark", "深色" } }, null },
        { "aa", "抗锯齿", ON, "边缘更平滑，更费显卡" },
        { "fps", "帧率", new Object[][] { { "auto", "自动" }, { "60", "60 帧" }, { "30", "30 帧" } }, "自动：画不过来降到 30 帧" },
        { "water", "水面倒影", ON, null },
        { "art", "背景封面", ON, null },
        { "ribbons", "光带", ON, null },
        { "rain", "玻璃上的雨珠", new Object[][] { { "auto", "自动" }, { "on", "开" }, { "off", "关" } }, "水珠挂在屏幕上，偶尔滑落。自动：跟着实时天气，并按歌词动态判断" },
        { "intro", "开场动画", ON, null },
        { "reveal", "换歌动画", ON, "光点从封面流出聚成歌词" },
        { "fsBar", "全屏时收起状态栏", ON, null },
    };

    private final Settings st;
    private final Actions act;
    private final ImageView gear, fs, close;
    private final LinearLayout panel, body;
    private final ScrollView scroll;
    private final TextView title, toast, locChip;
    private final PathInterpolator ease = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private boolean day, narrow, open, wait = true, blocked, shelfOn;
    private int locDir;
    private float locY = 0.5f;
    private String fsMode = "";
    private float rem = 10;
    private int w = 1, h = 1;

    // 深浅色（同网页版的 CSS 变量）
    private int ink, sub, sheet, line, chip, chipOn, chipOnInk, btn;

    Ui(Context c, Settings st, Actions act) {
        super(c);
        this.st = st;
        this.act = act;
        gear = key(R.drawable.ic_gear);
        gear.setOnClickListener(v -> show(true));
        fs = key(R.drawable.ic_full);
        fs.setVisibility(GONE);
        fs.setOnClickListener(v -> act.fullscreen());
        addView(fs);
        addView(gear);

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // 面板上的手势不漏到场景
        panel.setVisibility(INVISIBLE);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(c);
        title.setText("设置");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        close = new ImageView(c);
        close.setImageResource(R.drawable.ic_close);
        close.setOnClickListener(v -> show(false));
        head.addView(close);
        panel.addView(head);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(panel);

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
        fade(false);
    }

    private ImageView key(int icon) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setOnTouchListener((view, e) -> { // 按下去缩一点（同网页版 :active）
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.86f).scaleY(0.86f).setDuration(120).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1).scaleY(1).setDuration(450).setInterpolator(new PathInterpolator(0.34f, 1.56f, 0.64f, 1f)).start();
            return false;
        });
        return v;
    }

    // ================= 外面交进来的 =================
    void setDay(boolean d) { if (d == day) return; day = d; colors(); }
    /** 开场动画还没走完：右上角的键先藏着 */
    void setWait(boolean w0) { if (w0 == wait) return; wait = w0; fade(true); }
    /** 没连上宿主、没授权（上面盖着说明）：键藏起来、面板关上 */
    void setBlocked(boolean b) { if (b == blocked) return; blocked = b; if (b) closeAll(); fade(true); }
    /** 全屏键：full 在小窗口里（点了全屏打开）、small 在全屏页里（点了恢复小屏）、空的不显示 */
    void setFsMode(String m) {
        fsMode = m;
        fs.setVisibility(m.isEmpty() ? GONE : VISIBLE);
        fs.setImageResource("small".equals(m) ? R.drawable.ic_small : R.drawable.ic_full);
        fs.setContentDescription("small".equals(m) ? "恢复小屏" : "全屏");
        tint();
        if (open) rows();
    }
    /** 设置在别处改了（全屏页和小窗口共用一份）：面板开着就重画 */
    void refresh() { if (open) rows(); }
    boolean isOpen() { return open; }
    void closeAll() { show(false); }

    /** 歌单开着（返回键先关歌单） */
    void setShelfOn(boolean on) { shelfOn = on; }

    boolean shelfOn() { return shelfOn; }

    /** 歌单里正在放的那首跑出画面了：dir -1 在左边、1 在右边、0 不显示；y 是砖的中间在画面上多高（0～1） */
    void setLocate(int dir, float y) {
        if (dir != 0) { locY = y; if (dir != locDir) { locChip.setText(dir < 0 ? "‹  正在放" : "正在放  ›"); locDir = dir; } placeChip(); }
        else locDir = 0;
        boolean show = dir != 0 && !open;
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
        toast.animate().alpha(1).setDuration(350).start();
        if (toastOff != null) removeCallbacks(toastOff);
        toastOff = () -> toast.animate().alpha(0).setDuration(350).start();
        postDelayed(toastOff, 3200);
    }

    /** 打开 / 关上设置面板 */
    private void show(boolean on) {
        if (on == open) return;
        open = on;
        panel.animate().cancel();
        if (on) {
            rows();
            scroll.scrollTo(0, 0);
            panel.setVisibility(VISIBLE);
            panel.setTranslationX(panel.getWidth() > 0 ? panel.getWidth() * 1.1f : w);
            panel.animate().translationX(0).setDuration(500).setInterpolator(ease).start();
        } else {
            panel.animate().translationX(Math.max(panel.getWidth(), 1) * 1.1f).setDuration(500).setInterpolator(ease).withEndAction(() -> { if (!open) panel.setVisibility(INVISIBLE); }).start();
        }
        fade(true);
    }

    /** 键的显示：开场没完、面板开着、上面盖着说明时藏起来（同网页版 .wait / .open） */
    private void fade(boolean anim) {
        boolean show = !wait && !open && !blocked;
        for (View v : new View[] { gear, fs }) {
            float a = show ? 0.72f : 0;
            v.animate().cancel();
            v.setEnabled(show);
            if (anim) v.animate().alpha(a).setDuration(500).start(); else v.setAlpha(a);
        }
        if (locDir != 0) { // 面板盖着时"正在放"先藏起来
            locChip.animate().cancel();
            locChip.setEnabled(!open);
            if (!open) locChip.setVisibility(VISIBLE);
            locChip.animate().alpha(open ? 0 : 1).setDuration(anim ? 320 : 0).start();
        }
    }

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
        int k = px(0.62f), pad = Math.round(k * 0.22f);
        LayoutParams g = new LayoutParams(k, k, Gravity.TOP | Gravity.END);
        g.topMargin = px(0.24f);
        g.rightMargin = px(0.26f);
        gear.setLayoutParams(g);
        gear.setPadding(pad, pad, pad, pad);
        LayoutParams f = new LayoutParams(k, k, Gravity.TOP | Gravity.END);
        f.topMargin = narrow ? px(1.0f) : px(0.24f);
        f.rightMargin = narrow ? px(0.26f) : px(1.02f);
        fs.setLayoutParams(f);
        fs.setPadding(pad, pad, pad, pad);
        LayoutParams p = new LayoutParams(narrow ? -1 : Math.round(w * 0.46f), -1, Gravity.END);
        panel.setLayoutParams(p);
        if (!open) panel.setTranslationX((narrow ? w : w * 0.46f) * 1.1f);
        View head = panel.getChildAt(0);
        head.setPadding(px(0.3f), px(0.26f), px(0.3f), px(0.14f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.34f));
        int ck = px(0.56f), cp = Math.round(ck * 0.27f);
        close.setLayoutParams(new LinearLayout.LayoutParams(ck, ck));
        close.setPadding(cp, cp, cp, cp);
        body.setPadding(px(0.3f), 0, px(0.3f), px(0.3f));
        LayoutParams t = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        t.bottomMargin = px(1.4f);
        toast.setLayoutParams(t);
        toast.setMaxWidth(narrow ? Math.round(w * 0.9f) : px(6));
        toast.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
        toast.setLineSpacing(0, 1.2f);
        toast.setPadding(px(0.26f), px(0.12f), px(0.26f), px(0.12f));
        locChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        locChip.setPadding(px(0.24f), px(0.12f), px(0.24f), px(0.12f));
        if (locDir != 0) placeChip();
        colors();
    }

    // ================= 颜色（同网页版：夜里深色、白天浅色） =================
    private void colors() {
        ink = day ? 0xff151922 : 0xffffffff;
        sub = day ? 0x94151922 : 0x99ffffff;
        sheet = day ? 0xf2f6f7fb : 0xeb0e1018;
        line = day ? 0x1a141822 : 0x1affffff;
        chip = day ? 0x0f141822 : 0x14ffffff;
        chipOn = day ? 0xff151922 : 0xebffffff;
        chipOnInk = day ? 0xffffffff : 0xff111111;
        btn = day ? 0x12141822 : 0x1affffff;
        for (ImageView v : new ImageView[] { gear, fs }) v.setBackground(round(btn, -1));
        tint();
        panel.setBackgroundColor(sheet);
        title.setTextColor(ink);
        close.setBackground(round(chip, -1));
        close.getDrawable().mutate().setTint(ink);
        GradientDrawable tb = round(day ? 0xe0ffffff : 0x9e000000, px(0.3f));
        toast.setBackground(tb);
        toast.setTextColor(day ? 0xff151922 : 0xffffffff);
        locChip.setBackground(round(day ? 0xe6ffffff : 0xa6000000, px(0.3f)));
        locChip.setTextColor(day ? 0xff151922 : 0xffffffff);
        if (open) rows();
    }

    private void tint() {
        for (ImageView v : new ImageView[] { gear, fs }) { Drawable d = v.getDrawable(); if (d != null) d.mutate().setTint(ink); }
    }

    /** 圆角底（radius < 0 是正圆） */
    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        if (radius < 0) d.setShape(GradientDrawable.OVAL); else d.setCornerRadius(radius);
        d.setColor(color);
        return d;
    }

    // ================= 面板里的每一项 =================
    private void rows() {
        body.removeAllViews();
        Context c = getContext();
        boolean first = true;
        for (Object[] r : ROWS) {
            final String k = (String) r[0];
            if ("fsBar".equals(k) && fsMode.isEmpty()) continue;
            if (!first) { View ln = new View(c); ln.setBackgroundColor(line); body.addView(ln, new LinearLayout.LayoutParams(-1, Math.max(1, px(0.01f)))); }
            first = false;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, px(0.14f), 0, px(0.16f));
            TextView name = new TextView(c);
            name.setText((String) r[1]);
            name.setTextColor(ink);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.26f));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
            np.bottomMargin = px(0.08f);
            row.addView(name, np);
            if (r[3] != null) {
                TextView s = new TextView(c);
                s.setText((String) r[3]);
                s.setTextColor(sub);
                s.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
                sp.topMargin = -px(0.04f);
                sp.bottomMargin = px(0.1f);
                row.addView(s, sp);
            }
            LinearLayout seg = new LinearLayout(c);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            Object cur = st.get(k);
            Object[][] opts = (Object[][]) r[2];
            for (int i = 0; i < opts.length; i++) {
                final Object v = opts[i][0];
                boolean on = v.equals(cur);
                TextView b = new TextView(c);
                b.setText((String) opts[i][1]);
                b.setGravity(Gravity.CENTER);
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
                b.setTextColor(on ? chipOnInk : ink);
                b.setBackground(round(on ? chipOn : chip, px(0.14f)));
                b.setPadding(px(0.05f), px(0.11f), px(0.05f), px(0.11f));
                b.setSingleLine(true);
                b.setOnClickListener(x -> { if (!v.equals(st.get(k))) act.set(k, v); });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) bp.leftMargin = px(0.06f);
                seg.addView(b, bp);
            }
            row.addView(seg);
            body.addView(row);
        }
        body.setBackgroundColor(Color.TRANSPARENT);
    }
}
