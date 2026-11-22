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

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 壁纸清单：扫描静态 / 1080p / 视频目录，维护自定义排序。
 *
 * 只负责目录与排序，不持有「当前壁纸」和播放状态。
 */
final class WallpaperCatalog {

    private static final String KEY_BG_ORDER = "background_order";

    private final WallpaperStore store;
    private final BackgroundItem builtin;
    private final SharedPreferences prefs;

    WallpaperCatalog(Context context, WallpaperStore store, BackgroundItem builtin) {
        this.store = store;
        this.builtin = builtin;
        this.prefs = context.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** 4K 原图目录；内置壁纸固定排最后，其余按自定义顺序、再按文件名 */
    List<BackgroundItem> scanStill() {
        List<BackgroundItem> items = new ArrayList<>();
        // 内置壁纸不落盘：先入列表，位图直接从 APK assets 读；排序时固定排最后
        items.add(builtin);
        for (File file : store.listImages(store.staticDir())) {
            // 旧版本把内置图复制进了目录，现在改为直接读 APK，跳过遗留副本避免重名
            if (builtin.name.equals(file.getName())) {
                continue;
            }
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file), false));
        }
        // 有自定义排序时按 order 排，没列出的按文件名排在后面
        final List<String> order = readOrder();
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (!rank.containsKey(order.get(i))) {
                rank.put(order.get(i), i);
            }
        }
        Collections.sort(items, new Comparator<BackgroundItem>() {
            @Override
            public int compare(BackgroundItem a, BackgroundItem b) {
                // 内置壁纸固定排在最后，不受自定义排序影响
                if (a.builtin != b.builtin) {
                    return a.builtin ? 1 : -1;
                }
                int ra = rank.containsKey(a.name) ? rank.get(a.name) : Integer.MAX_VALUE;
                int rb = rank.containsKey(b.name) ? rank.get(b.name) : Integer.MAX_VALUE;
                if (ra != rb) {
                    return ra < rb ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return items;
    }

    /** 1080p 兜底目录；没有图片时用内置壁纸 */
    List<BackgroundItem> scanLegacy() {
        List<BackgroundItem> items = new ArrayList<>();
        for (File file : store.listImages(store.legacyImageDir())) {
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file)));
        }
        if (!items.isEmpty()) {
            return items;
        }
        items.add(builtin);
        return items;
    }

    List<BackgroundItem> scanVideos() {
        List<BackgroundItem> items = new ArrayList<>();
        for (File file : store.listVideos(store.videoDir())) {
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file)));
        }
        return items;
    }

    /** 自定义排序：按行保存文件名，未列出的按名称排在后面 */
    void saveOrder(List<BackgroundItem> items) {
        StringBuilder sb = new StringBuilder();
        for (BackgroundItem item : items) {
            sb.append(item.name).append('\n');
        }
        prefs.edit().putString(KEY_BG_ORDER, sb.toString()).apply();
    }

    private List<String> readOrder() {
        List<String> order = new ArrayList<>();
        String raw = prefs.getString(KEY_BG_ORDER, "");
        if (TextUtils.isEmpty(raw)) {
            return order;
        }
        for (String line : raw.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                order.add(name);
            }
        }
        return order;
    }

    /** 重新扫描后判断列表是否变化（名称、时间戳、来源都一致才算没变） */
    static boolean sameItems(List<BackgroundItem> current, List<BackgroundItem> found) {
        if (found.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < found.size(); i++) {
            BackgroundItem a = current.get(i);
            BackgroundItem b = found.get(i);
            if (!a.name.equals(b.name)
                    || a.stamp != b.stamp
                    || !TextUtils.equals(a.assetName, b.assetName)
                    || !TextUtils.equals(a.filePath, b.filePath)) {
                return false;
            }
        }
        return true;
    }
}
