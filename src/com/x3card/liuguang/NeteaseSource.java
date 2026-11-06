package com.x3card.liuguang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONObject;

import android.util.Log;

/**
 * 网易云：openapi.music.163.com/openapi/music/basic/<endpoint>，POST form-urlencoded。
 * 鉴权：body 带 appId/signType=RSA_SHA256/timestamp/bizContent/device/accessToken + sign。
 * sign = base64(RSA-SHA256(私钥, 除 sign 外所有参数按 key 字母排序拼成 "k=v&"，值是原始未编码的))。
 * bizContent 是业务参数的 JSON（值都当字符串）。
 * accessToken：有车主 token（RootCreds 从「多媒体」读）就用它；没有就自助调 oauth2/login/anonymous 拿匿名 token
 * （该端点签名拦截器豁免 accessToken，不用已有 token、不用 root）。搜歌等公开数据用匿名 token 即可。
 * 加别的接口：endpoint 换路径，biz 换业务参数即可。搜歌：endpoint=search/song/get/v2，结果 data.records[]。
 */
final class NeteaseSource implements MusicSource {
    private static final String BASE = "https://openapi.music.163.com/openapi/music/basic/";
    private static volatile boolean anonTried; // 匿名登录只在本进程试一次（失败不反复打）

    // 固定的 device（deviceId 用 "empty" 即可，抓包就是这样）
    private static String deviceJson() {
        return Jx.json(Jx.map(
            "channel", "byd_sdk_new", "deviceId", "empty", "deviceType", "andrcar",
            "appVer", "1.1.1.1", "os", "andrcar", "osVer", "12", "brand", "byd_sdk_new",
            "model", "DCO-AL00", "clientIp", "10.0.2.5", "sdkVer", MusicCreds.NE_SDK_VER));
    }

    @Override public int code() { return 12; }
    @Override public String name() { return "网易云音乐"; }
    @Override public boolean needsLogin() { return false; } // 没车主 token 时自助用匿名 token

    @Override public Req build(String endpoint, Map<String, Object> biz) {
        ensureToken();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("appId", MusicCreds.NE_APP_ID);
        p.put("signType", "RSA_SHA256");
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("bizContent", Jx.json(biz == null ? Jx.map() : biz));
        p.put("device", deviceJson()); // JSON 串；sign 时原样、body 时 url 编码
        p.put("accessToken", MusicCreds.neAccessToken);
        String body = sign(p);
        return new Req("POST", BASE + endpoint,
            Http.h("Content-Type", "application/x-www-form-urlencoded"),
            body.getBytes(MCrypto.UTF8));
    }

    @Override public String decode(Http.Resp resp) { return resp.text(); }

    // 给参数算 sign 并返回 form body（除 sign 外按 key 字母排序，"k=v&"，值用原始串）
    private String sign(Map<String, Object> p) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : p.entrySet()) sorted.put(e.getKey(), String.valueOf(e.getValue()));
        StringBuilder base = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (base.length() > 0) base.append('&');
            base.append(e.getKey()).append('=').append(e.getValue());
        }
        p.put("sign", MCrypto.rsaSha256SignB64(base.toString(), MusicNative.neParam()));
        return Jx.query(p);
    }

    // 没有车主 token 时，自助调匿名登录拿一个（不用 root）。有 token 或已试过就跳过。
    private synchronized void ensureToken() {
        if (MusicCreds.neAccessToken != null && !MusicCreds.neAccessToken.isEmpty()) return;
        if (anonTried) return;
        anonTried = true;
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("clientId", MusicCreds.NE_APP_ID); // 匿名登录：应用层只给 clientId+appId
            p.put("appId", MusicCreds.NE_APP_ID);
            p.put("signType", "RSA_SHA256");
            p.put("timestamp", String.valueOf(System.currentTimeMillis()));
            p.put("device", deviceJson()); // 无 bizContent/accessToken（端点豁免 token）
            String body = sign(p);
            Http.Resp resp = Http.send("POST", BASE + "oauth2/login/anonymous",
                Http.h("Content-Type", "application/x-www-form-urlencoded"),
                body.getBytes(MCrypto.UTF8));
            JSONObject o = new JSONObject(resp.text());
            JSONObject data = o.optJSONObject("data");
            String tok = data == null ? null : data.optString("accessToken", null);
            if (tok != null && !tok.isEmpty()) {
                MusicCreds.neAccessToken = tok;
                Log.w(Uiplus.TAG, "网易云匿名 token 拿到了");
            } else {
                Log.w(Uiplus.TAG, "网易云匿名登录没拿到 token：" + resp.text());
            }
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "网易云匿名登录失败：" + e);
        }
    }
}
