package com.x3card.liuguang;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 新手引导（同 pet、demo3d 的写法，只有一步）：画面压暗，封面那块亮着、外面一圈呼吸光，旁边一个说明气泡：点一下封面打开歌单（没有歌单时是暂停 / 播放）、左右划换歌。
 * 点哪里、点"知道了"、点右上角关闭都算看过（done）。亮框跟着封面走（场景交来封面在画面上的范围），第一次交来才淡入。
 * 气泡放在封面右边，放不下放下面，再放不下放上面；箭头指着封面。深浅色跟着场景
 */
@SuppressLint("ViewConstructor")
final class Guide extends FrameLayout {
    private final Runnable done;
    private final LinearLayout tip;
    private final TextView label, title, text, ok;
    private final ImageView x;
    private final View accLine;
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
        arrow = new Paint(Paint.ANTI_ALIAS_FLAG), arrowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF hole = new RectF(), tmp = new RectF();
    private final ValueAnimator pulse = ValueAnimator.ofFloat(0, 1);
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    private boolean has, day, closing;
    private float rem = 10, pk, ax, ay, tx, ty, enter;
    private int w = 1, h = 1, side; // side：气泡在封面的 0 右边、1 下面、2 上面
    private int ink, mute, acc, top, bottom, lineC, xBg, okBg, okInk;

    Guide(Context c, boolean day, Runnable done) {
        super(c);
        this.day = day;
        this.done = done;
        setWillNotDraw(false);
        setClickable(true);
        setAlpha(0);

        tip = new LinearLayout(c);
        tip.setOrientation(LinearLayout.VERTICAL);
        tip.setClickable(true);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        label = new TextView(c);
        label.setText("新手引导");
        label.setLetterSpacing(0.18f);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        x = new ImageView(c);
        x.setImageResource(R.drawable.ic_close);
        x.setContentDescription("关闭");
        x.setOnClickListener(v -> finish());
        head.addView(x);
        tip.addView(head);
        title = new TextView(c);
        title.setText("封面能点，也能划");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        tip.addView(title);
        text = new TextView(c);
        tip.addView(text);
        LinearLayout foot = new LinearLayout(c);
        foot.setGravity(Gravity.END);
        ok = new TextView(c);
        ok.setText("知道了");
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setOnClickListener(v -> finish());
        foot.addView(ok);
        tip.addView(foot);
        addView(tip, new LayoutParams(-2, -2));
        accLine = new View(c); // 气泡顶上一道细线（同设置面板）
        addView(accLine, new LayoutParams(0, 0));

        edge.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        arrowLine.setStyle(Paint.Style.STROKE);
        path.setFillType(Path.FillType.EVEN_ODD);
        pulse.setDuration(1800);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> { pk = (float) a.getAnimatedValue(); invalidate(); });
        colors();
    }

    // ================= 外面交进来的 =================
    void setDay(boolean d) { if (d == day) return; day = d; colors(); }

    void size(int nw, int nh, float r) { w = nw; h = nh; rem = r; style(); place(); }

    /** 封面在画面上的范围（像素）：第一次交来时淡入 */
    void hole(float l, float t, float r, float b) {
        float pad = rem * 0.1f;
        hole.set(l - pad, t - pad, r + pad, b + pad);
        place();
        if (!has) {
            has = true;
            animate().alpha(1).setDuration(450).start();
            ValueAnimator in = ValueAnimator.ofFloat(0, 1); // 气泡从下面一点浮上来
            in.setDuration(450);
            in.setInterpolator(ease);
            in.addUpdateListener(a -> { enter = (float) a.getAnimatedValue(); applyPos(); });
            in.start();
            pulse.start();
        }
    }

    /** 收起来（不算看过） */
    void dismiss() { pulse.cancel(); animate().cancel(); if (getParent() != null) ((android.view.ViewGroup) getParent()).removeView(this); }

    /** 看过了：淡出、拿掉，告诉外面 */
    void finish() {
        if (closing) return;
        closing = true;
        setClickable(false);
        done.run();
        animate().alpha(0).setDuration(300).withEndAction(this::dismiss).start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) { // 点哪里都算看过（手指抬起时）
        if (e.getActionMasked() == MotionEvent.ACTION_UP && has) finish();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow(); }

    // ================= 样子 =================
    private int px(float r) { return Math.round(r * rem); }

    private void colors() {
        ink = day ? 0xff14171c : 0xffeef1f6;
        mute = day ? 0x9914171c : 0x9eeef1f6;
        acc = day ? 0xff14171c : 0xffffffff;
        top = day ? 0xffffffff : 0xff262a34;
        bottom = day ? 0xfff1f3f6 : 0xff111317;
        lineC = day ? 0x1414171c : 0x14ffffff;
        xBg = day ? 0x0f14171c : 0x14ffffff;
        okBg = day ? 0xff151922 : 0xebffffff;
        okInk = day ? 0xffffffff : 0xff111111;
        mask.setColor(day ? 0x8012161e : 0xb3060810);
        arrow.setColor(side == 2 ? bottom : top);
        arrowLine.setColor(lineC);
        label.setTextColor(mute);
        title.setTextColor(ink);
        x.getDrawable().mutate().setTint(ink);
        ok.setTextColor(okInk);
        text.setText(lines());
        style();
        invalidate();
    }

    /** 两行说明，动作那几个字用正文色加粗 */
    private CharSequence lines() {
        String[][] ls = { { "点一下", "：打开整个歌单，点正在放的那块回到歌词（没有歌单时是暂停 / 播放）" }, { "左右划", "：上一首 / 下一首" } };
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) s.append('\n');
            int a = s.length();
            s.append(ls[i][0]);
            s.setSpan(new ForegroundColorSpan(ink), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new StyleSpan(Typeface.BOLD), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(ls[i][1]);
        }
        return s;
    }

    /** 尺寸（1rem = 高度 / 10.52，同 Ui） */
    private void style() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { top, bottom });
        bg.setCornerRadius(px(0.32f));
        bg.setStroke(1, lineC);
        tip.setBackground(bg);
        tip.setElevation(px(0.14f));
        tip.setPadding(px(0.3f), px(0.22f), px(0.2f), px(0.24f));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        int xk = px(0.5f), xp = px(0.13f);
        x.setLayoutParams(new LinearLayout.LayoutParams(xk, xk));
        x.setPadding(xp, xp, xp, xp);
        GradientDrawable xb = new GradientDrawable();
        xb.setShape(GradientDrawable.OVAL);
        xb.setColor(xBg);
        x.setBackground(xb);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.34f));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = px(0.02f);
        tp.rightMargin = px(0.1f);
        title.setLayoutParams(tp);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        text.setTextColor(mute);
        text.setLineSpacing(0, 1.55f);
        LinearLayout.LayoutParams xp2 = new LinearLayout.LayoutParams(-1, -2);
        xp2.topMargin = px(0.1f);
        xp2.rightMargin = px(0.1f);
        text.setLayoutParams(xp2);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        ok.setPadding(px(0.3f), px(0.1f), px(0.3f), px(0.1f));
        GradientDrawable ob = new GradientDrawable();
        ob.setCornerRadius(px(0.3f));
        ob.setColor(okBg);
        ok.setBackground(ob);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = px(0.2f);
        fp.rightMargin = px(0.1f);
        ((View) ok.getParent()).setLayoutParams(fp);
        GradientDrawable al = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] { acc & 0x00ffffff, acc, acc & 0x00ffffff });
        accLine.setBackground(al);
        accLine.setElevation(px(0.14f) + 1); // 画在气泡上面
        edge.setStrokeWidth(Math.max(2, rem * 0.03f));
        edge.setColor(acc);
        edge.setShadowLayer(rem * 0.2f, 0, 0, acc);
        ring.setStrokeWidth(Math.max(2, rem * 0.03f));
        arrowLine.setStrokeWidth(1);
    }

    /** 气泡摆在封面旁边：右边放得下放右边，不然下面，再不然上面；箭头的位置跟着算 */
    private void place() {
        if (hole.isEmpty() || w <= 1) return;
        int tw = Math.min(px(4.8f), w - px(0.4f)), m = px(0.2f), gap = px(0.36f);
        tip.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        int th = tip.getMeasuredHeight();
        if (hole.right + gap + tw + m <= w) { side = 0; tx = hole.right + gap; ty = clamp(hole.centerY() - th / 2f, m, h - th - m); }
        else if (hole.bottom + gap + th + m <= h) { side = 1; ty = hole.bottom + gap; tx = clamp(hole.centerX() - tw / 2f, m, w - tw - m); }
        else { side = 2; ty = Math.max(m, hole.top - gap - th); tx = clamp(hole.centerX() - tw / 2f, m, w - tw - m); }
        LayoutParams lp = (LayoutParams) tip.getLayoutParams();
        if (lp.width != tw) { lp.width = tw; tip.setLayoutParams(lp); }
        float r = px(0.4f);
        if (side == 0) { ax = tx; ay = clamp(hole.centerY(), ty + r, ty + th - r); }
        else { ax = clamp(hole.centerX(), tx + r, tx + tw - r); ay = side == 1 ? ty : ty + th; }
        LayoutParams ap = (LayoutParams) accLine.getLayoutParams();
        int lw = tw - px(0.6f), lh = Math.max(2, px(0.03f));
        if (ap.width != lw || ap.height != lh) { ap.width = lw; ap.height = lh; accLine.setLayoutParams(ap); }
        arrow.setColor(side == 2 ? bottom : top);
        applyPos();
    }

    /** 气泡、顶上那道线摆到算好的位置（进场时往下错开一点、淡着） */
    private void applyPos() {
        float dy = (1 - enter) * rem * 0.16f;
        tip.setTranslationX(tx); tip.setTranslationY(ty + dy); tip.setAlpha(enter);
        accLine.setTranslationX(tx + px(0.3f)); accLine.setTranslationY(ty + dy); accLine.setAlpha(enter);
        invalidate();
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : v > b ? b : v; }

    @Override
    protected void onDraw(Canvas c) {
        if (hole.isEmpty()) return;
        float rad = rem * 0.24f;
        path.reset();
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        path.addRoundRect(hole, rad, rad, Path.Direction.CW);
        c.drawPath(path, mask);
        float o = rem * 0.04f;
        tmp.set(hole.left - o, hole.top - o, hole.right + o, hole.bottom + o);
        c.drawRoundRect(tmp, rad + o, rad + o, edge);
        float s = 1 + 0.12f * pk; // 呼吸光：往外扩、淡掉
        float hw = tmp.width() * s / 2, hh = tmp.height() * s / 2, cx = tmp.centerX(), cy = tmp.centerY();
        ring.setColor(acc);
        ring.setAlpha(Math.round(204 * (1 - pk)));
        c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, (rad + o) * s, (rad + o) * s, ring);
        // 箭头：一个转了 45° 的小方块，一半压在气泡下面
        float a = rem * 0.09f;
        arrow.setAlpha(Math.round(255 * enter));
        arrowLine.setAlpha(Math.round((lineC >>> 24) * enter));
        c.save();
        c.translate(ax, ay + (1 - enter) * rem * 0.16f);
        c.rotate(45);
        c.drawRect(-a, -a, a, a, arrow);
        c.drawRect(-a, -a, a, a, arrowLine);
        c.restore();
    }
}
