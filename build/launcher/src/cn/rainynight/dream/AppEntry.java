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

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** 可启动应用条目：名称、组件、图标与首次安装时间 */
final class AppEntry {
    final String label;
    final ComponentName component;
    final Drawable icon;
    final String key;
    /** 首次安装时间：与最后打开时间一起决定底部栏的先后 */
    final long firstInstallTime;

    AppEntry(String label, ComponentName component, Drawable icon, long firstInstallTime) {
        this.label = label;
        this.component = component;
        this.icon = icon;
        this.key = component.flattenToString();
        this.firstInstallTime = firstInstallTime;
    }
}
