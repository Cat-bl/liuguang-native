package com.x3card.liuguang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Handler;
import android.os.Looper;

/**
 * 四家音乐接口的统一入口（框架层，不含业务解析）。
 * 调一次 = 取对应签名器 build 请求 → Http 发 → decode 成文本。解析交给上层（MusicSearch 等）。
 * 加新接口：直接 call(音源, endpoint, 业务参数, 回调)，不用改这里。
 */
final class MusicApi {
    private MusicApi() {}

    private static final Map<Integer, MusicSource> SRC = new LinkedHashMap<>();
    static {
        for (MusicSource s : new MusicSource[]{new KugouSource(), new KuwoSource(), new QQSource(), new NeteaseSource()})
            SRC.put(s.code(), s);
    }

    static MusicSource source(int code) { return SRC.get(code); }

    private static final ExecutorService EX = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lg-music");
        t.setDaemon(true);
        return t;
    });

    /** 同步调用（放后台线程里用）：返回解好的响应文本；失败抛异常 */
    static String callRaw(int code, String endpoint, Map<String, Object> biz) throws Exception {
        if (!MusicNative.ready()) throw new Exception("音乐功能暂不可用");
        MusicSource s = SRC.get(code);
        if (s == null) throw new Exception("没有这个音源：" + code);
        MusicSource.Req r = s.build(endpoint, biz);
        Http.Resp resp = Http.send(r.method, r.url, r.headers, r.body);
        return s.decode(resp);
    }

    interface Cb { void done(String text, String err); }

    /** 异步调用：后台线程发请求，结果回到主线程给 cb */
    static void call(int code, String endpoint, Map<String, Object> biz, Cb cb) {
        EX.execute(() -> {
            String text = null, err = null;
            try { text = callRaw(code, endpoint, biz); }
            catch (Exception e) { err = "请求失败：" + e.getMessage(); }
            final String ft = text, fe = err;
            new Handler(Looper.getMainLooper()).post(() -> cb.done(ft, fe));
        });
    }

    /** 后台线程池（MusicSearch 等复用） */
    static ExecutorService exec() { return EX; }

    /** 便捷：组业务参数（保留顺序，QQ 等顺序敏感的要用） */
    static LinkedHashMap<String, Object> biz(Object... kv) { return Jx.map(kv); }
}
