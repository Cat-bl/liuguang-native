package com.x3card.liuguang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 酷狗：thirdsso.kugou.com/v2/<endpoint>，POST 明文 JSON body。
 * 鉴权：头 signature = md5(bodyJSON + 密钥)。body 套 device_id/pid/apk_ver/nonce/userid/token/timestamp 等标准参数 + 业务参数。
 * 签名只要"签的串 = 发的串"，所以参数顺序随意（这里按插入序拼一次，既签又发）。响应是明文 JSON。
 * 搜歌：endpoint=search/song，业务参数 keyword/page/size，结果在 data.songs[]。
 */
final class KugouSource implements MusicSource {
    private static final String BASE = "https://thirdsso.kugou.com/v2/";
    private static final Jx.Raw DEVICE_INFO = new Jx.Raw(
        "{\"resolution_height\":1080,\"resolution_width\":1920,\"api_level\":32,\"device_level\":2,\"memory_size\":4.8251953}");

    @Override public int code() { return 5; }
    @Override public String name() { return "酷狗音乐"; }
    @Override public boolean needsLogin() { return false; } // 搜歌公开；个人歌单才要 token

    @Override public Req build(String endpoint, Map<String, Object> biz) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("quality_filter", 2);
        p.put("device_id", MusicCreds.deviceId);
        p.put("pid", MusicCreds.KG_PID);
        p.put("apk_ver", MusicCreds.KG_APK_VER);
        p.put("nonce", UUID.randomUUID().toString().replace("-", ""));
        p.put("userid", MusicCreds.kgUserid); // 默认 anonymous/password（游客，搜歌够）；登录后是真 userid/token
        p.put("token", MusicCreds.kgToken);
        if (biz != null) p.putAll(biz);
        p.put("device_info", DEVICE_INFO);
        p.put("client_ver", MusicCreds.KG_CLIENT_VER);
        p.put("client_ip", "127.0.0.1");
        p.put("sp", "KG");
        p.put("timestamp", System.currentTimeMillis() / 1000);

        String body = Jx.json(p);
        String sign = MusicNative.kgSign(body);
        return new Req("POST", BASE + endpoint,
            Http.h("signature", sign, "Content-Type", "application/json; charset=utf-8"),
            body.getBytes(MCrypto.UTF8));
    }

    @Override public String decode(Http.Resp resp) { return resp.text(); }
}
