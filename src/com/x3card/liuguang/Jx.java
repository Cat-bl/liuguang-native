package com.x3card.liuguang;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构建请求用的小工具：有序地拼 JSON、拼 query，字符串按规则转义。
 * 为什么不用 org.json 拼：签名要求"签的串 = 发的串"，这里保证键按插入顺序、输出稳定（跨 JVM/安卓一致），
 * 关键词带引号 / 反斜杠也不会把签名拼坏。响应解析才用 org.json（在安卓上）。
 */
final class Jx {
    private Jx() {}

    /** 原样嵌入的 JSON 片段（如固定的 device_info 子对象），不再加引号 */
    static final class Raw {
        final String s;
        Raw(String s) { this.s = s; }
    }

    /** 按插入顺序拼成 {"k":v,...}；值是 String 加引号转义，Number/Boolean/Raw 原样，null 成 "" */
    static String json(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(esc(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) b.append("\"\"");
            else if (v instanceof Number || v instanceof Boolean) b.append(v.toString());
            else if (v instanceof Raw) b.append(((Raw) v).s);
            else b.append('"').append(esc(v.toString())).append('"');
        }
        return b.append('}').toString();
    }

    /** JSON 字符串转义（和标准一致：" \ 控制字符） */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /** 按插入顺序拼 query（值 url 编码）：k=v&k=v */
    static String query(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(e.getKey()).append('=').append(enc(str(e.getValue())));
        }
        return b.toString();
    }

    /** 按插入顺序拼签名用的原始串（不编码）：k=v&k=v */
    static String raw(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(e.getKey()).append('=').append(str(e.getValue()));
        }
        return b.toString();
    }

    static String str(Object v) { return v == null ? "" : v.toString(); }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return s; }
    }

    static LinkedHashMap<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
