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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用列表仓储：扫描可启动应用并维护「标识 → 条目」索引，供抽屉与 Dock 共用。
 * 扫描为纯计算，可在后台线程调用；apply() 需在主线程执行。
 */
final class AppRepository {

    private final LauncherActivity activity;
    private final List<AppEntry> all = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();

    AppRepository(LauncherActivity activity) {
        this.activity = activity;
    }

    List<AppEntry> all() {
        return all;
    }

    AppEntry find(String key) {
        return byKey.get(key);
    }

    boolean contains(String key) {
        return byKey.containsKey(key);
    }

    void apply(List<AppEntry> scanned) {
        all.clear();
        all.addAll(scanned);
        byKey.clear();
        for (AppEntry entry : all) {
            byKey.put(entry.key, entry);
        }
    }

    void load() {
        apply(scan());
    }

    /** 与当前列表的应用集合是否一致（顺序按 label 排序） */
    boolean sameAs(List<AppEntry> scanned) {
        if (scanned.size() != all.size()) {
            return false;
        }
        for (int i = 0; i < scanned.size(); i++) {
            if (!scanned.get(i).key.equals(all.get(i).key)) {
                return false;
            }
        }
        return true;
    }

    /** 扫描可启动应用（纯计算，可在后台线程调用） */
    List<AppEntry> scan() {
        PackageManager pm = activity.getPackageManager();
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            byPackage.put(ri.activityInfo.packageName, ri);
        }
        Intent normal = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(normal, 0)) {
            if (!byPackage.containsKey(ri.activityInfo.packageName)) {
                byPackage.put(ri.activityInfo.packageName, ri);
            }
        }

        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : byPackage.values()) {
            if (activity.getPackageName().equals(ri.activityInfo.packageName)) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            String name = TextUtils.isEmpty(label)
                    ? ri.activityInfo.packageName : label.toString();
            long installTime = 0L;
            try {
                installTime = pm.getPackageInfo(ri.activityInfo.packageName, 0).firstInstallTime;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 取不到就当作最旧，不影响其它应用排序
            }
            out.add(new AppEntry(name,
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm), installTime));
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }
}
