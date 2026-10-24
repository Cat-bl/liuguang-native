package com.x3card.liuguang;

/** 弹簧（同网页版：iOS 的默认手感 response 0.55、阻尼 0.825 ≈ k 130、c 19）；step 返回还在不在动 */
final class Spring {
    float x, t, v, k, c, eps;

    Spring(float x, float k, float c, float eps) { this.x = x; this.t = x; this.k = k; this.c = c; this.eps = eps; }
    Spring(float x, float k, float c) { this(x, k, c, 0.001f); }

    boolean step(float dt) {
        if (x == t && v == 0) return false;
        int n = (int) Math.ceil(dt / 0.004f);
        float h = dt / Math.max(1, n);
        for (int i = 0; i < n; i++) { v += (-k * (x - t) - c * v) * h; x += v * h; }
        if (Math.abs(x - t) < eps && Math.abs(v) < eps * 8) { x = t; v = 0; return false; }
        return true;
    }

    void set(float x) { this.x = this.t = x; v = 0; }
}
