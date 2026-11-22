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
import android.media.MediaPlayer;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * 静态 4K 壁纸播放器：负责设备端 x264 编码、缓存命中、MediaPlayer 播放、
 * 首帧就绪状态和切图防抖。是否进入 4K 模式、UI 预览图与启动黑幕仍由 LauncherActivity 控制。
 */
final class FourKStaticPlayer {

    private static final String TAG = "DSH_4K";

    private final LauncherActivity activity;

    /** 是否处于 4K 静态模式（由 LauncherActivity 切换） */
    private boolean mode;
    private boolean encoding;
    private int encodeToken;
    private int playToken;
    private boolean ready;
    private boolean preparing;
    /** 编码期间又切了图：当前编码结束后补排队一次 */
    private boolean encodeQueued;
    /** surface 重建后的重放次数上限，防止播放失败时死循环 */
    private int surfaceReplayCount;
    /** UI 预览图是否已经画好，和视频首帧一起决定何时揭掉封面 */
    private boolean uiAnimationDone;

    /** 延迟启动视频层：UI 立即换图，用户停止操作后再加载，避免频繁重配闪屏 */
    private final Runnable videoStart = new Runnable() {
        @Override
        public void run() {
            if (!mode || activity.managerImageItems().isEmpty()) {
                preparing = false;
                return;
            }
            int index = activity.managerImageIndex();
            if (index < 0 || index >= activity.managerImageItems().size()) {
                preparing = false;
                return;
            }
            BackgroundItem target = activity.managerImageItems().get(index);
            File cache = activity.caches().fourKCacheFile(target);
            if (cache.exists() && cache.length() > 0) {
                play(cache);
            } else if (encoding) {
                // 正在编码（可能是预编码）：结束后再排一次当前这张
                encodeQueued = true;
            } else {
                encode(target);
            }
        }
    };

    FourKStaticPlayer(LauncherActivity activity) {
        this.activity = activity;
    }

    boolean isMode() {
        return mode;
    }

    void setMode(boolean value) {
        mode = value;
    }

    boolean isReady() {
        return ready;
    }

    boolean isPreparing() {
        return preparing;
    }

    boolean isUiAnimationDone() {
        return uiAnimationDone;
    }

    void markUiAnimationDone(boolean done) {
        uiAnimationDone = done;
    }

    void markReady() {
        ready = true;
    }

    /** UI 预览已画好但视频首帧还没到：重置「已在播」标记，准备重新加载 */
    void markPreviewShown() {
        uiAnimationDone = false;
        ready = false;
    }

    /** 新壁纸开始展示：清空 surface 重放计数并标记准备中 */
    void resetForShow() {
        surfaceReplayCount = 0;
        preparing = true;
    }

    /** 主动换图/重新应用：让上一张的晚到回调作废 */
    void invalidate() {
        playToken++;
        ready = false;
        preparing = false;
    }

    /** 放弃当前编码任务（编码线程结束时按 token 自行作废） */
    void resetEncoding() {
        encoding = false;
        encodeToken++;
    }

    /** 清空全部状态，不动视图；用于进入其它壁纸模式前 */
    void reset() {
        encoding = false;
        encodeToken++;
        ready = false;
        preparing = false;
        encodeQueued = false;
        surfaceReplayCount = 0;
        uiAnimationDone = false;
    }

    void scheduleStart() {
        activity.uiHandler().removeCallbacks(videoStart);
        activity.uiHandler().postDelayed(videoStart, LauncherActivity.FOUR_K_SWITCH_DEBOUNCE_MS);
    }

    /** 停止播放并收起视频层与过渡层 */
    void stop() {
        activity.uiHandler().removeCallbacks(videoStart);
        playToken++;
        uiAnimationDone = false;
        ready = false;
        preparing = false;
        surfaceReplayCount = 0;
        if (activity.wallpaperVideoView() != null) {
            activity.wallpaperVideoView().stopPlayback();
            activity.wallpaperVideoView().setVisibility(View.GONE);
        }
        if (activity.transitionLayerView() != null) {
            activity.transitionLayerView().setVisibility(View.GONE);
            activity.transitionOldView().setImageDrawable(null);
            activity.transitionNewView().setImageDrawable(null);
        }
    }

    /** surface 被销毁：旧画面已经不在，等重建后再重放 */
    void onSurfaceDestroyed() {
        ready = false;
        preparing = false;
    }

    /**
     * surface 重建后的 4K 补播。返回 true 表示这次 surface 事件已经由 4K 逻辑处理。
     */
    boolean handleSurfaceRecreated() {
        if (!mode) {
            return false;
        }
        if (!preparing && surfaceReplayCount < 2) {
            // 加延迟和次数上限，避免播放失败时 surface 重建/报错互相触发死循环。
            activity.setVideoSurfaceLost(false);
            surfaceReplayCount++;
            final File cache = activity.current4KCacheOrNull();
            if (cache != null) {
                Log.i(TAG, "4K surface recreated, replay #" + surfaceReplayCount
                        + " " + cache.getName());
                activity.uiHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mode && !activity.isVideoWallpaperMode()) {
                            invalidate();
                            play(cache);
                        }
                    }
                }, 250);
            }
            return true;
        }
        // 正在准备或重试次数用尽：保持 UI 预览图，不再重放视频层
        activity.setVideoSurfaceLost(false);
        return true;
    }

    void encode(final BackgroundItem item) {
        if (encoding || !mode) {
            return;
        }
        final File cache = activity.caches().fourKCacheFile(item);
        if (cache.exists() && cache.length() > 0) {
            play(cache);
            return;
        }
        if (!LauncherActivity.x264Available) {
            preparing = false;
            Toast.makeText(activity, "x264 编码库不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        encoding = true;
        final int token = ++encodeToken;
        Log.i(TAG, "encode 4K wallpaper: " + item.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap source = null;
                try {
                    int[] target = activity.targetSizeFor(item);
                    if (target == null) {
                        throw new IOException("bad image bounds");
                    }
                    source = activity.decode4KBitmap(item, target[0], target[1]);
                    if (source == null) {
                        throw new IOException("decode failed");
                    }
                    Log.i(TAG, "4K encode target " + target[0] + "x" + target[1]
                            + " for " + item.name);
                    byte[] yuv = FourKEncoder.bitmapToI420(source, target[0], target[1]);
                    source.recycle();
                    source = null;
                    byte[] data = LauncherActivity.encodeI4204K(
                            yuv, target[0], target[1], LauncherActivity.FOUR_K_ENCODE_FRAMES);
                    if (data == null) {
                        throw new IOException("x264 returned null");
                    }
                    FourKEncoder.writeMp4(cache, data, target[0], target[1]);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isDestroyed()) {
                                return;
                            }
                            encoding = false;
                            encodeQueued = false;
                            if (token != encodeToken) {
                                // 编码期间用户又切了壁纸：这张作废，重新排一次延迟加载
                                scheduleStart();
                                return;
                            }
                            if (mode) {
                                play(cache);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "encode 4K wallpaper failed", t);
                    if (source != null) {
                        source.recycle();
                    }
                    cache.delete();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isDestroyed()) {
                                return;
                            }
                            encoding = false;
                            if (token != encodeToken) {
                                if (encodeQueued) {
                                    encodeQueued = false;
                                    scheduleStart();
                                }
                                return;
                            }
                            encodeQueued = false;
                            preparing = false;
                            activity.revealStartupFromBlack();
                            Toast.makeText(activity,
                                    "4K 壁纸生成失败", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "4k-wallpaper-encode").start();
    }

    void play(final File file) {
        final android.widget.VideoView video = activity.wallpaperVideoView();
        if (!mode || video == null) {
            return;
        }
        final int token = ++playToken;
        activity.setVideoSurfaceLost(false);
        preparing = true;
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (token != playToken || !mode) {
                    return;
                }
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                Log.i(TAG, "4K static prepared " + mp.getVideoWidth()
                        + "x" + mp.getVideoHeight());
                activity.bgFrontView().setVisibility(View.GONE);
                activity.bgBackView().setVisibility(View.GONE);
                video.setVisibility(View.VISIBLE);
                video.start();
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                // 快速切图时旧播放器的事件会晚到：不是当前这次播放就忽略，
                // 否则会把 preparing 清掉，露出上一张的旧帧。
                if (token != playToken) {
                    return false;
                }
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "4K static rendering start");
                    preparing = false;
                    activity.setVideoSurfaceLost(false);
                    surfaceReplayCount = 0;
                    activity.uiHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token == playToken && mode) {
                                ready = true;
                                activity.maybeHide4KUiCover();
                            }
                        }
                    }, LauncherActivity.FOUR_K_STATIC_SETTLE_MS);
                    // 静态壁纸只有 3 个相同的帧：等错帧被覆盖后主动暂停，把最后这一帧留在 surface 上，
                    // 省掉解码器每 2 秒重复解一帧 4K。setLooping 仍是 true，所以永远走不到 EOS，
                    // 不会出现播放完成后的黑屏。
                    activity.uiHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token != playToken || !mode) {
                                return;
                            }
                            android.widget.VideoView view = activity.wallpaperVideoView();
                            if (view != null && view.isPlaying()) {
                                view.pause();
                                Log.i(TAG, "4K static paused on frame");
                            }
                        }
                    }, LauncherActivity.FOUR_K_PAUSE_AFTER_MS);
                }
                return false;
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.w(TAG, "4K static playback error " + what + "/" + extra);
                if (token == playToken) {
                    preparing = false;
                    video.setVisibility(View.GONE);
                    if (activity.transitionLayerView() != null) {
                        activity.transitionLayerView().setVisibility(View.GONE);
                    }
                    activity.bgFrontView().setVisibility(View.VISIBLE);
                    activity.bgBackView().setVisibility(View.VISIBLE);
                    activity.revealStartupFromBlack();
                }
                return true;
            }
        });
        Log.i(TAG, "4K static play " + file.getName());
        video.setVisibility(View.VISIBLE);
        video.setVideoPath(file.getAbsolutePath());
    }

    /** 后台预编码下一张 4K 壁纸；与按需编码共用 encoding 标记，同一时刻只有一个编码任务 */
    void prefetch() {
        if (!mode || encoding || !LauncherActivity.x264Available
                || activity.managerImageItems().size() < 2) {
            return;
        }
        final java.util.List<BackgroundItem> items = activity.managerImageItems();
        final BackgroundItem item = items.get((activity.managerImageIndex() + 1) % items.size());
        final File cache = activity.caches().fourKCacheFile(item);
        if (cache.exists() && cache.length() > 0) {
            return;
        }
        encoding = true;
        Log.i(TAG, "prefetch 4K wallpaper: " + item.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    int[] target = activity.targetSizeFor(item);
                    if (target != null) {
                        Bitmap source = activity.decode4KBitmap(item, target[0], target[1]);
                        if (source != null) {
                            byte[] yuv = FourKEncoder.bitmapToI420(source, target[0], target[1]);
                            source.recycle();
                            byte[] data = LauncherActivity.encodeI4204K(
                                    yuv, target[0], target[1], LauncherActivity.FOUR_K_ENCODE_FRAMES);
                            if (data != null) {
                                FourKEncoder.writeMp4(cache, data, target[0], target[1]);
                                ok = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "prefetch 4K wallpaper failed", t);
                    cache.delete();
                }
                final boolean success = ok;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isDestroyed()) {
                            return;
                        }
                        encoding = false;
                        if (!mode) {
                            return;
                        }
                        java.util.List<BackgroundItem> list = activity.managerImageItems();
                        int index = activity.managerImageIndex();
                        BackgroundItem current = (index >= 0 && index < list.size())
                                ? list.get(index) : null;
                        if (success && current != null && current.name.equals(item.name)) {
                            // 用户已经切到这张：直接播，省掉一次防抖等待
                            activity.uiHandler().removeCallbacks(videoStart);
                            Log.i(TAG, "prefetch hit current wallpaper: " + item.name);
                            play(cache);
                            return;
                        }
                        if (encodeQueued) {
                            encodeQueued = false;
                            scheduleStart();
                        }
                    }
                });
            }
        }, "4k-wallpaper-prefetch").start();
    }
}
