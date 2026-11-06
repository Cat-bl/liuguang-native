package com.x3card.liuguang;

import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

/**
 * 音乐接口用到的加密原语（四家签名就这几样，见 MusicSource 各实现）：
 * md5（酷狗 signature、酷我 sign）、HmacSHA256（QQ X-QYOPI-Sign）、AES-ECB（酷我 data=）、RSA-SHA256（网易云 sign）。
 * 都走标准 java.security / javax.crypto，没有第三方依赖；JVM 上照样能跑（测试直接用）。
 */
final class MCrypto {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private MCrypto() {}

    /** md5 十六进制；upper=true 大写（酷我 sign），false 小写（酷狗 signature） */
    static String md5Hex(String s, boolean upper) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(UTF8));
            return hex(d, upper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** HmacSHA256 十六进制小写（QQ 的 X-QYOPI-Sign） */
    static String hmacSha256Hex(String data, String key) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(UTF8), "HmacSHA256"));
            return hex(m.doFinal(data.getBytes(UTF8)), false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** AES/ECB/PKCS5Padding 加密后 base64（酷我 data=；key 是 16 字节） */
    static String aesEcbEncB64(byte[] plain, byte[] key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** AES/ECB/PKCS5Padding 解密（酷我响应：先 urldecode→base64→这里） */
    static byte[] aesEcbDec(byte[] cipher, byte[] key) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(cipher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** RSA 私钥（PKCS8 的 base64）签名 data，SHA256withRSA，输出 base64（网易云 sign） */
    static String rsaSha256SignB64(String data, String pkcs8B64) {
        try {
            byte[] der = Base64.decode(pkcs8B64, Base64.DEFAULT);
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            Signature sg = Signature.getInstance("SHA256withRSA");
            sg.initSign(pk);
            sg.update(data.getBytes(UTF8));
            return Base64.encodeToString(sg.sign(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String hex(byte[] b, boolean upper) {
        char[] H = (upper ? "0123456789ABCDEF" : "0123456789abcdef").toCharArray();
        char[] o = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            o[i * 2] = H[(b[i] >> 4) & 0xf];
            o[i * 2 + 1] = H[b[i] & 0xf];
        }
        return new String(o);
    }
}
