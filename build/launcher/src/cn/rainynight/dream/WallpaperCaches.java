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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 壁纸缓存：4K 转码缓存（静态壁纸编码出来的 mp4）与缩略图磁盘缓存。
 *
 * 只负责缓存文件命名、统计与清理；内存缩略图缓存和界面刷新仍由 LauncherActivity 管。
 */
final class WallpaperCaches {

    private static final String TAG = "DSH_LAUNCHER";

    private static final String FOUR_K_CACHE_DIR = "4k_cache";
    private static final String FOUR_K_CACHE_VERSION = "v6";
    private static final String THUMB_DIR = "thumb_cache";

    private final Context context;

    WallpaperCaches(Context context) {
        this.context = context;
    }

    private File baseDir() {
        File base = context.getExternalFilesDir(null);
        return base != null ? base : context.getFilesDir();
    }

    File fourKCacheDir() {
        return new File(baseDir(), FOUR_K_CACHE_DIR);
    }

    File fourKCacheFile(BackgroundItem item) {
        File dir = fourKCacheDir();
        dir.mkdirs();
        String safe = item.name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safe + "_" + item.stamp + "_"
                + FOUR_K_CACHE_VERSION + ".mp4");
    }

    private static String cachePrefixFor(BackgroundItem item) {
        return item.name.replaceAll("[^a-zA-Z0-9._-]", "_") + "_";
    }

    long fourKCacheSizeFor(BackgroundItem item) {
        File[] files = fourKCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        String prefix = cachePrefixFor(item);
        long total = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix)) {
                total += file.length();
            }
        }
        return total;
    }

    long totalFourKCacheSize() {
        return dirSize(fourKCacheDir());
    }

    int fourKCacheFileCount() {
        File[] files = fourKCacheDir().listFiles();
        return files == null ? 0 : files.length;
    }

    int deleteFourKCacheFor(BackgroundItem item) {
        File[] files = fourKCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        String prefix = cachePrefixFor(item);
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix) && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 后台清理：图片已删除或已变更后遗留的 4K 缓存 */
    void cleanupFourK(final List<BackgroundItem> items) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] files = fourKCacheDir().listFiles();
                if (files == null || files.length == 0) {
                    return;
                }
                Set<String> keep = new HashSet<>();
                for (BackgroundItem item : items) {
                    keep.add(fourKCacheFile(item).getName());
                }
                int deleted = 0;
                for (File file : files) {
                    if (!file.isFile()) {
                        continue;
                    }
                    if (!keep.contains(file.getName())) {
                        if (file.delete()) {
                            deleted++;
                        }
                    }
                }
                Log.i(TAG, "4K cache cleanup: kept=" + keep.size()
                        + " deleted=" + deleted);
            }
        }, "4k-cache-cleanup").start();
    }

    // ---------- 缩略图磁盘缓存 ----------

    File thumbCacheDir() {
        File dir = new File(baseDir(), THUMB_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    String thumbFileName(BackgroundItem item) {
        String safe = item.name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe + "_" + item.stamp + (WallpaperStore.isVideoName(item.name) ? "_v.jpg" : "_i.jpg");
    }

    Bitmap loadThumbFromDisk(BackgroundItem item) {
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            if (!file.exists() || file.length() == 0L) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            Log.w(TAG, "load thumb cache failed: " + item.name, t);
            return null;
        }
    }

    void saveThumbToDisk(BackgroundItem item, Bitmap bitmap) {
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
            out.close();
            if (file.exists()) {
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "save thumb cache failed: " + item.name, t);
        }
    }

    void deleteThumb(BackgroundItem item) {
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            if (file.exists() && file.delete()) {
                Log.i(TAG, "thumb cache deleted: " + item.name);
            }
        } catch (Throwable t) {
            Log.w(TAG, "delete thumb cache failed: " + item.name, t);
        }
    }

    /** 壁纸列表变化后清掉孤儿缩略图（源文件已删的那些） */
    void pruneThumbs(final Set<String> keep) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File[] files = thumbCacheDir().listFiles();
                    int removed = 0;
                    if (files != null) {
                        for (File file : files) {
                            if (!keep.contains(file.getName())) {
                                if (file.delete()) {
                                    removed++;
                                }
                            }
                        }
                    }
                    Log.i(TAG, "thumb cache pruned: kept=" + keep.size()
                            + " removed=" + removed);
                } catch (Throwable t) {
                    Log.w(TAG, "prune thumb cache failed", t);
                }
            }
        }, "thumb-prune").start();
    }

    long thumbCacheSize() {
        return dirSize(thumbCacheDir());
    }

    /** 清空全部缓存：4K 转码缓存 + 缩略图缓存。返回删除的文件数。 */
    int deleteAll() {
        int deleted = deleteFiles(fourKCacheDir());
        deleted += deleteFiles(thumbCacheDir());
        return deleted;
    }

    private static long dirSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    private static int deleteFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
