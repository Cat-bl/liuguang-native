package com.x3card.liuguang;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * 画 3D 场景的画布：OpenGL ES 3.0、带深度缓冲；画布按密度 1.5 画（同网页版的像素比上限，车机密度 2 时少画四成多的像素），系统拉伸到窗口大小。
 * 抗锯齿（设置里开）是画布的 4 倍多重采样，只能在建画布时定：改了设置就换一块新画布（MainActivity.rebuild）；显卡不支持就不开。
 * 停着不动不出帧（场景要画才 requestRender）。手势交给场景（在 GL 线程里处理）
 */
public class SceneView extends GLSurfaceView {
    final Scene scene;
    private final float dens;

    public SceneView(Context c, Scene.Host host, boolean aa) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new Chooser(aa));
        setPreserveEGLContextOnPause(true);
        scene = new Scene(this, host, dens);
        setRenderer(scene);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** RGBA8 + 24 位深度；要抗锯齿时先找 4 倍多重采样的，找不到退回不开 */
    private static final class Chooser implements EGLConfigChooser {
        private final boolean aa;
        Chooser(boolean aa) { this.aa = aa; }

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            EGLConfig c = aa ? find(egl, d, 4) : null;
            if (c == null) c = find(egl, d, 0);
            if (c == null) throw new IllegalArgumentException("没有能用的画布格式");
            return c;
        }

        private static EGLConfig find(EGL10 egl, EGLDisplay d, int samples) {
            int[] attr = {
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0, EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE };
            int[] n = new int[1];
            if (!egl.eglChooseConfig(d, attr, null, 0, n) || n[0] <= 0) return null;
            EGLConfig[] list = new EGLConfig[n[0]];
            if (!egl.eglChooseConfig(d, attr, list, n[0], n)) return null;
            // eglChooseConfig 按"至少"挑，排在前面的可能位数更多：挑颜色正好 8 位、深度正好 24 的
            for (EGLConfig c : list) {
                if (get(egl, d, c, EGL10.EGL_RED_SIZE) == 8 && get(egl, d, c, EGL10.EGL_ALPHA_SIZE) == 8 && get(egl, d, c, EGL10.EGL_DEPTH_SIZE) == 24
                    && get(egl, d, c, EGL10.EGL_SAMPLES) == samples) return c;
            }
            return list[0];
        }

        private static int get(EGL10 egl, EGLDisplay d, EGLConfig c, int a) {
            int[] v = new int[1];
            return egl.eglGetConfigAttrib(d, c, a, v) ? v[0] : 0;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float k = Math.min(dens, 1.5f) / dens;
        final int fw = Math.max(1, Math.round(w * k)), fh = Math.max(1, Math.round(h * k));
        post(() -> getHolder().setFixedSize(fw, fh)); // 在布局过程中直接设有时不生效：放到下一轮
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: a = 0; break;
            case MotionEvent.ACTION_MOVE: a = 1; break;
            case MotionEvent.ACTION_UP: a = 2; break;
            case MotionEvent.ACTION_CANCEL: a = 3; break;
            default: return true; // 第二根手指：不管
        }
        final int act = a;
        final float x = e.getX() / Math.max(1, getWidth()), y = e.getY() / Math.max(1, getHeight());
        final long t = e.getEventTime();
        queueEvent(() -> scene.touch(act, x, y, t));
        return true;
    }
}
