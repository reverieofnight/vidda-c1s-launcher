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
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 壁纸文件的落盘仓储：目录解析、扫描、去重复制、迁移。
 *
 * 只管文件系统，不持有 UI / 播放状态；静态壁纸和动态壁纸共用同一套规则，
 * 避免 App 里再各写一份「列目录 → 排序 → 重名加后缀 → 写临时文件」。
 */
final class WallpaperStore {

    private static final String TAG = "DSH_LAUNCHER";

    /** 1080p 兜底图目录：旧版遗留，4K 目录为空时才扫描 */
    static final String LEGACY_IMAGE_DIR = "backgrounds";
    /** 动态壁纸（视频）目录 */
    static final String VIDEO_DIR = "backgrounds_video";
    /** 静态壁纸（4K 原图）目录 */
    static final String STATIC_DIR = "backgrounds_4k";

    /** 公共壁纸目录：/sdcard/VIDAA桌面壁纸/{静态,动态}，卸载不删除 */
    private static final String PUBLIC_ROOT = "VIDAA桌面壁纸";
    private static final String PUBLIC_STATIC_SUB = "静态";
    private static final String PUBLIC_VIDEO_SUB = "动态";

    private final Context context;

    WallpaperStore(Context context) {
        this.context = context;
    }

    /** 静态壁纸目录：优先公共目录，没权限回落到应用私有目录 */
    File staticDir() {
        File pub = publicSubDir(PUBLIC_STATIC_SUB);
        return pub != null ? pub : privateDir(STATIC_DIR);
    }

    File videoDir() {
        File pub = publicSubDir(PUBLIC_VIDEO_SUB);
        return pub != null ? pub : privateDir(VIDEO_DIR);
    }

    /** 1080p 兜底图目录（应用私有，不参与公共目录迁移） */
    File legacyImageDir() {
        return privateDir(LEGACY_IMAGE_DIR);
    }

    File privateDir(String name) {
        File base = context.getExternalFilesDir(null) != null
                ? context.getExternalFilesDir(null) : context.getFilesDir();
        return new File(base, name);
    }

    boolean canWritePublic() {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /** 公共子目录；没权限或创建失败返回 null */
    File publicSubDir(String sub) {
        if (!canWritePublic()) {
            return null;
        }
        File dir = new File(new File(Environment.getExternalStorageDirectory(), PUBLIC_ROOT), sub);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "create public wallpaper dir failed: " + dir);
            return null;
        }
        return dir;
    }

    /** 目录里的图片文件，按文件名升序；目录不存在时会尝试创建 */
    List<File> listImages(File dir) {
        return list(dir, false);
    }

    /** 目录里的视频文件，按文件名升序；目录不存在时会尝试创建 */
    List<File> listVideos(File dir) {
        return list(dir, true);
    }

    private List<File> list(File dir, boolean video) {
        List<File> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            if (video ? isVideoName(file.getName()) : isImageName(file.getName())) {
                out.add(file);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /** 外部文件的 lastModified ^ length，用来发现同步后的内容变化 */
    static long stampOf(File file) {
        return file.lastModified() ^ file.length();
    }

    /**
     * 把文件复制进目录：重名自动加 _2 / _3，先写 .upload-* 临时文件再 rename，
     * 避免中途失败留下半截文件。失败返回 null，成功返回目标文件。
     */
    File copyInto(File src, File dir) {
        if (src == null || !src.isFile() || dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String name = src.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        File dest = new File(dir, base + ext);
        int suffix = 1;
        while (dest.exists()) {
            suffix++;
            dest = new File(dir, base + "_" + suffix + ext);
        }
        File tmp = new File(dir, ".upload-" + System.nanoTime() + ext);
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(tmp);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "copy wallpaper failed: " + name, t);
            tmp.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return null;
        }
        return dest;
    }

    /** 把应用私有目录里的静态/动态壁纸搬到公共目录，卸载后不丢 */
    void migratePrivateToPublic() {
        if (!canWritePublic()) {
            return;
        }
        int images = migrate(privateDir(STATIC_DIR), publicSubDir(PUBLIC_STATIC_SUB));
        int videos = migrate(privateDir(VIDEO_DIR), publicSubDir(PUBLIC_VIDEO_SUB));
        if (images + videos > 0) {
            Log.i(TAG, "wallpapers migrated to public: images=" + images + " videos=" + videos);
        }
    }

    private int migrate(File from, File to) {
        if (from == null || to == null || from.equals(to) || !from.isDirectory()) {
            return 0;
        }
        File[] files = from.listFiles();
        if (files == null) {
            return 0;
        }
        int moved = 0;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            File dst = new File(to, file.getName());
            if (dst.exists()) {
                file.delete();
                continue;
            }
            if (file.renameTo(dst)) {
                moved++;
                continue;
            }
            // 跨挂载点时退回复制；失败就留着，下次再试
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(file);
                out = new FileOutputStream(dst);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                moved++;
                file.delete();
            } catch (Throwable t) {
                Log.w(TAG, "migrate wallpaper failed: " + file.getName(), t);
                dst.delete();
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        }
        return moved;
    }

    static boolean isImageName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    static boolean isVideoName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".ts");
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
