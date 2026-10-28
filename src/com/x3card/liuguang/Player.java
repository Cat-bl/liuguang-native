package com.x3card.liuguang;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 跳到歌词某一句（宿主没有这个接口）：发给车机自带「多媒体」（com.byd.mediacenter）的导出服务入口，零权限——不用 root、不用通知使用权，
 * 方向盘按键、通知栏走的也是这条。入口认一个整数 MediaAction + 一个 MediaParams 参数包：
 * - 跳进度 MediaAction=15，参数 position（毫秒）；车机把当前在放的那个音源跳过去。
 * 反编译「多媒体」确认：ControlConverter.convertAction 把 15 映射成 seek，AbstractOnlineMessageService 读 params 的 "position"（毫秒）/1000 交给播放器。
 * 实车验证过：发 seek 到 90 秒，进度跟着跳；别的音源 / 不是「多媒体」时这条 intent 没人接，不报错也不生效。
 * 以前这个功能连播放器的媒体会话做（要通知使用权），还带收藏红心；2026-10-28 用户让删掉红心、seek 改成这条零权限的路。
 */
final class Player {
    private static final String PKG = "com.byd.mediacenter";
    private static final String CLS = "com.byd.mediacenter.main.MediaService";

    /** 跳到 ms 毫秒。发出去返回真（不等回执，这个入口不回结果） */
    static boolean seek(Context ctx, long ms) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", 15); // ACTION_SEEK
            Bundle p = new Bundle();
            p.putLong("position", Math.max(0, ms));
            s.putExtra("MediaParams", p);
            ctx.startService(s);
            Log.w(Uiplus.TAG, "跳到 → " + ms);
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "跳歌词失败", e);
            return false;
        }
    }
}
