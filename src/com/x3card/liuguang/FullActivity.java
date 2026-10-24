package com.x3card.liuguang;

/**
 * 全屏页：同一套界面，在主屏上单独一个任务打开（从多窗口里点全屏键开的）；右上角的键是"恢复小屏"，点了关掉这一页。
 * 设置"全屏时收起状态栏"开着就盖住顶端状态栏
 */
public class FullActivity extends MainActivity {
    @Override
    boolean isFull() { return true; }
}
