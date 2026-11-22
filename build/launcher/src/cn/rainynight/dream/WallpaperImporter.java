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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 壁纸导入流程：选择导入方式、浏览本机 / USB 目录、校验并复制入库。
 *
 * 只负责导入与权限申请；列表刷新和界面状态由 LauncherActivity 处理。
 */
final class WallpaperImporter {

    private static final String TAG = "DSH_LAUNCHER";

    /** 导入壁纸时申请存储读取权限 */
    static final int REQ_READ_STORAGE = 4101;

    private final LauncherActivity activity;
    private final WallpaperStore store;

    WallpaperImporter(LauncherActivity activity, WallpaperStore store) {
        this.activity = activity;
        this.store = store;
    }

    /** 「导入」按钮：先选导入方式，再走对应的流程。 */
    void showChooser() {
        final List<File> usbRoots = usbImportRoots();
        final List<String> labels = new ArrayList<>();
        labels.add("从本机导入");
        final int usbIndex = usbRoots.isEmpty() ? -1 : labels.size();
        if (usbIndex >= 0) {
            labels.add("从 USB 设备导入");
        }
        labels.add("远程导入（手机/电脑网页上传）");
        new AlertDialog.Builder(activity)
                .setTitle("导入壁纸")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            importFromLocal();
                        } else if (usbIndex >= 0 && which == usbIndex) {
                            pickUsbRootAndBrowse(usbRoots);
                        } else {
                            activity.openRemoteImport();
                        }
                    }
                })
                .show();
    }

    /** 处理存储读取权限结果；不是导入相关的 requestCode 返回 false */
    boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_READ_STORAGE) {
            return false;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showImportRoots();
        } else {
            Toast.makeText(activity, "需要存储读取权限才能导入壁纸", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    /** 从本机（内置存储）导入：直接进根目录浏览，不再弹位置选择。 */
    private void importFromLocal() {
        if (activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE);
            return;
        }
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.isDirectory()) {
            showImportRoots();
            return;
        }
        showFileBrowser(root);
    }

    /** 当前接入的 U 盘 / 外接盘（/storage 下非模拟卷、/mnt 下 usb|media）。 */
    private List<File> usbImportRoots() {
        List<File> candidates = new ArrayList<>();
        addImportRoots(candidates, new File("/storage"), false);
        addImportRoots(candidates, new File("/mnt"), true);
        List<File> roots = new ArrayList<>();
        for (File dir : candidates) {
            if (isRealMountPoint(dir)) {
                roots.add(dir);
            }
        }
        Log.i(TAG, "usb roots: " + roots + " (candidates=" + candidates + ")");
        return roots;
    }

    /** 是否出现在 /proc/mounts 里——厂商会预建空的 /mnt/usb 之类目录，不能只看目录存在。 */
    private boolean isRealMountPoint(File dir) {
        final String path = dir.getAbsolutePath();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }
                String mountPoint = parts[1].replace("\\040", " ");
                if (mountPoint.equals(path) || mountPoint.equals(path + "/")) {
                    return true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "read /proc/mounts failed: " + e);
            return isStorageVolumeRemovable(dir);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** /proc/mounts 读不到时的兜底：问 StorageManager 有没有可移动卷挂在 /storage/<uuid>。 */
    private boolean isStorageVolumeRemovable(File dir) {
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) activity.getSystemService(
                            Context.STORAGE_SERVICE);
            if (sm == null) {
                return false;
            }
            for (android.os.storage.StorageVolume volume : sm.getStorageVolumes()) {
                if (!volume.isRemovable()) {
                    continue;
                }
                String uuid = volume.getUuid();
                if (uuid != null && dir.getAbsolutePath().endsWith("/" + uuid)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void pickUsbRootAndBrowse(final List<File> roots) {
        if (activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE);
            return;
        }
        if (roots.isEmpty()) {
            Toast.makeText(activity, "没有检测到 USB 设备", Toast.LENGTH_LONG).show();
            return;
        }
        if (roots.size() == 1) {
            showFileBrowser(roots.get(0));
            return;
        }
        final String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            labels[i] = roots.get(i).getAbsolutePath();
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择 USB 设备")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showFileBrowser(roots.get(which));
                    }
                })
                .show();
    }

    private void showImportRoots() {
        final List<File> roots = new ArrayList<>();
        File sd = Environment.getExternalStorageDirectory();
        if (sd != null && sd.isDirectory()) {
            roots.add(sd);
        }
        addImportRoots(roots, new File("/storage"), false);
        addImportRoots(roots, new File("/mnt"), true);
        if (roots.isEmpty()) {
            Toast.makeText(activity, "没有找到可访问的存储位置", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            String prefix = root.equals(sd) ? "内置存储" : "U 盘 / 外部存储";
            labels[i] = prefix + "  " + root.getAbsolutePath();
        }
        new AlertDialog.Builder(activity)
                .setTitle("从哪个位置导入")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showFileBrowser(roots.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 只认真实的可移动卷名：usb / usb_x / media-uuid；排除内部卷 emulated、self、media_rw。 */
    private static boolean isUsbLikeVolume(String name) {
        if ("emulated".equals(name) || "self".equals(name) || "media_rw".equals(name)) {
            return false;
        }
        return name.startsWith("usb") || name.startsWith("media-") || name.startsWith("media_")
                || (name.startsWith("media") && name.indexOf('-') > 0);
    }

    private void addImportRoots(List<File> roots, File parent, boolean usbOnly) {
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        List<File> dirs = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory() || !child.canRead()) {
                continue;
            }
            String name = child.getName().toLowerCase(Locale.US);
            if ("emulated".equals(name) || "self".equals(name)) {
                continue;
            }
            if (usbOnly && !isUsbLikeVolume(name)) {
                continue;
            }
            dirs.add(child);
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File dir : dirs) {
            boolean duplicated = false;
            for (File existing : roots) {
                if (existing.getAbsolutePath().equals(dir.getAbsolutePath())) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                roots.add(dir);
            }
        }
    }

    private void showFileBrowser(final File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            Toast.makeText(activity, "无法读取该目录", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean wantVideo = activity.managerWantsVideo();
        File[] children = dir.listFiles();
        List<File> dirs = new ArrayList<>();
        final List<File> images = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    dirs.add(child);
                } else if (child.isFile()) {
                    boolean match = wantVideo
                            ? WallpaperStore.isVideoName(child.getName())
                            : WallpaperStore.isImageName(child.getName());
                    if (match) {
                        images.add(child);
                    }
                }
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(images, byName);

        final List<String> labels = new ArrayList<>();
        final List<File> targets = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            labels.add("⬆ 上一级");
            targets.add(parent);
        }
        if (!images.isEmpty()) {
            labels.add(wantVideo
                    ? "导入本目录全部视频（" + images.size() + " 个）"
                    : "导入本目录全部图片（" + images.size() + " 张）");
            targets.add(null);
        }
        for (File child : dirs) {
            labels.add("📁 " + child.getName());
            targets.add(child);
        }
        for (File image : images) {
            labels.add((wantVideo ? "🎬 " : "🖼 ") + image.getName());
            targets.add(image);
        }
        if (labels.isEmpty()) {
            labels.add("（空目录）");
            targets.add(null);
        }

        new AlertDialog.Builder(activity)
                .setTitle(dir.getAbsolutePath())
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File target = targets.get(which);
                        if (target == null) {
                            if (labels.get(which).startsWith("导入本目录")) {
                                if (wantVideo) {
                                    importVideos(images);
                                } else {
                                    importWallpapers(images);
                                }
                            }
                        } else if (target.isDirectory()) {
                            showFileBrowser(target);
                        } else {
                            List<File> one = new ArrayList<>();
                            one.add(target);
                            if (wantVideo) {
                                importVideos(one);
                            } else {
                                importWallpapers(one);
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importWallpapers(final List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Toast.makeText(activity, "正在导入 " + files.size() + " 张…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                String lastName = null;
                for (File file : files) {
                    String name = copyWallpaperIntoDevice(file);
                    if (name != null) {
                        ok++;
                        lastName = name;
                    }
                }
                final int okCount = ok;
                final String last = lastName;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (okCount <= 0) {
                            Toast.makeText(activity,
                                    "导入失败：不是有效图片或无法读取", Toast.LENGTH_LONG).show();
                            return;
                        }
                        activity.onWallpapersImported(last);
                        Toast.makeText(activity,
                                "已导入 " + okCount + " 张", Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "wallpapers imported: " + okCount);
                    }
                });
            }
        }, "wallpaper-import").start();
    }

    private void importVideos(final List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Toast.makeText(activity, "正在导入 " + files.size() + " 个视频…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (File file : files) {
                    if (copyVideoIntoDevice(file) != null) {
                        ok++;
                    }
                }
                final int okCount = ok;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.onVideosImported();
                        Toast.makeText(activity,
                                okCount > 0 ? ("已导入 " + okCount + " 个视频，点一下设为当前")
                                        : "导入失败：不是可播放的视频",
                                Toast.LENGTH_LONG).show();
                        Log.i(TAG, "videos imported: " + okCount);
                    }
                });
            }
        }, "video-import").start();
    }

    /** 校验并复制视频到 files/backgrounds_video，返回新文件名；失败返回 null */
    private String copyVideoIntoDevice(File src) {
        if (src == null || !src.isFile() || !WallpaperStore.isVideoName(src.getName())) {
            return null;
        }
        if (src.length() <= 0) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(src.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (TextUtils.isEmpty(duration) || "0".equals(duration)) {
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "reject video (cannot read): " + src.getName(), t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
        File dest = store.copyInto(src, store.videoDir());
        if (dest == null) {
            Log.w(TAG, "copy video failed: " + src.getName());
            return null;
        }
        return dest.getName();
    }

    /** 校验并复制到 files/backgrounds_4k，返回新文件名；失败返回 null */
    private String copyWallpaperIntoDevice(File src) {
        if (src == null || !src.isFile() || !WallpaperStore.isImageName(src.getName())) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        File dest = store.copyInto(src, store.staticDir());
        if (dest == null) {
            Log.w(TAG, "import wallpaper failed: " + src.getAbsolutePath());
            return null;
        }
        Log.i(TAG, "wallpaper imported: " + src.getAbsolutePath() + " -> " + dest.getName());
        return dest.getName();
    }
}
