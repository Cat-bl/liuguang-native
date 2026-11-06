package com.x3card.liuguang;

import java.util.Map;

/**
 * 一家音乐平台的"签名器"：把业务参数套上标准参数、按这家的方式签名/加密，产出一个可直接发的请求；并负责解响应。
 * 四家各一个实现（KugouSource/QQSource/NeteaseSource/KuwoSource），算法见各自类头。
 * 加新接口不用动这里：调用方给 endpoint + 业务参数，build 通用地签名。凭证从 MusicCreds 取（运行时从「多媒体」读）。
 */
interface MusicSource {

    /** 音源号，同 BydMedia：5 酷狗 6 酷我 10 QQ 12 网易云 */
    int code();

    /** 给人看的名字 */
    String name();

    /** 这家的接口需不需要车主登录（个人数据用）。搜歌等公开数据多数不需要（网易云例外，要 accessToken） */
    boolean needsLogin();

    /**
     * 把 endpoint（这家的接口名/路径）+ 业务参数 biz 组装成已签名的请求。
     * biz 的键名是这家接口自己的（如 QQ 搜歌 w、网易云 keyword、酷狗 playlist_id、酷我 key），框架不统一。
     * 值可以是 String / 数字 / 布尔。
     */
    Req build(String endpoint, Map<String, Object> biz);

    /** 把响应解成文本（酷我要 AES 解密；其余直接 UTF-8）。拿到文本后调用方用 org.json 解析 */
    String decode(Http.Resp resp) throws Exception;

    /** 一个可发送的请求 */
    final class Req {
        final String method, url;
        final Map<String, String> headers; // 可空
        final byte[] body;                 // 可空（GET）
        Req(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method; this.url = url; this.headers = headers; this.body = body;
        }
    }
}
