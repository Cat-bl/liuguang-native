package com.x3card.liuguang;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人 / 设备级凭证从「多媒体」(com.byd.mediacenter) 读出来，覆盖 MusicCreds 的默认值。要 root（这车是 Magisk root 的）。
 * 位置（抓包定位的，「多媒体」更新后可能变）：
 *  - 酷狗 userId/token：databases/acc.db 的 User 表（搜歌必需）
 *  - 网易云 accessToken：files/mmkv/com.netease.cloudmusic.preferences（搜歌必需；没登录也有匿名 token，够搜）
 *  - device_id：files/mmkv/app_global 的 key_device_id
 *  - 酷我 appUid/appId：shared_prefs/com.byd.mediacenter_preferences.xml
 * 读不到就保留默认值（在开发那台车上能跑；别的车该项失效）。QQ 的 uid 用 0（搜歌够了）。
 */
final class RootCreds {
    private static final String MC = "/data/data/com.byd.mediacenter";
    private static volatile boolean loaded;

    private RootCreds() {}

    /** 放后台线程调。读「多媒体」凭证覆盖 MusicCreds。返回是否有 root */
    static synchronized boolean load(Context ctx) {
        byte[] id = su("id");
        if (id == null || !new String(id, MCrypto.UTF8).contains("uid=0")) {
            Log.w(Uiplus.TAG, "没有 root，音乐接口只能用默认凭证");
            return false;
        }
        readKugou(ctx);
        readNetease();
        readKuwoXml();
        loaded = true;
        Log.w(Uiplus.TAG, "音乐凭证已从「多媒体」读取");
        return true;
    }

    static boolean loaded() { return loaded; }

    /** 酷狗 userId/token：拷 acc.db(+wal+shm) 到自己缓存，SQLite 查 User 表 */
    private static void readKugou(Context ctx) {
        File dir = ctx.getCacheDir();
        File db = new File(dir, "mc_acc.db");
        try {
            if (!copy(MC + "/databases/acc.db", db)) return;
            copy(MC + "/databases/acc.db-wal", new File(dir, "mc_acc.db-wal"));
            copy(MC + "/databases/acc.db-shm", new File(dir, "mc_acc.db-shm"));
            SQLiteDatabase d = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            try (Cursor c = d.rawQuery("SELECT userId, token FROM User WHERE token IS NOT NULL AND token<>'' LIMIT 1", null)) {
                if (c.moveToFirst()) {
                    String uid = c.getString(0), tok = c.getString(1);
                    if (uid != null && tok != null && !tok.isEmpty()) {
                        MusicCreds.kgUserid = uid;
                        MusicCreds.kgToken = tok;
                    }
                }
            } finally {
                d.close();
            }
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "读酷狗凭证失败：" + e);
        } finally {
            new File(dir, "mc_acc.db").delete();
            new File(dir, "mc_acc.db-wal").delete();
            new File(dir, "mc_acc.db-shm").delete();
        }
    }

    /** 网易云 accessToken：mmkv 里读 key "accessToken" 的字符串值 */
    private static void readNetease() {
        String t = mmkvStr(su("cat " + MC + "/files/mmkv/com.netease.cloudmusic.preferences"), "accessToken");
        if (t != null && !t.isEmpty()) MusicCreds.neAccessToken = t;
    }

    /** 酷我 appUid/appId：shared_prefs 明文 XML */
    private static void readKuwoXml() {
        String s = text(su("cat " + MC + "/shared_prefs/com.byd.mediacenter_preferences.xml"));
        if (s == null) return;
        String au = xml(s, "appconfig@kuwo@appuid");
        if (au != null && !au.isEmpty()) MusicCreds.kwAppUid = au;
        String ai = xml(s, "appId");
        if (ai != null && !ai.isEmpty()) MusicCreds.kwAppId = ai;
    }

    // ---- 工具 ----

    /**
     * 从 MMKV 原始字节里读一个字符串值。MMKV 布局：…[keyLen varint][key][valueLen varint][strLen varint][utf8]…
     * 按"[keyLen][key]"精确定位（避免子串误命中），取最后一次出现（更新会追加，后者最新）。
     */
    private static String mmkvStr(byte[] data, String key) {
        if (data == null) return null;
        byte[] kb = key.getBytes(MCrypto.UTF8);
        byte[] pat = new byte[kb.length + 1];
        pat[0] = (byte) kb.length; // 短 key 的 keyLen 就是 1 字节
        System.arraycopy(kb, 0, pat, 1, kb.length);
        int at = lastIndexOf(data, pat);
        if (at < 0) return null;
        int p = at + pat.length;
        int[] v1 = varint(data, p); if (v1 == null) return null; p = v1[1];   // valueLen（容器长度）
        int[] v2 = varint(data, p); if (v2 == null) return null; int len = v2[0]; p = v2[1]; // strLen
        if (len <= 0 || p + len > data.length) return null;
        return new String(data, p, len, MCrypto.UTF8);
    }

    /** 读一个 varint（最多 5 字节），返回 [值, 下一个位置]；坏了返回 null */
    private static int[] varint(byte[] b, int p) {
        int r = 0, shift = 0;
        while (p < b.length && shift < 35) {
            int x = b[p++] & 0xff;
            r |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return new int[]{r, p};
            shift += 7;
        }
        return null;
    }

    private static int lastIndexOf(byte[] hay, byte[] needle) {
        for (int i = hay.length - needle.length; i >= 0; i--) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }

    private static String xml(String s, String name) {
        Matcher m = Pattern.compile("<string name=\"" + Pattern.quote(name) + "\">(.*?)</string>").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String text(byte[] b) { return b == null ? null : new String(b, MCrypto.UTF8); }

    /** su cat 一个文件写到目标；成功返回 true */
    private static boolean copy(String path, File to) {
        byte[] b = su("cat " + path);
        if (b == null || b.length == 0) return false;
        try (FileOutputStream o = new FileOutputStream(to)) { o.write(b); return true; }
        catch (Exception e) { return false; }
    }

    /** 以 root 跑一条命令，返回 stdout 字节（失败 null） */
    private static byte[] su(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            p.waitFor();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
