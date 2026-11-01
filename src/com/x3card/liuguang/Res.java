package com.x3card.liuguang;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** 画布每边按多少倍画（Scene 按帧率自动定，画布按它定尺寸、系统拉伸到窗口大小）：按窗口大小（CSS 像素"宽x高"）记住，存 lg 的 res，下次打开直接用 */
final class Res {
    private static SharedPreferences sp;
    private static JSONObject map;

    private static JSONObject map(Context c) {
        if (map == null) {
            sp = c.getApplicationContext().getSharedPreferences("lg", Context.MODE_PRIVATE);
            try { map = new JSONObject(sp.getString("res", "{}")); } catch (Exception e) { map = new JSONObject(); }
        }
        return map;
    }

    static synchronized float recall(Context c, String key) {
        double k = map(c).optDouble(key, 1);
        return k >= 0.5 && k <= 1 ? (float) k : 1;
    }

    static synchronized void remember(Context c, String key, float k) {
        JSONObject m = map(c);
        if (Math.abs(m.optDouble(key, 1) - k) < 0.005) return;
        if (m.length() > 16) m = map = new JSONObject(); // 悬浮窗口拖出过很多种大小：清掉重记
        try { if (k > 0.995f) m.remove(key); else m.put(key, Math.round(k * 1000) / 1000.0); } catch (Exception ignored) { }
        sp.edit().putString("res", m.toString()).apply();
    }
}
