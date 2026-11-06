package com.x3card.liuguang;

import android.util.Log;

/**
 * 音乐接口的签名 native 层（jni/music.cpp，并进 libliuguang.so）。
 * 酷狗 md5、QQ HmacSHA256、酷我 AES 在 native 里算；网易云用系统 RSA 签名，native 提供它需要的参数。
 * native 载不上或参数不全时 ready() 为假，音乐接口不可用。
 */
final class MusicNative {
    private static boolean ready;

    static {
        try {
            System.loadLibrary("liuguang");
            ready = has();
        } catch (Throwable t) {
            Log.w(Uiplus.TAG, "音乐 native 载不进来：" + t);
        }
    }

    /** native 库载上了、而且密钥齐 */
    static boolean ready() { return ready; }

    static native boolean has();
    static native String kgSign(String body);      // 酷狗 signature（md5 小写 hex）
    static native String qqSign(String query);     // QQ X-QYOPI-Sign（HmacSHA256 小写 hex）
    static native byte[] kwEnc(byte[] plain);      // 酷我 data（AES-ECB 加密的字节，调用方再 base64）
    static native byte[] kwDec(byte[] cipher);     // 酷我响应解密
    static native String neParam();                // 网易云 RSA 签名需要的参数（PKCS8 base64），交 Java 签
}
