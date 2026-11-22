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

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * RemoteImportServer.Host 的实现：把远程导入的 HTTP 请求接到桌面的壁纸状态上。
 * 文件读写、格式校验、去重在这里完成；涉及界面与播放器的操作统一委托给 LauncherActivity。
 */
final class RemoteImportHostBridge implements RemoteImportServer.Host {

    private static final String TAG = "DSH_LAUNCHER";

    private final LauncherActivity activity;

    RemoteImportHostBridge(LauncherActivity activity) {
        this.activity = activity;
    }

    @Override
    public List<String> wallpaperNames() {
        List<String> names = activity.callOnUi(new Callable<List<String>>() {
            @Override
            public List<String> call() {
                List<BackgroundItem> items = activity.remoteImageItems();
                List<String> out = new ArrayList<>(items.size());
                for (BackgroundItem item : items) {
                    out.add(item.name);
                }
                return out;
            }
        }, 3000);
        return names == null ? new ArrayList<String>() : names;
    }

    @Override
    public String currentWallpaper() {
        // 只有图片模式下才有「当前图片」；放视频时图片栏没有当前项
        return activity.remoteCurrentImageName();
    }

    @Override
    public File wallpaperDir() {
        return activity.remoteImageDir();
    }

    @Override
    public synchronized RemoteImportServer.SaveResult saveWallpaper(
            String fileName, InputStream in, long length) throws IOException {
        File dir = wallpaperDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File temp = new File(dir, ".upload-" + System.currentTimeMillis() + ".tmp");
        long written = 0;
        OutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
            }
            out.flush();
        } finally {
            if (out != null) {
                out.close();
            }
        }
        if (written != length) {
            temp.delete();
            Log.w(TAG, "remote upload incomplete: " + written + "/" + length);
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            temp.delete();
            Log.w(TAG, "remote upload not an image: " + fileName);
            return null;
        }
        File dup = findSameContentFile(dir, temp);
        if (dup != null) {
            temp.delete();
            Log.i(TAG, "remote upload duplicated, ignored: " + dup.getName());
            return new RemoteImportServer.SaveResult(dup.getName(), true);
        }
        String base = fileName;
        String ext = ".jpg";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        File dest = new File(dir, base + ext);
        int suffix = 1;
        while (dest.exists()) {
            suffix++;
            dest = new File(dir, base + "_" + suffix + ext);
        }
        if (!temp.renameTo(dest)) {
            temp.delete();
            return null;
        }
        final String saved = dest.getName();
        Log.i(TAG, "remote upload stored: " + saved
                + " (" + bounds.outWidth + "x" + bounds.outHeight + ")");
        activity.onRemoteImageSaved(saved);
        return new RemoteImportServer.SaveResult(saved, false);
    }

    @Override
    public boolean applyWallpaper(final String name) {
        Boolean ok = activity.callOnUi(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (BackgroundItem item : activity.remoteImageItems()) {
                    if (item.name.equals(name)) {
                        activity.applyWallpaperByName(name);
                        return true;
                    }
                }
                return false;
            }
        }, 5000);
        return ok != null && ok;
    }

    @Override
    public boolean deleteWallpaper(final String name) {
        Boolean ok = activity.callOnUi(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (BackgroundItem item : activity.remoteImageItems()) {
                    if (item.name.equals(name)) {
                        activity.deleteWallpaper(item);
                        // 内置壁纸没有实体文件，删除会被拒绝，这里直接返回 false
                        return item.filePath == null || !new File(item.filePath).exists();
                    }
                }
                return false;
            }
        }, 5000);
        return ok != null && ok;
    }

    @Override
    public List<String> videoNames() {
        List<String> names = activity.callOnUi(new Callable<List<String>>() {
            @Override
            public List<String> call() {
                List<BackgroundItem> items = activity.remoteVideoItems();
                List<String> out = new ArrayList<>(items.size());
                for (BackgroundItem item : items) {
                    out.add(item.name);
                }
                return out;
            }
        }, 3000);
        return names == null ? new ArrayList<String>() : names;
    }

    @Override
    public String currentVideo() {
        // 只有动态壁纸模式下才有「当前视频」；图片模式时视频栏没有当前项
        return activity.remoteCurrentVideoName();
    }

    @Override
    public File videoDir() {
        return activity.remoteVideoDir();
    }

    @Override
    public synchronized RemoteImportServer.SaveResult saveVideo(
            String fileName, InputStream in, long length) throws IOException {
        if (!WallpaperStore.isVideoName(fileName) || length <= 0) {
            return null;
        }
        File dir = videoDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String ext = ".mp4";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            ext = fileName.substring(dot).toLowerCase(Locale.US);
        }
        File temp = new File(dir, ".upload-" + System.currentTimeMillis() + ext);
        long written = 0;
        OutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                written += read;
            }
            out.flush();
        } finally {
            if (out != null) {
                out.close();
            }
        }
        if (written != length) {
            temp.delete();
            Log.w(TAG, "remote video incomplete: " + written + "/" + length);
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(temp.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (TextUtils.isEmpty(duration) || "0".equals(duration)) {
                temp.delete();
                Log.w(TAG, "remote video not playable: " + fileName);
                return null;
            }
        } catch (Throwable t) {
            temp.delete();
            Log.w(TAG, "remote video rejected: " + fileName, t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
        File dup = findSameContentFile(dir, temp);
        if (dup != null) {
            temp.delete();
            Log.i(TAG, "remote video duplicated, ignored: " + dup.getName());
            return new RemoteImportServer.SaveResult(dup.getName(), true);
        }
        String base = fileName;
        if (dot > 0) {
            base = fileName.substring(0, dot);
        }
        File dest = new File(dir, base + ext);
        int suffix = 1;
        while (dest.exists()) {
            suffix++;
            dest = new File(dir, base + "_" + suffix + ext);
        }
        if (!temp.renameTo(dest)) {
            temp.delete();
            return null;
        }
        final String saved = dest.getName();
        Log.i(TAG, "remote video stored: " + saved + " (" + length + " bytes)");
        activity.onRemoteVideoSaved(saved);
        return new RemoteImportServer.SaveResult(saved, false);
    }

    @Override
    public boolean applyVideo(final String name) {
        Boolean ok = activity.callOnUi(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (BackgroundItem item : activity.remoteVideoItems()) {
                    if (item.name.equals(name)) {
                        activity.applyVideoByName(name);
                        return true;
                    }
                }
                return false;
            }
        }, 8000);
        return ok != null && ok;
    }

    @Override
    public boolean deleteVideo(final String name) {
        Boolean ok = activity.callOnUi(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                for (BackgroundItem item : activity.remoteVideoItems()) {
                    if (item.name.equals(name)) {
                        activity.deleteVideoWallpaper(item);
                        return !new File(item.filePath).exists();
                    }
                }
                return false;
            }
        }, 5000);
        return ok != null && ok;
    }

    @Override
    public void onRemoteEvent(String message) {
        activity.postRemoteStatus(message);
    }

    @Override
    public void beginTransfer() {
        activity.beginRemoteTransfer();
    }

    @Override
    public void endTransfer() {
        activity.endRemoteTransfer();
    }

    /** 在目录里找与候选文件内容完全相同的文件（先比长度，再逐字节比），没有返回 null */
    private static File findSameContentFile(File dir, File candidate) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        long length = candidate.length();
        for (File f : files) {
            if (!f.isFile() || f.length() != length) {
                continue;
            }
            if (f.getName().startsWith(".upload-")) {
                continue;
            }
            if (sameBytes(candidate, f)) {
                return f;
            }
        }
        return null;
    }

    private static boolean sameBytes(File a, File b) {
        InputStream inA = null;
        InputStream inB = null;
        try {
            inA = new BufferedInputStream(new FileInputStream(a), 64 * 1024);
            inB = new BufferedInputStream(new FileInputStream(b), 64 * 1024);
            byte[] bufA = new byte[64 * 1024];
            byte[] bufB = new byte[64 * 1024];
            while (true) {
                int nA = readFully(inA, bufA);
                int nB = readFully(inB, bufB);
                if (nA != nB) {
                    return false;
                }
                if (nA <= 0) {
                    return true;
                }
                for (int i = 0; i < nA; i++) {
                    if (bufA[i] != bufB[i]) {
                        return false;
                    }
                }
            }
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(inA);
            closeQuietly(inB);
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
