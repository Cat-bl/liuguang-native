package com.x3card.liuguang;

import android.view.View;

/** 场景的画布：GL 的 SceneView、Vulkan 的 VkSceneView 都是这几个用法（同 GLSurfaceView） */
interface Canvas3D {
    Scene scene();
    View view();
    void onPause();
    void onResume();
    /** 要画一帧（哪个线程都能叫） */
    void requestRender();
    /** 放到画画线程里做 */
    void queueEvent(Runnable r);
    /** 窗口大小（CSS 像素，还没量出来是 0） */
    float cssW();
    float cssH();
    /** 画布每边按多少倍画（见 Res） */
    float res();
    /** 改倍数：画布换成新尺寸（哪个线程都能叫） */
    void setRes(float k);
}
