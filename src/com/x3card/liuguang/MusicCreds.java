package com.x3card.liuguang;

/**
 * 四家音乐接口的凭证。分三类：
 * - 公共常量（appId、版本号这些，请求里本来就明文带着）：写死在这里。
 * - 签名需要的参数：不在这里，由 MusicNative 提供；缺失时 MusicNative.ready() 为假，音乐接口用不了。
 * - 运行时值（设备号、各家 token）：RootCreds 从「多媒体」读覆盖，读不到用默认。
 * 各接口的来历、公式见 MusicSource 各实现类头和记忆 mediacenter-music-apis。
 */
final class MusicCreds {
    private MusicCreds() {}

    // ===== 非密钥常量 =====
    static final String KG_PID = "202511";
    static final String KG_APK_VER = "20103316";
    static final String KG_CLIENT_VER = "165-b87916967-20260703145909";
    static final String QQ_APP_ID = "2000000522";
    static final String QQ_BUS_CV = "20103316";
    static final String QQ_CV = "40500";
    static final String NE_APP_ID = "a301020000000000b1e7555e5d630228";
    static final String NE_SDK_VER = "0.4.5.20260818170724";
    static final String KW_VERSION = "3.9.5.28";
    static final String KW_CHANNEL = "sdk_C_SDK_BYD_DiL";

    // 签名需要的参数由 MusicNative 提供（见 MusicNative / jni/music.cpp），签名也在 native 算。

    // ===== 运行时值（RootCreds 覆盖；默认是模拟器那台车的）=====
    static volatile String deviceId = "2d7018ad7b40e39099d72816c1385f161";
    // 酷狗：默认游客（退登后「多媒体」就用这组，搜歌够了，不用 root）；RootCreds 读到真登录才覆盖，供个人数据用
    static volatile String kgUserid = "anonymous";
    static volatile String kgToken = "password";
    // 网易云搜歌必需（从 mmkv 读；匿名 token 也行）
    static volatile String neAccessToken = "";
    // QQ（uid=0 也能搜；设备号/qimei/tech_version 签名要带，难通用获取，先用抓到的；不是秘密、服务器不校验）
    static volatile String qqUid = "0";
    static volatile String qqDeviceId = "ffffffffc12e218d000001a1ae4721dd";
    static volatile String qqQimei36 = "0505d0f8074583c60678a7fd10001ed1910a";
    static volatile String qqTechVersion = "sdk|b95f93fef534f83325a410d5baec5d31";
    // 酷我（无需登录；appUid/appId 从 XML 读，q36 设备指纹）
    static volatile String kwAppUid = "2964300870";
    static volatile String kwAppId = "l73ab843hfpf";
    static volatile String kwQ36 = "f2ce3c2ef68ddfd1b2bea7ed00001f314716";

    static byte[] hex(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
