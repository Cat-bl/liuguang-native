package com.x3card.liuguang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 网易云：openapi.music.163.com/openapi/music/basic/<endpoint>，POST form-urlencoded。
 * 鉴权：body 带 appId/signType=RSA_SHA256/timestamp/bizContent/device/accessToken + sign。
 * sign = base64(RSA-SHA256(私钥, 除 sign 外所有参数按 key 字母排序拼成 "k=v&"，值是原始未编码的))。
 * bizContent 是业务参数的 JSON（值都当字符串）。搜歌必需车主 accessToken（RootCreds 从「多媒体」读）。
 * 加别的接口：endpoint 换路径，biz 换业务参数即可。搜歌：endpoint=search/song/get/v2，结果 data.records[]。
 */
final class NeteaseSource implements MusicSource {
    private static final String BASE = "https://openapi.music.163.com/openapi/music/basic/";
    // 固定的 device（deviceId 用 "empty" 即可，抓包就是这样）
    private static String deviceJson() {
        return Jx.json(Jx.map(
            "channel", "byd_sdk_new", "deviceId", "empty", "deviceType", "andrcar",
            "appVer", "1.1.1.1", "os", "andrcar", "osVer", "12", "brand", "byd_sdk_new",
            "model", "DCO-AL00", "clientIp", "10.0.2.5", "sdkVer", MusicCreds.NE_SDK_VER));
    }

    @Override public int code() { return 12; }
    @Override public String name() { return "网易云音乐"; }
    @Override public boolean needsLogin() { return true; } // 搜歌也要 accessToken

    @Override public Req build(String endpoint, Map<String, Object> biz) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("appId", MusicCreds.NE_APP_ID);
        p.put("signType", "RSA_SHA256");
        p.put("timestamp", String.valueOf(System.currentTimeMillis()));
        p.put("bizContent", Jx.json(biz == null ? Jx.map() : biz));
        p.put("device", deviceJson()); // JSON 串；sign 时原样、body 时 url 编码
        p.put("accessToken", MusicCreds.neAccessToken);

        // sign base：除 sign 外所有参数按 key 字母排序，"k=v&"，值用原始串
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : p.entrySet()) sorted.put(e.getKey(), String.valueOf(e.getValue()));
        StringBuilder base = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (base.length() > 0) base.append('&');
            base.append(e.getKey()).append('=').append(e.getValue());
        }
        String sign = MCrypto.rsaSha256SignB64(base.toString(), MusicNative.neParam());
        p.put("sign", sign);

        String body = Jx.query(p); // form-urlencoded，顺序无所谓
        return new Req("POST", BASE + endpoint,
            Http.h("Content-Type", "application/x-www-form-urlencoded"),
            body.getBytes(MCrypto.UTF8));
    }

    @Override public String decode(Http.Resp resp) { return resp.text(); }
}
