package com.x3card.liuguang;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.ArrayList;

/**
 * 画 3D 场景的 Vulkan 画布（见 Vk）：用法同 SceneView（GLSurfaceView 的那几个方法），自己开一个画画线程。
 * 画布按密度 1.5 画、系统拉伸到窗口大小（同 SceneView）；抗锯齿是 4 倍多重采样，建画布时定。
 * 原生那边的设备、贴图这些一直留着（同 GL 的 setPreserveEGLContextOnPause），画布没了只拆交换链，再有了重建交换链。
 */
final class VkSceneView extends SurfaceView implements SurfaceHolder.Callback, Canvas3D {
    final Scene scene;
    private final float dens;
    private final int samples;
    private final Object lock = new Object();
    private final ArrayList<Runnable> events = new ArrayList<>();
    private Surface surface;
    private boolean surfaceNew, sized, paused, requested, gone, exit, hasSurface;
    private int sw, sh;
    private final Thread th;

    VkSceneView(Context c, Scene.Host host, boolean aa) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        samples = aa ? 4 : 1;
        getHolder().addCallback(this);
        scene = new Scene(this, host, dens);
        th = new Thread(this::loop, "VkThread");
        th.start();
    }

    @Override public Scene scene() { return scene; }
    @Override public View view() { return this; }

    @Override
    public void surfaceCreated(SurfaceHolder h) { synchronized (lock) { surface = h.getSurface(); surfaceNew = true; lock.notifyAll(); } }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { synchronized (lock) { sw = w; sh = hh; sized = true; requested = true; lock.notifyAll(); } }

    /** 画布要没了：等画画线程把交换链拆掉再返回（最多等 2 秒，免得卡住主线程） */
    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        synchronized (lock) {
            surface = null; gone = true; lock.notifyAll();
            long end = System.currentTimeMillis() + 2000;
            while (gone && !exit) { long left = end - System.currentTimeMillis(); if (left <= 0) break; try { lock.wait(left); } catch (InterruptedException e) { break; } }
        }
    }

    @Override public void onPause() { synchronized (lock) { paused = true; } }
    @Override public void onResume() { synchronized (lock) { paused = false; requested = true; lock.notifyAll(); } }
    @Override public void requestRender() { synchronized (lock) { requested = true; lock.notifyAll(); } }
    @Override public void queueEvent(Runnable r) { synchronized (lock) { events.add(r); lock.notifyAll(); } }

    @Override
    protected void onDetachedFromWindow() {
        synchronized (lock) { exit = true; lock.notifyAll(); }
        super.onDetachedFromWindow();
    }

    private void loop() {
        Vk vk = null;
        boolean failed = false;
        while (true) {
            Runnable ev = null;
            Surface mk = null;
            boolean lose = false, resize = false, draw = false;
            synchronized (lock) {
                while (true) {
                    if (exit) break;
                    if (gone) { lose = true; break; }
                    if (surfaceNew && surface != null && !failed) { mk = surface; surfaceNew = false; break; }
                    if (vk != null && !events.isEmpty()) { ev = events.remove(0); break; }
                    if (vk != null && hasSurface && sized) { sized = false; resize = true; break; }
                    if (vk != null && hasSurface && !paused && requested) { requested = false; draw = true; break; }
                    try { lock.wait(); } catch (InterruptedException e) { exit = true; }
                }
                if (exit) break;
            }
            if (ev != null) { ev.run(); continue; }
            if (lose) {
                if (vk != null && hasSurface) vk.surfaceLost();
                hasSurface = false;
                synchronized (lock) { gone = false; lock.notifyAll(); }
                continue;
            }
            if (mk != null) {
                if (vk == null) {
                    vk = Vk.create(mk, getContext().getAssets(), getContext().getCacheDir().getAbsolutePath(), samples);
                    if (vk == null) {
                        failed = true;
                        Vk.failed = true;
                        Log.w(Uiplus.TAG, "Vulkan：起不来，换成 OpenGL");
                        post(() -> { if (getContext() instanceof MainActivity) ((MainActivity) getContext()).vkFailed(); });
                        continue;
                    }
                    G.useVk(vk);
                    hasSurface = true;
                    scene.onSurfaceCreated(null, null);
                } else hasSurface = vk.surface(mk);
                continue;
            }
            if (resize) { vk.resize(false); scene.onSurfaceChanged(null, sw, sh); continue; }
            if (draw) {
                vk.begin();
                scene.onDrawFrame(null);
                int r = vk.end();
                if (r == 1) { vk.resize(true); requestRender(); }
            }
        }
        if (vk != null) { vk.destroy(); G.useVk(null); }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float k = Math.min(dens, 1.5f) / dens;
        final int fw = Math.max(1, Math.round(w * k)), fh = Math.max(1, Math.round(h * k));
        post(() -> getHolder().setFixedSize(fw, fh)); // 同 SceneView
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: a = 0; break;
            case MotionEvent.ACTION_MOVE: a = 1; break;
            case MotionEvent.ACTION_UP: a = 2; break;
            case MotionEvent.ACTION_CANCEL: a = 3; break;
            default: return true;
        }
        final int act = a;
        final float x = e.getX() / Math.max(1, getWidth()), y = e.getY() / Math.max(1, getHeight());
        final long t = e.getEventTime();
        queueEvent(() -> scene.touch(act, x, y, t));
        return true;
    }
}
