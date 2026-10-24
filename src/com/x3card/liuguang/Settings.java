package com.x3card.liuguang;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Arrays;

/**
 * 设置（同网页版 lg-settings：同样的项、同样的默认值；字体只有得意黑和系统字体，没有霞鹜文楷），存在 SharedPreferences "lg" 的 "settings" 里（JSON），读的时候按白名单校验，坏了回默认。
 * 小窗口和全屏页（两个 Activity）共用一份：回到前台时重新读一次
 */
final class Settings {
    String font = "smiley", theme = "auto", fps = "60", rain = "auto";
    boolean aa, water = true, art = true, ribbons = true, intro = true, reveal = true, fsBar;

    static final String[] FONTS = { "smiley", "system" }, THEMES = { "auto", "light", "dark" }, FPS = { "auto", "60", "30" }, RAIN = { "auto", "on", "off" };

    static Settings load(Context c) {
        Settings s = new Settings();
        try {
            JSONObject o = new JSONObject(c.getSharedPreferences("lg", Context.MODE_PRIVATE).getString("settings", "{}"));
            s.font = pick(o.optString("font"), FONTS, s.font);
            s.theme = pick(o.optString("theme"), THEMES, s.theme);
            s.fps = pick(o.optString("fps"), FPS, s.fps);
            s.aa = bool(o, "aa", s.aa);
            s.water = bool(o, "water", s.water);
            s.art = bool(o, "art", s.art);
            s.ribbons = bool(o, "ribbons", s.ribbons);
            Object r = o.opt("rain");
            s.rain = Boolean.TRUE.equals(r) ? "on" : pick(o.optString("rain"), RAIN, s.rain); // 以前雨珠只有开 / 关（关是默认）
            s.intro = bool(o, "intro", s.intro);
            s.reveal = bool(o, "reveal", s.reveal);
            s.fsBar = bool(o, "fsBar", s.fsBar);
        } catch (Exception ignored) { /* 坏了就用默认值 */ }
        return s;
    }

    void save(Context c) {
        try {
            JSONObject o = new JSONObject().put("font", font).put("theme", theme).put("fps", fps).put("aa", aa).put("water", water).put("art", art)
                .put("ribbons", ribbons).put("rain", rain).put("intro", intro).put("reveal", reveal).put("fsBar", fsBar);
            SharedPreferences.Editor e = c.getSharedPreferences("lg", Context.MODE_PRIVATE).edit();
            e.putString("settings", o.toString()).apply();
        } catch (Exception ignored) { /* 存不了就不存 */ }
    }

    /** 按键名取值（面板用）：选项类是字符串，开关类是布尔 */
    Object get(String k) {
        switch (k) {
            case "font": return font;
            case "theme": return theme;
            case "fps": return fps;
            case "aa": return aa;
            case "water": return water;
            case "art": return art;
            case "ribbons": return ribbons;
            case "rain": return rain;
            case "intro": return intro;
            case "reveal": return reveal;
            case "fsBar": return fsBar;
            default: return null;
        }
    }

    void set(String k, Object v) {
        switch (k) {
            case "font": font = pick((String) v, FONTS, font); break;
            case "theme": theme = pick((String) v, THEMES, theme); break;
            case "fps": fps = pick((String) v, FPS, fps); break;
            case "aa": aa = (Boolean) v; break;
            case "water": water = (Boolean) v; break;
            case "art": art = (Boolean) v; break;
            case "ribbons": ribbons = (Boolean) v; break;
            case "rain": rain = pick((String) v, RAIN, rain); break;
            case "intro": intro = (Boolean) v; break;
            case "reveal": reveal = (Boolean) v; break;
            case "fsBar": fsBar = (Boolean) v; break;
            default: break;
        }
    }

    Settings copy() {
        Settings s = new Settings();
        s.font = font; s.theme = theme; s.fps = fps; s.aa = aa; s.water = water; s.art = art; s.ribbons = ribbons; s.rain = rain; s.intro = intro; s.reveal = reveal; s.fsBar = fsBar;
        return s;
    }

    private static String pick(String v, String[] ok, String def) { return v != null && Arrays.asList(ok).contains(v) ? v : def; }

    private static boolean bool(JSONObject o, String k, boolean def) { Object v = o.opt(k); return v instanceof Boolean ? (Boolean) v : def; }
}
