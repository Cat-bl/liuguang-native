package com.x3card.liuguang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QQ 音乐：qplaycloud-quic.y.qq.com 的 music_open_api.fcg（IOT UNION SDK，app_id 2000000522），GET。
 * 鉴权：头 X-QYOPI-Sign = HmacSHA256(URL 编码后的 query 串, 密钥)。
 * 注意：① 签的是"编码后的 query"（tech_version 里的 | 以 %7C 参与签名），和发出去的 query 用同一个串；
 *       ② 参数顺序是 SDK 定的（服务器按固定序重算，排序会被拒），所以业务参数在前、按调用方给的顺序，再接固定的公共参数块。
 * 加别的接口：endpoint 传 opi_cmd（如 fcg_music_custom_get_song_info.fcg），biz 传这个接口的业务参数（保持 SDK 抓到的顺序）。
 * 搜歌：endpoint=fcg_music_custom_search.fcg，业务参数见下面 MusicApi.search；结果 JSON 的 list[]。
 */
final class QQSource implements MusicSource {
    private static final String BASE = "https://qplaycloud-quic.y.qq.com/rpc_proxy/fcgi-bin/music_open_api.fcg";

    @Override public int code() { return 10; }
    @Override public String name() { return "QQ音乐"; }
    @Override public boolean needsLogin() { return false; } // 搜歌 uid=0 也行

    @Override public Req build(String endpoint, Map<String, Object> biz) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (biz != null) p.putAll(biz);           // 业务参数在前（按 SDK 顺序）
        p.put("opi_cmd", endpoint);
        p.put("aes_version", "1");
        p.put("app_id", MusicCreds.QQ_APP_ID);
        p.put("brand", "");
        p.put("bus_cv", MusicCreds.QQ_BUS_CV);
        p.put("car_type", "0");
        p.put("client_ip", "");
        p.put("ct", "sdk_android");
        p.put("cv", MusicCreds.QQ_CV);
        p.put("device_id", MusicCreds.qqDeviceId);
        p.put("qqmusic_open_appid", MusicCreds.QQ_APP_ID);
        p.put("opi_device_id", MusicCreds.deviceId);
        p.put("qimei36", MusicCreds.qqQimei36);
        p.put("screen_id", "");
        p.put("sign_version", "v3");
        p.put("stream_format", "1");
        p.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        p.put("uid", MusicCreds.qqUid);
        p.put("tech_version", MusicCreds.qqTechVersion);
        p.put("cookie", ""); // 关键：cookie 参与签名，但不放进 URL（SDK 就这样，放进 URL 会被判签名不合法）

        String signQuery = Jx.query(p);                        // 含 cookie，用来签名
        String sign = MusicNative.qqSign(signQuery);
        p.remove("cookie");
        String urlQuery = Jx.query(p);                         // 不含 cookie，用来发
        return new Req("GET", BASE + "?" + urlQuery,
            Http.h("X-QYOPI-Sign", sign, "User-Agent", "IOT UNION SDK-Android-40500", "Accept", "*/*"),
            null);
    }

    @Override public String decode(Http.Resp resp) { return resp.text(); }
}
