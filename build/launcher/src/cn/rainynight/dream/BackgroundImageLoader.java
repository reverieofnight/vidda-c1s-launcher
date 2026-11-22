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
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 壁纸位图解码：原图读取、4K 居中裁切、管理页缩略图、视频取帧。
 *
 * 只负责解码与单张原图缓存，不持有 UI、壁纸列表或播放状态。
 */
final class BackgroundImageLoader {

    private static final String TAG = "DSH_LAUNCHER";

    private final Context context;
    /** 最近解码过的壁纸位图：同一张图在启动/切图时只解码一次 */
    private Bitmap lastLoadedBitmap;
    private String lastLoadedKey;

    BackgroundImageLoader(Context context) {
        this.context = context;
    }

    /** 读图片尺寸；文件壁纸和 APK 内置壁纸都支持，失败返回 null */
    int[] readImageBounds(BackgroundItem item) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (item.assetName != null) {
            InputStream in = null;
            try {
                in = context.getAssets().open(assetPath(item.assetName));
                BitmapFactory.decodeStream(in, null, bounds);
            } catch (Throwable t) {
                Log.w(TAG, "read asset bounds failed: " + item.assetName, t);
                return null;
            } finally {
                closeQuietly(in);
            }
        } else {
            BitmapFactory.decodeFile(item.filePath, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        return new int[]{bounds.outWidth, bounds.outHeight};
    }

    Bitmap decodeImage(BackgroundItem item, BitmapFactory.Options options) {
        if (item.assetName != null) {
            InputStream in = null;
            try {
                in = context.getAssets().open(assetPath(item.assetName));
                return BitmapFactory.decodeStream(in, null, options);
            } catch (Throwable t) {
                Log.w(TAG, "decode asset failed: " + item.assetName, t);
                return null;
            } finally {
                closeQuietly(in);
            }
        }
        return BitmapFactory.decodeFile(item.filePath, options);
    }

    /** 按目标尺寸解码并居中裁切，用于 4K 静态壁纸编码 */
    Bitmap decode4KBitmap(BackgroundItem item, int targetW, int targetH) {
        int[] bounds = readImageBounds(item);
        if (bounds == null) {
            return null;
        }
        int sample = 1;
        while (bounds[0] / (sample * 2) >= targetW
                && bounds[1] / (sample * 2) >= targetH) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = decodeImage(item, options);
        if (src == null) {
            return null;
        }
        Bitmap dst = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float scale = Math.max((float) targetW / src.getWidth(),
                (float) targetH / src.getHeight());
        int cropW = Math.min(src.getWidth(), Math.round(targetW / scale));
        int cropH = Math.min(src.getHeight(), Math.round(targetH / scale));
        int left = (src.getWidth() - cropW) / 2;
        int top = (src.getHeight() - cropH) / 2;
        Rect srcRect = new Rect(left, top, left + cropW, top + cropH);
        Rect dstRect = new Rect(0, 0, targetW, targetH);
        // UI 和视频统一 overscan；低清目标尺寸同样适用
        canvas.save();
        canvas.scale(LauncherActivity.WALLPAPER_OVERSCAN, LauncherActivity.WALLPAPER_OVERSCAN,
                targetW / 2f, targetH / 2f);
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        canvas.restore();
        src.recycle();
        return dst;
    }

    /** 管理页网格缩略图：图片按目标尺寸采样，视频取第 1 秒附近的一帧 */
    Bitmap decodeThumbnail(BackgroundItem item, int targetW, int targetH) {
        if (WallpaperStore.isVideoName(item.name)) {
            return item.filePath == null ? null : decodeVideoThumbnail(item.filePath, targetW);
        }
        try {
            int[] bounds = readImageBounds(item);
            if (bounds == null) {
                return null;
            }
            int sample = 1;
            while (bounds[0] / (sample * 2) >= targetW
                    && bounds[1] / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return decodeImage(item, options);
        } catch (Throwable t) {
            Log.w(TAG, "decode thumbnail failed: " + item.name, t);
            return null;
        }
    }

    private Bitmap decodeVideoThumbnail(String path, int targetW) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap frame = null;
            try {
                frame = retriever.getScaledFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW * 2, targetW);
            } catch (Throwable ignored) {
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                // 4K H.264 用 MediaMetadataRetriever 取不到帧（返回 null），
                // 退回用 MediaCodec 硬解一帧。
                frame = decodeVideoFrameWithCodec(path, targetW);
            }
            if (frame == null) {
                return null;
            }
            if (frame.getWidth() <= targetW * 2) {
                return frame;
            }
            int height = Math.max(1, Math.round(frame.getHeight() * (targetW * 2f)
                    / frame.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(frame, targetW * 2, height, true);
            if (scaled != frame) {
                frame.recycle();
            }
            return scaled;
        } catch (Throwable t) {
            Log.w(TAG, "decode video thumbnail failed: " + path, t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** UI 层当前壁纸：assets 或文件，文件走单张缓存 */
    Bitmap loadBackground(BackgroundItem item) {
        if (item.assetName != null) {
            return loadAsset(item.assetName);
        }
        return loadFileCached(item.filePath);
    }

    private Bitmap loadAsset(String name) {
        String path = assetPath(name);
        InputStream input = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            input = context.getAssets().open(path);
            BitmapFactory.decodeStream(input, null, bounds);
            input.close();
            input = null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            input = context.getAssets().open(path);
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Throwable t) {
            Log.w(TAG, "load background asset failed: " + name, t);
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    private Bitmap loadFile(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            Log.w(TAG, "load background file failed: " + path, t);
            return null;
        }
    }

    /**
     * 带单张缓存的解码：4K 预览和 1080p UI 层用的是同一个原图文件，
     * 冷启动/换图时不必解码两遍（一张 4K 图解码约 150-400ms）。
     */
    private Bitmap loadFileCached(String path) {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        String key = path + "|" + file.lastModified() + "|" + file.length();
        if (key.equals(lastLoadedKey) && lastLoadedBitmap != null
                && !lastLoadedBitmap.isRecycled()) {
            return lastLoadedBitmap;
        }
        Bitmap bitmap = loadFile(path);
        if (bitmap != null) {
            lastLoadedBitmap = bitmap;
            lastLoadedKey = key;
        }
        return bitmap;
    }

    /** assets 内的路径：带 `/` 的按原样用（内置壁纸在 builtin/ 下），否则当作 backgrounds/ */
    private static String assetPath(String name) {
        return name.indexOf('/') >= 0 ? name : WallpaperStore.LEGACY_IMAGE_DIR + "/" + name;
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        if (width > 0 && height > 0) {
            while (width / (sample * 2) >= 1920 && height / (sample * 2) >= 1080) {
                sample *= 2;
            }
        }
        return sample;
    }

    static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 用 MediaCodec 解出视频第 1 秒附近的一帧；MediaMetadataRetriever 拿不到 4K 帧时兜底 */
    static Bitmap decodeVideoFrameWithCodec(String path, int targetW) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(track);
            extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 10000L;
            boolean inputDone = false;
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Bitmap frame = null;
                    if (info.size > 0) {
                        Image image = codec.getOutputImage(outIndex);
                        if (image != null) {
                            frame = yuvImageToBitmap(image, targetW);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if (frame != null) {
                        Log.i(TAG, "video thumb via codec: " + new File(path).getName()
                                + " " + frame.getWidth() + "x" + frame.getHeight());
                        return frame;
                    }
                    if (eos) {
                        break;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "codec thumb failed: " + path, t);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** YUV_420_888 的 Image 转 Bitmap（走 NV21 + JPEG，省掉手写 YUV→RGB） */
    private static Bitmap yuvImageToBitmap(Image image, int targetW) {
        int width = image.getWidth() & ~1;
        int height = image.getHeight() & ~1;
        if (width <= 0 || height <= 0) {
            return null;
        }
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv21, row * width, width);
        }
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int offset = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                nv21[offset + row * width + col * 2] =
                        vBuffer.get(row * vRowStride + col * vPixelStride);
                nv21[offset + row * width + col * 2 + 1] =
                        uBuffer.get(row * uRowStride + col * uPixelStride);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), 85, jpeg);
        byte[] data = jpeg.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        int sample = 1;
        while (sample < 16 && width / (sample * 2) >= targetW * 2) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
}
