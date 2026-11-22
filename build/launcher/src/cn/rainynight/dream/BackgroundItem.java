/*
 * Dream 桌面（cn.rainynight.dream）
 * Copyright (C) 2026 Dream 桌面项目贡献者
 *
 * 本程序是自由软件：你可以依据 GNU 通用公共许可证第 2 版
 * 或（按你的选择）任何更新版本重新发布和/或修改它。
 *
 * 本程序按“无担保”发布；详见仓库根目录 LICENSE。
 */
package cn.rainynight.dream;

/** 一张背景图：要么来自 APK assets，要么来自设备外部目录 */
final class BackgroundItem {

    final String name;
    final String assetName;
    final String filePath;
    /** 外部文件的 lastModified ^ length，用来发现同步后的内容变化 */
    final long stamp;
    /** 随 APK 分发的内置壁纸：不可删除，管理页单独显示标识 */
    final boolean builtin;

    BackgroundItem(String name, String assetName, String filePath, long stamp) {
        this(name, assetName, filePath, stamp, false);
    }

    BackgroundItem(String name, String assetName, String filePath, long stamp, boolean builtin) {
        this.name = name;
        this.assetName = assetName;
        this.filePath = filePath;
        this.stamp = stamp;
        this.builtin = builtin;
    }
}
