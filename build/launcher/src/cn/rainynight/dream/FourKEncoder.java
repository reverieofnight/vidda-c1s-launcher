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

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 4K 静态壁纸编码：位图转 I420、把 x264 输出封装成 mp4。
 *
 * 只做纯编码，不接触播放器、缓存目录或 UI。
 */
final class FourKEncoder {

    /** ARGB 位图转 I420（BT.709 limited range，与 4K 视频的显示保持一致） */
    static byte[] bitmapToI420(Bitmap bitmap, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int[] row = new int[width];
        int uOff = width * height;
        int vOff = uOff + (width / 2) * (height / 2);
        int uvRowSize = width / 2;
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            int uvRow = (y / 2) * uvRowSize;
            for (int x = 0; x < width; x++) {
                int c = row[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                int yv = ((47 * r + 157 * g + 16 * b + 128) >> 8) + 16;
                yuv[y * width + x] = (byte) clampByte(yv);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 102 * g - 10 * b + 128) >> 8) + 128;
                    int uvIndex = uvRow + x / 2;
                    yuv[uOff + uvIndex] = (byte) clampByte(u);
                    yuv[vOff + uvIndex] = (byte) clampByte(v);
                }
            }
        }
        return yuv;
    }

    private static int clampByte(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    /** 把 x264 JNI 返回的 SPS/PPS + 关键帧封装成 mp4 */
    static void writeMp4(File file, byte[] data, int width, int height) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        int spsLen = bb.getInt();
        byte[] sps = new byte[spsLen];
        bb.get(sps);
        int ppsLen = bb.getInt();
        byte[] pps = new byte[ppsLen];
        bb.get(pps);
        int frameCount = bb.getInt();
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            int len = bb.getInt();
            byte[] frame = new byte[len];
            bb.get(frame);
            frames.add(frame);
        }

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        // 帧率必须够高：解码器/显示 plane 在切换尺寸后，第一帧可能按旧缩放/旧色彩展示，
        // 只有 1fps 时这张错帧会停留近 1 秒；30fps 下第二帧 33ms 后就覆盖掉它
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setByteBuffer("csd-0", withStartCode(sps));
        format.setByteBuffer("csd-1", withStartCode(pps));

        file.getParentFile().mkdirs();
        if (file.exists()) {
            file.delete();
        }
        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        try {
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < frames.size(); i++) {
                byte[] annexB = toAnnexB(frames.get(i));
                ByteBuffer buf = ByteBuffer.wrap(annexB);
                info.set(0, annexB.length, i * 33_333L,
                        MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(track, buf, info);
            }
        } finally {
            if (started) {
                muxer.stop();
            }
            muxer.release();
        }
    }

    private static ByteBuffer withStartCode(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(nal.length + 4);
        b.put(new byte[]{0, 0, 0, 1});
        b.put(nal);
        b.position(0);
        return b;
    }

    private static byte[] toAnnexB(byte[] avcc) {
        ByteBuffer in = ByteBuffer.wrap(avcc);
        ByteArrayOutputStream out = new ByteArrayOutputStream(avcc.length + 32);
        while (in.remaining() >= 4) {
            int len = in.getInt();
            if (len <= 0 || len > in.remaining()) {
                return avcc;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            byte[] nal = new byte[len];
            in.get(nal);
            out.write(nal, 0, nal.length);
        }
        return out.toByteArray();
    }
}
