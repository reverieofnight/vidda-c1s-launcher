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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 最近使用：记录每个应用最后一次打开时间，供底部 Dock 排序。
 *
 * 排序时间 = max(最后打开时间, 首次安装时间)，新装应用会立刻排到前面。
 */
final class RecentApps {

    /** 旧格式（只有顺序、没有时间），仅用于升级迁移 */
    private static final String KEY_RECENT = "recent_components";
    /** 新格式：每行 "组件|最后打开时间(ms)" */
    private static final String KEY_OPEN_TIMES = "open_times";

    private final SharedPreferences prefs;
    private final AppRepository repository;
    /** 组件 → 最后一次打开时间 */
    private final Map<String, Long> openTimes = new HashMap<>();

    RecentApps(Context context, AppRepository repository) {
        this.prefs = context.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE);
        this.repository = repository;
    }

    /**
     * 打开时间持久化格式：每行 "组件|时间戳(ms)"。
     * 旧版本只有顺序（KEY_RECENT），首次升级时按原顺序迁移成合成时间。
     */
    void load() {
        openTimes.clear();
        if (!prefs.contains(KEY_OPEN_TIMES)) {
            migrateOldRecent();
            return;
        }
        String raw = prefs.getString(KEY_OPEN_TIMES, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        for (String line : raw.split("\\n")) {
            int split = line.lastIndexOf('|');
            if (split <= 0 || split >= line.length() - 1) {
                continue;
            }
            try {
                openTimes.put(line.substring(0, split),
                        Long.parseLong(line.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                // 单条坏数据不影响其它记录
            }
        }
    }

    /** 打开应用：记录这次打开的时间，底部栏按时间重排 */
    void remember(AppEntry entry) {
        openTimes.put(entry.key, System.currentTimeMillis());
        save();
    }

    /** 把应用按 Dock 时间倒序排列；时间相同保持原来的字母序 */
    List<AppEntry> sorted(List<AppEntry> apps) {
        List<AppEntry> out = new ArrayList<>(apps);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                long ta = dockTime(a);
                long tb = dockTime(b);
                if (ta != tb) {
                    return Long.compare(tb, ta);
                }
                return 0; // 时间相同保持字母序（Collections.sort 是稳定排序）
            }
        });
        return out;
    }

    private long dockTime(AppEntry entry) {
        Long opened = openTimes.get(entry.key);
        long openTime = opened == null ? 0L : opened;
        return Math.max(openTime, entry.firstInstallTime);
    }

    /** 旧版 recent_components 只有先后顺序、没有时间，按原顺序补成递减时间 */
    private void migrateOldRecent() {
        String raw = prefs.getString(KEY_RECENT, "");
        long now = System.currentTimeMillis();
        int index = 0;
        if (!TextUtils.isEmpty(raw)) {
            for (String key : raw.split("\\n")) {
                if (TextUtils.isEmpty(key) || !repository.contains(key)) {
                    continue;
                }
                openTimes.put(key, now - (++index) * 60000L);
            }
        }
        prefs.edit().remove(KEY_RECENT).apply();
        save();
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : openTimes.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('|').append(e.getValue());
        }
        prefs.edit().putString(KEY_OPEN_TIMES, sb.toString()).apply();
    }
}
