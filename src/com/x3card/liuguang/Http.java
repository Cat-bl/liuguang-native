package com.x3card.liuguang;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 共用的 HTTP：GET/POST、自定义头、返回原始字节（有的接口响应要先解密，不能当字符串）。
 * 阻塞式，调用方自己放后台线程（MusicApi、SongFinder 都这么用）。
 */
final class Http {
    static final int MAX = 4 * 1024 * 1024;

    private Http() {}

    static final class Resp {
        final int code;
        final byte[] body;
        Resp(int code, byte[] body) { this.code = code; this.body = body; }
        String text() { return new String(body, MCrypto.UTF8); }
    }

    /** 一次请求。method GET/POST；body 为空就是 GET 式（不写出）；headers 可空 */
    static Resp send(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "okhttp/4.12.0");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) try (InputStream i = in) {
                byte[] b = new byte[8192];
                int n;
                while ((n = i.read(b)) > 0 && bo.size() < MAX) bo.write(b, 0, n);
            }
            return new Resp(code, bo.toByteArray());
        } finally {
            c.disconnect();
        }
    }

    static Resp get(String url, Map<String, String> headers) throws Exception { return send("GET", url, headers, null); }

    static Resp post(String url, Map<String, String> headers, byte[] body) throws Exception { return send("POST", url, headers, body); }

    /** 便捷：一串 key,value,key,value… 组成 header map（顺序保留） */
    static Map<String, String> h(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
