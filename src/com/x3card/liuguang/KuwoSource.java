package com.x3card.liuguang;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

import android.util.Base64;

/**
 * 酷我：wbd.kuwo.cn/api/bd/music/<endpoint>，GET，参数 data/sign/appId/time/q36。
 * 鉴权：data = base64(AES-ECB(业务参数的 JSON，含固定的 kuwo_sdk/car 等字段))；sign = md5(appId + data + time).大写。
 * 响应：成功是 url 编码的 base64 密文（AES 解密成 JSON）；出错是明文 JSON（如 AppId 错误）。decode 两种都认。
 * 无需车主登录（appUid/appId 是设备级，RootCreds 从「多媒体」XML 读）。加别的接口：endpoint 换、biz 换。
 * 搜歌：endpoint=search_music，业务参数 key/pn/rn，结果 data[]。
 */
final class KuwoSource implements MusicSource {
    private static final String BASE = "https://wbd.kuwo.cn/api/bd/music/";

    @Override public int code() { return 6; }
    @Override public String name() { return "酷我音乐"; }
    @Override public boolean needsLogin() { return false; }

    @Override public Req build(String endpoint, Map<String, Object> biz) {
        Map<String, Object> pt = new LinkedHashMap<>();
        pt.put("uid", ""); pt.put("sid", ""); pt.put("loginUid", ""); pt.put("loginSid", "");
        pt.put("devId", "sdk_C_SDK_BYD_DiL_" + MusicCreds.deviceId);
        pt.put("appUid", MusicCreds.kwAppUid);
        pt.put("sFrom", "kuwo_sdk");
        pt.put("platform", "car");
        pt.put("q36", MusicCreds.kwQ36);
        pt.put("channel", MusicCreds.KW_CHANNEL);
        pt.put("version", MusicCreds.KW_VERSION);
        pt.put("user_type", "AP");
        pt.put("carSource", "kwplayercar_ar_" + MusicCreds.KW_VERSION + "_" + MusicCreds.KW_CHANNEL + ".apk");
        pt.put("virtualUid", -1);
        pt.put("virtualSid", "");
        if (biz != null) pt.putAll(biz);

        String json = Jx.json(pt);
        String data = Base64.encodeToString(MusicNative.kwEnc(json.getBytes(MCrypto.UTF8)), Base64.NO_WRAP);
        String time = String.valueOf(System.currentTimeMillis());
        String sign = MCrypto.md5Hex(MusicCreds.kwAppId + data + time, true);

        Map<String, Object> q = new LinkedHashMap<>();
        q.put("data", data);
        q.put("sign", sign);
        q.put("appId", MusicCreds.kwAppId);
        q.put("time", time);
        q.put("q36", MusicCreds.kwQ36);
        return new Req("GET", BASE + endpoint + "?" + Jx.query(q), null, null);
    }

    @Override public String decode(Http.Resp resp) {
        String t = resp.text().trim();
        if (t.startsWith("{") || t.startsWith("[")) return t; // 明文（出错）
        try {
            String b64 = URLDecoder.decode(t, "UTF-8");
            byte[] plain = MusicNative.kwDec(Base64.decode(b64, Base64.DEFAULT));
            return new String(plain, MCrypto.UTF8);
        } catch (UnsupportedEncodingException e) {
            return t;
        } catch (Exception e) {
            return t; // 解不了就原样（让上层看到）
        }
    }
}
