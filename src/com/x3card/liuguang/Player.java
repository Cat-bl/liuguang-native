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
 * - 播放模式 MediaAction=5，参数 pattern：0 列表循环 / 1 随机 / 2 单曲循环（ControlConverter.convertPattern）。
 * - 插放一首 QQ 音乐的歌（insertSong）：参数包里带 action=insert_play 就走自定义动作（BaseMediaService.handleMediaAction → ControlConverter.convertCustomAction），
 *   play_type=song、songId=歌曲 id（数字的或者 mid 都认）、is_background=true（后台放、不开界面）；插进当前播放队列的下一首马上放，同「多媒体」搜索页里点一首。只有 QQ 音乐实现了。
 * 反编译「多媒体」确认：ControlConverter.convertAction 把 15 映射成 seek、10 / 11 映射成 collect / uncollect、14 映射成 playByIndex，
 * AbstractOnlineMessageService 读 params 的 "position"（毫秒）/1000 交给播放器。
 * 实车验证过：发 seek 到 90 秒，进度跟着跳；别的音源 / 不是「多媒体」时这条 intent 没人接，不报错也不生效。
 *
 * 换一批歌、搜曲库走「多媒体」给语音助手留的广播入口（voice）：VoiceControllerReceiver 导出、不要权限，
 * 认方法名 + JSON 参数（带 mediaType 1001 = 音乐；searchMusic 不带，它把认不出的参数都拼进搜索关键词），交给当前的在线音乐音源（QQ、网易云、酷狗、酷我）；当前不是在线音乐时交给上次用过的那个。
 * 结果只回给语音助手，这里拿不到：做成了媒体控制器会推新的播放队列（BydMedia）。
 * 带界面的方法（playMediaSource、playRandom）会把「多媒体」开到主屏前台，不用；要账号的（playFavorite、playMySongList）在没登录时弹出它的登录页。
 * 「多媒体」在不在前台问它的数据接口 MediaProvider（导出、不要权限，foreground）
 */
final class Player {
    static final String PKG = "com.byd.mediacenter";
    private static final String CLS = "com.byd.mediacenter.main.MediaService";
    private static final String VOICE = "com.byd.mediacenter.controller.voice.VoiceControllerReceiver";

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
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "点歌失败", e);
            return false;
        }
    }

    /** 播放模式：0 列表循环 / 1 随机 / 2 单曲循环。发出去返回真 */
    static boolean playMode(Context ctx, int pattern) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", 5); // setPlayPattern
            Bundle p = new Bundle();
            p.putInt("pattern", pattern);
            s.putExtra("MediaParams", p);
            ctx.startService(s);
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "播放模式切不了", e);
            return false;
        }
    }

    /** 插放一首 QQ 音乐的歌（songId：歌曲 id）。发出去返回真 */
    static boolean insertSong(Context ctx, String songId) {
        try {
            Intent s = new Intent("byd.intent.action.START_MEDIA");
            s.setClassName(PKG, CLS);
            s.putExtra("MediaAction", 1);
            Bundle p = new Bundle();
            p.putString("action", "insert_play");
            p.putString("play_type", "song");
            p.putString("songId", songId);
            p.putBoolean("is_background", true);
            s.putExtra("MediaParams", p);
            ctx.startService(s);
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "插放失败", e);
            return false;
        }
    }

    /** 发给语音入口：method + JSON 参数（searchMusic 以外自动带上 mediaType 1001：searchMusic 会把认不出的参数拼进搜索关键词，带了就成了"流行 1001"）。发出去返回真 */
    static boolean voice(Context ctx, String method, org.json.JSONObject params) {
        try {
            org.json.JSONObject j = params == null ? new org.json.JSONObject() : params;
            if (!"searchMusic".equals(method)) j.put("mediaType", 1001);
            Intent b = new Intent("com.byd.action.AUTOVOICE_COMMON_OPERATION");
            b.setClassName(PKG, VOICE);
            b.putExtra("EXTRA_COMMON_OPERATION_METHOD", method);
            b.putExtra("EXTRA_COMMON_OPERATION_PARAM", j.toString());
            ctx.sendBroadcast(b);
            return true;
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "曲库命令发不出去", e);
            return false;
        }
    }

    /** 「多媒体」现在在不在前台：1 在、0 不在、-1 问不到。跨进程，别在主线程调 */
    static int foreground(Context ctx) {
        try {
            Bundle b = ctx.getContentResolver().call(android.net.Uri.parse("content://com.byd.mediacenter.mediaprovider/info"), "method_current_is_foreground", null, null);
            if (b == null || !"result_ok".equals(b.getString("args_result"))) return -1;
            return b.getBoolean("args_current_is_foreground") ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
