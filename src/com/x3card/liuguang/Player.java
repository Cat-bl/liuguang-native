package com.x3card.liuguang;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 宿主没有的播放操作：发给车机自带「多媒体」（com.byd.mediacenter）的导出服务入口，零权限——不用 root、不用通知使用权，
 * 方向盘按键、通知栏走的也是这条。入口认一个整数 MediaAction + 一个 MediaParams 参数包：
 * - 播放 / 暂停 / 上一首 / 下一首 MediaAction=1 / 2 / 3 / 4，不带参数；按「多媒体」当前的音源分发（蓝牙音乐经蓝牙发给手机）。
 *   宿主的 media.* 转播放控制时会挑错会话：「多媒体」里同时有几个播放会话（比如在放蓝牙、刚用过的喜马拉雅也留着一个），实车上暂停发给了喜马拉雅。
 * - 跳进度 MediaAction=15，参数 position（毫秒）；车机把当前在放的那个音源跳过去。
 * - 收藏 / 取消收藏当前这首 MediaAction=10 / 11，不带参数。收没收藏从车机的媒体控制器读（BydMedia）。
 * - 放播放队列里的第几首 MediaAction=14（playByIndex），参数 media_id = 序号（int，从 0 起，同 BydMedia 歌单的顺序）。实测序号精确（点第 50 首正好放这首）。
 *   本地音乐（本地、U 盘、SD 卡）还要 media_list_type = 4（当前播放队列），不带时它把 media_id 当歌曲 id 在全部歌曲里找，找不到就不放；在线音源不看这个参数。
 *   媒体控制器自己的 changePlayMusic(音源, id) 把歌曲 id 当 media_id 传过来，「多媒体」当序号用，会切错——不用它。
 * 反编译「多媒体」确认：ControlConverter.convertAction 把 15 映射成 seek、10 / 11 映射成 collect / uncollect、14 映射成 playByIndex，
 * AbstractOnlineMessageService 读 params 的 "position"（毫秒）/1000 交给播放器。
 * 实车验证过：发 seek 到 90 秒，进度跟着跳；别的音源 / 不是「多媒体」时这条 intent 没人接，不报错也不生效。
 */
final class Player {
    static final String PKG = "com.byd.mediacenter";
    private static final String CLS = "com.byd.mediacenter.main.MediaService";

    /** 播放 1 / 暂停 2 / 上一首 3 / 下一首 4。这台车上有这个入口返回真（不等回执） */
    static boolean control(Context ctx, int action) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", action);
            return ctx.startService(s) != null;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "播放控制发不出去", e);
            return false;
        }
    }

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

    /** 收藏（on）/ 取消收藏当前在放的这首。发出去返回真 */
    static boolean favorite(Context ctx, boolean on) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", on ? 10 : 11); // collect / uncollect
            s.putExtra("MediaParams", new Bundle());
            ctx.startService(s);
            Log.w(Uiplus.TAG, "收藏 → " + on);
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "收藏失败", e);
            return false;
        }
    }

    /** 放播放队列里第 index 首（序号从 0 起）。发出去返回真 */
    static boolean playIndex(Context ctx, int index) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", 14); // playByIndex
            Bundle p = new Bundle();
            p.putInt("media_id", index);
            p.putInt("media_list_type", 4);
            s.putExtra("MediaParams", p);
            ctx.startService(s);
            Log.w(Uiplus.TAG, "放第 " + index + " 首");
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "点歌失败", e);
            return false;
        }
    }
}
