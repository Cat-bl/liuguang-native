package com.x3card.liuguang;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 车机自带的天气（系统应用 com.byd.weatherdata，桌面天气卡片用的就是它）：对外开放的数据接口，不要权限。
 * 它自己联网、自己定位，每 10～11 分钟刷新一次，刷新完数据接口发变化通知、还发 THIRD_REFRESH 广播（给第三方用的）。
 * 一行数据，name 列是整段 JSON（data 下面）：condition 当前天气（weathertext 文字、updatetime），radar 未来两小时逐分钟降水
 * （dataTime 起每分钟一个，dataseries）。我们不用自己定位、不用联网。
 * 在不在下雨：逐分钟降水里现在这一分钟有雨，或者当前天气（2 小时内更新过）的文字里有"雨"（小雨、阵雨、雷阵雨、雨夹雪、冻雨……）。
 * 逐分钟降水是预报，按现在的时间往后取，所以离上次刷新过了几分钟也是现在这一分钟的；两小时都过了、当前天气也太旧就当查不到
 */
final class BydWeather {
    static final Uri URI = Uri.parse("content://com.byd.weatherdata.utils.WeatherContentProvider/weather");
    static final String REFRESH = "com.byd.weatherdata.action.THIRD_REFRESH";
    private static final long COND_OK = 2 * 3600000L;
    private static final double RAIN_MM = 0.05; // 逐分钟降水超过这个算在下

    private volatile String text;
    private volatile long condAt, radarAt;
    private volatile double[] radar;
    String err = ""; // 上次没读成的原因（排查用）

    /** 读一次（数据接口在另一个进程、整段 JSON 几十 KB：在后台线程调）；读不到、正在刷新（先删后写的空档）返回 false，留着上一份 */
    boolean read(ContentResolver cr) {
        String json = null;
        try (Cursor c = cr.query(URI, null, null, null, null)) {
            if (c != null && c.moveToFirst()) json = c.getString(c.getColumnIndexOrThrow("name"));
        } catch (Exception e) {
            err = String.valueOf(e);
            return false;
        }
        if (json == null || json.isEmpty()) { err = "没有数据"; return false; }
        try {
            JSONObject o = new JSONObject(json), d = o.optJSONObject("data");
            if (d == null) d = o;
            JSONObject cond = d.optJSONObject("condition"), rad = d.optJSONObject("radar");
            text = cond == null ? null : cond.optString("weathertext", null);
            condAt = cond == null ? 0 : cond.optLong("updatetime", d.optLong("updatetime"));
            JSONArray s = rad == null ? null : rad.optJSONArray("dataseries");
            if (s != null && s.length() > 0) {
                double[] r = new double[s.length()];
                for (int i = 0; i < r.length; i++) r[i] = s.optDouble(i, 0);
                radar = r;
                radarAt = rad.optLong("dataTime");
            } else { radar = null; radarAt = 0; }
            return true;
        } catch (Exception e) {
            err = "数据看不懂：" + e;
            return false;
        }
    }

    /** 现在在不在下雨：true / false，查不到（没数据、太旧）是 null。now 是墙上时间（毫秒） */
    Boolean rainingNow(long now) {
        double[] r = radar;
        Boolean byRadar = null, byText = null;
        if (r != null && radarAt > 0) {
            long i = (now - radarAt) / 60000;
            if (i >= 0 && i < r.length) byRadar = r[(int) i] > RAIN_MM;
        }
        String t = text;
        if (t != null && condAt > 0 && now - condAt < COND_OK) byText = t.contains("雨");
        if (Boolean.TRUE.equals(byRadar) || Boolean.TRUE.equals(byText)) return true;
        return byRadar != null || byText != null ? Boolean.FALSE : null;
    }

    String debug() { return text + " cond=" + condAt + " radarAt=" + radarAt + " n=" + (radar == null ? 0 : radar.length); }
}
