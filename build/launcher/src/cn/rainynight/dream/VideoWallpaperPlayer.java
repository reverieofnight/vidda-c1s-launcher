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
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;

/**
 * 动态视频壁纸播放器：播放/暂停/续播、surface 重建、首帧与卡死看门狗、
 * 管理页/远程导入让路时的暂停协调。
 * 是否处于视频模式、当前视频下标与文件名仍由 LauncherActivity 维护。
 */
final class VideoWallpaperPlayer {

    private static final String TAG = "DSH_VIDEO";

    private final LauncherActivity activity;

    private boolean ready;
    /** surface 还没建好时先把要播的文件记下来，surfaceCreated 之后再 setVideoPath */
    private File pendingVideoFile;
    private int retryCount;
    /** 最近一次收到首帧渲染回调的时间，自检日志用 */
    private long lastFrameAt;
    private String playingVideoPath;
    /** 卡死看门狗：位置连续多个自检周期不变，判定解码器挂起 */
    private int stallTicks;
    private int lastPosition;
    /** 当前视频因卡死重播过几次；换视频时清零，避免无限重试 */
    private int stallRetries;
    private boolean muted = true;
    private MediaPlayer playerRef;
    private boolean pausedByManager;
    private boolean pausedByTransfer;
    /** 同时进行的上传请求数（浏览器可能并发传多个文件） */
    private int transferCount;
    /** 传输结束时页面还开着，等页面关闭后再续播 */
    private boolean resumePending;
    private int playToken;

    /** 视频模式下的周期自检，排查黑屏用 */
    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            VideoView video = activity.wallpaperVideoView();
            if (!activity.isVideoWallpaperMode() || video == null) {
                return;
            }
            SurfaceHolder holder = video.getHolder();
            String surface = holder.getSurface() == null
                    ? "null"
                    : (holder.getSurface().isValid() ? "valid" : "invalid");
            long sinceFrame = lastFrameAt == 0L ? -1L
                    : (System.currentTimeMillis() - lastFrameAt) / 1000L;
            Log.i(TAG, "video state: surface=" + surface
                    + " visible=" + (video.getVisibility() == View.VISIBLE)
                    + " playing=" + video.isPlaying()
                    + " pos=" + video.getCurrentPosition()
                    + " dur=" + video.getDuration()
                    + " ready=" + ready
                    + " retries=" + retryCount
                    + " sinceFirstFrame=" + sinceFrame + "s");
            // 卡死看门狗：解码器偶尔会停住，isPlaying 仍为 true 但位置不动、
            // 画面停在最后一帧或变黑。先重播一次，仍不动就退回静态壁纸，避免一直黑屏。
            try {
                int pos = video.getCurrentPosition();
                if (ready && video.isPlaying() && pos == lastPosition) {
                    stallTicks++;
                } else {
                    stallTicks = 0;
                }
                lastPosition = pos;
                if (stallTicks >= 2) {
                    stallTicks = 0;
                    if (stallRetries < 1) {
                        stallRetries++;
                        Log.w(TAG, "video stalled at " + pos + "ms, retry prepare");
                        refresh();
                    } else {
                        Log.w(TAG, "video still stalled, fall back to image mode");
                        stallRetries = 0;
                        activity.onVideoFallbackToImage(true);
                    }
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "video stall check failed: " + t);
            }
            activity.uiHandler().postDelayed(this, 5000);
        }
    };

    VideoWallpaperPlayer(LauncherActivity activity) {
        this.activity = activity;
    }

    void setMuted(boolean value) {
        muted = value;
        if (playerRef != null) {
            float volume = muted ? 0f : 1f;
            try {
                playerRef.setVolume(volume, volume);
            } catch (Throwable t) {
                Log.w(TAG, "apply video volume failed: " + t);
            }
        }
    }

    boolean isMuted() {
        return muted;
    }

    void resetRetries() {
        retryCount = 0;
        stallRetries = 0;
    }

    /** surface 重建/回到前台等场景重新准备当前视频 */
    void refresh() {
        VideoView video = activity.wallpaperVideoView();
        if (video == null) {
            return;
        }
        if (activity.isWallpaperCleared()) {
            // 当前壁纸删掉后的黑屏状态，不要再自动恢复视频
            stop();
            return;
        }
        if (!activity.isVideoWallpaperMode() || activity.videoItemCount() == 0) {
            stop();
            return;
        }
        int index = activity.currentVideoIndex();
        if (index < 0 || index >= activity.videoItemCount()) {
            index = 0;
            activity.setCurrentVideoIndex(0);
        }
        BackgroundItem item = activity.videoItemAt(index);
        activity.setCurrentVideoName(item.name);
        activity.persistVideoWallpaper(item.name);
        play(new File(item.filePath));
    }

    void play(final File file) {
        VideoView video = activity.wallpaperVideoView();
        if (video == null || file == null || !file.isFile()) {
            return;
        }
        final int token = ++playToken;
        ready = false;
        activity.pauseSlideshowForVideo();
        final String prevPath = playingVideoPath;
        if (prevPath != null && !prevPath.equals(file.getAbsolutePath())) {
            // 换新视频：重试计数清零，避免上一个视频的失败次数影响这一个
            retryCount = 0;
            stallRetries = 0;
        }
        stallTicks = 0;
        lastPosition = 0;
        final boolean switchingVideo = playerRef != null && prevPath != null
                && !prevPath.equals(file.getAbsolutePath());
        if (switchingVideo) {
            // 动态壁纸之间切换：先用当前视频的一帧铺封面，不能铺静态壁纸（会闪一下静态图）
            final File prevFile = new File(prevPath);
            int position = 0;
            try {
                position = Math.max(0, video.getCurrentPosition());
            } catch (Throwable ignored) {
            }
            final int captureMs = position;
            Log.i(TAG, "video switch, capture cover @" + captureMs + "ms " + prevFile.getName());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap frame = grabFrame(prevFile, captureMs);
                    activity.uiHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != playToken) {
                                return;
                            }
                            if (frame != null) {
                                showFrameCover(frame);
                            }
                            start(file, token, true);
                        }
                    });
                }
            }, "video-switch-cover").start();
            return;
        }
        // 已经在动态壁纸模式里重播（例如从其他应用返回、surface 重建）：
        // 不铺静态壁纸封面，直接等视频首帧，中间黑就黑。
        final boolean rePrepare = activity.isVideoWallpaperMode() && playingVideoPath != null;
        start(file, token, rePrepare);
    }

    /** 真正开始播放下一个视频；frameCover=true 表示封面已经用视频帧铺好，不再铺静态壁纸 */
    private void start(final File file, final int token, final boolean frameCover) {
        final VideoView video = activity.wallpaperVideoView();
        if (video == null) {
            return;
        }
        pausedByManager = false;
        resumePending = false;
        activity.stopFourKForVideo();
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (token != playToken || !activity.isVideoWallpaperMode()) {
                    return;
                }
                mp.setLooping(true);
                playerRef = mp;
                float volume = muted ? 0f : 1f;
                mp.setVolume(volume, volume);
                // 先保留 bgFront/bgBack（显示上一张壁纸），等首帧真正渲染出来再隐藏，
                // 避免 prepare 期间整屏黑掉。视频→视频切换时 bgFront 里是旧静态图，不能再露出来。
                if (!frameCover && activity.bgFrontView().getDrawable() != null) {
                    activity.bgFrontView().setVisibility(View.VISIBLE);
                }
                if (activity.transitionLayerView() != null && !frameCover) {
                    activity.transitionLayerView().setVisibility(View.GONE);
                }
                video.setVisibility(View.VISIBLE);
                video.start();
                // 看门狗：3 秒内没有收到首帧就重建一次，避免停在黑屏不再恢复
                activity.uiHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (token != playToken || !activity.isVideoWallpaperMode() || ready) {
                            return;
                        }
                        if (retryCount < 1) {
                            retryCount++;
                            Log.w(TAG, "video wallpaper no frame in 3s, retry prepare #"
                                    + retryCount);
                            refresh();
                        } else {
                            Log.w(TAG, "video wallpaper still black, fall back to image mode");
                            retryCount = 0;
                            activity.onVideoFallbackToImage(false);
                        }
                    }
                }, 3000);

                Log.i(TAG, "video wallpaper prepared " + mp.getVideoWidth()
                        + "x" + mp.getVideoHeight() + " " + file.getName());
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "video wallpaper rendering start");
                    lastFrameAt = System.currentTimeMillis();
                    ready = true;
                    retryCount = 0;
                    activity.setVideoSurfaceLost(false);
                    video.setVisibility(View.VISIBLE);
                    activity.hideVideoCover();
                    activity.bgFrontView().setVisibility(View.GONE);
                    activity.bgBackView().setVisibility(View.GONE);
                    activity.uiHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token == playToken && activity.isVideoWallpaperMode()) {
                                activity.markFourKVideoReady();
                            }
                        }
                    }, LauncherActivity.FOUR_K_FRAME_SETTLE_MS);
                }
                return false;
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (token != playToken) {
                    return true;
                }
                Log.w(TAG, "video wallpaper failed: " + what + "/" + extra);
                // 删除当前视频后切换时，surface 正在重建，播放器会报一次 1/0；
                // 这种瞬时错误先重试，别直接退回静态壁纸
                if (retryCount < 2) {
                    retryCount++;
                    activity.uiHandler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isVideoWallpaperMode()) {
                                refresh();
                            }
                        }
                    }, 500);
                    return true;
                }
                activity.onVideoFallbackToImage(false);
                return true;
            }
        });
        // 视频层必须可见，否则 surface 建不起来、prepare 完也拿不到帧；
        // 空 surface 是黑的，所以在它上面先盖一层当前壁纸，等首帧渲染再撤。
        // 视频→视频切换时封面已经用上一段视频的帧铺好，不能换成静态壁纸。
        if (!frameCover) {
            activity.showVideoCover();
        }
        video.setVisibility(View.VISIBLE);
        playingVideoPath = file.getAbsolutePath();
        activity.uiHandler().removeCallbacks(healthTick);
        activity.uiHandler().postDelayed(healthTick, 5000);
        SurfaceHolder holder = video.getHolder();
        if (holder.getSurface() == null || !holder.getSurface().isValid()) {
            // 没有输出目标时 setVideoPath 会 prepare 成功但永远不出帧（黑屏），
            // 记录待播文件，等 surfaceCreated 回调里再 prepare。
            pendingVideoFile = file;
            Log.i(TAG, "video surface not ready, defer prepare " + file.getName());
            // surfaceCreated 有可能在本方法之前就已经跑过（返回桌面时的竞态），
            // 那样就没人再来接这个 pending，画面会一直黑着。这里补两次兜底。
            activity.uiHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (token != playToken || !activity.isVideoWallpaperMode()
                            || pendingVideoFile == null) {
                        return;
                    }
                    VideoView view = activity.wallpaperVideoView();
                    if (view == null) {
                        return;
                    }
                    SurfaceHolder h = view.getHolder();
                    if (h.getSurface() != null && h.getSurface().isValid()) {
                        File pending = pendingVideoFile;
                        pendingVideoFile = null;
                        Log.i(TAG, "video surface recovered, prepare " + pending.getName());
                        view.setVideoPath(pending.getAbsolutePath());
                    }
                }
            }, 200);
            activity.uiHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (token != playToken || !activity.isVideoWallpaperMode()
                            || pendingVideoFile == null) {
                        return;
                    }
                    if (retryCount < 1) {
                        retryCount++;
                        Log.w(TAG, "video surface still not ready, retry prepare");
                        refresh();
                    }
                }
            }, 1500);
        } else {
            video.setVideoPath(playingVideoPath);
        }
        Log.i(TAG, "video wallpaper play " + file.getName());
    }

    void stop() {
        ready = false;
        pausedByManager = false;
        pausedByTransfer = false;
        resumePending = false;
        playingVideoPath = null;
        pendingVideoFile = null;
        playToken++;
        playerRef = null;
        activity.uiHandler().removeCallbacks(healthTick);
        VideoView video = activity.wallpaperVideoView();
        if (video != null) {
            video.setOnPreparedListener(null);
            video.setOnInfoListener(null);
            video.setOnErrorListener(null);
            video.stopPlayback();
            video.setVisibility(View.GONE);
        }
    }

    /** surfaceCreated：接管待播文件或 surface 丢失后的重播。返回 true 表示已处理 */
    boolean onSurfaceCreated() {
        VideoView video = activity.wallpaperVideoView();
        if (video == null) {
            return false;
        }
        if (pendingVideoFile != null) {
            if (!activity.isVideoWallpaperMode()) {
                // 已经切回静态壁纸了，别再补播这个待播视频
                Log.i(TAG, "video surface ready, drop pending " + pendingVideoFile.getName());
                pendingVideoFile = null;
                return false;
            }
            File pending = pendingVideoFile;
            pendingVideoFile = null;
            Log.i(TAG, "video surface ready, prepare " + pending.getName());
            video.setVideoPath(pending.getAbsolutePath());
            return true;
        }
        if (activity.isVideoSurfaceLost() && activity.isVideoWallpaperMode()) {
            // Surface 被销毁过（打开管理页、切走再回来等），旧 MediaPlayer 已经没有
            // 输出目标了，直接重新 prepare 一次，否则画面会一直黑着。
            activity.setVideoSurfaceLost(false);
            File file = currentFile();
            if (file != null) {
                Log.i(TAG, "video surface recreated, re-prepare " + file.getName());
                ready = false;
                video.setVideoPath(file.getAbsolutePath());
            }
            return true;
        }
        return false;
    }

    void onSurfaceDestroyed() {
        ready = false;
    }

    /** 当前视频壁纸的真实文件（找不到返回 null） */
    File currentFile() {
        if (activity.videoItemCount() == 0) {
            return null;
        }
        int index = activity.currentVideoIndex();
        if (index < 0 || index >= activity.videoItemCount()) {
            index = 0;
            activity.setCurrentVideoIndex(0);
        }
        return new File(activity.videoItemAt(index).filePath);
    }

    /** 远程导入结束：把为传输让路而暂停的视频从原位置续播 */
    void resumeAfterTransfer() {
        resumePending = false;
        VideoView video = activity.wallpaperVideoView();
        if (!activity.isVideoWallpaperMode() || video == null) {
            return;
        }
        if (activity.isVideoSurfaceLost() || playerRef == null) {
            refresh();
            return;
        }
        try {
            video.start();
            ready = true;
            activity.uiHandler().removeCallbacks(healthTick);
            activity.uiHandler().postDelayed(healthTick, 5000);
            Log.i(TAG, "remote transfer done, video wallpaper resumed");
        } catch (Throwable t) {
            Log.w(TAG, "resume video after transfer failed: " + t);
            refresh();
        }
    }

    /**
     * 回到前台：
     * 1) 播放器还停在同一个文件上、只是被 pause 了 → 直接 start() 续播，不出黑帧；
     * 2) 播放器已经不在了（进程内重建/曾被 stop）→ 重新 prepare，bgFront 垫着上一张壁纸直到首帧。
     * play() 里还有 3 秒看门狗兜底，真卡黑屏会重试一次再退回图片模式。
     */
    void restoreOnForeground() {
        if (activity.isManagerOpen() || activity.isRemoteOpen()) {
            // 管理页/远程页还盖着桌面：保持暂停，等页面关闭时再续播或重播
            Log.i(TAG, "skip video restore while manager/remote page open");
            return;
        }
        if (pausedByTransfer) {
            // 远程导入还没传完：先保持暂停，传完由 endTransfer 续播
            Log.i(TAG, "skip video restore while transfer in progress");
            return;
        }
        File file = currentFile();
        if (activity.isVideoSurfaceLost()) {
            // surface 被销毁过（切走应用时必然发生），旧播放器已经没有输出目标：
            // 直接重新 prepare，别等 3 秒看门狗才恢复画面。
            activity.setVideoSurfaceLost(false);
            Log.i(TAG, "video surface lost, re-prepare on foreground");
            refresh();
            return;
        }
        VideoView video = activity.wallpaperVideoView();
        if (playerRef != null && video != null && file != null
                && playingVideoPath != null
                && playingVideoPath.equals(file.getAbsolutePath())) {
            try {
                if (!video.isPlaying()) {
                    video.start();
                }
                ready = true;
                resumePending = false;
                activity.uiHandler().removeCallbacks(healthTick);
                activity.uiHandler().postDelayed(healthTick, 5000);
                Log.i(TAG, "video wallpaper resumed " + file.getName());
                return;
            } catch (Throwable t) {
                Log.w(TAG, "video resume failed: " + t);
            }
        }
        refresh();
    }

    /** 管理页打开：释放解码器资源 */
    void pauseForOverlay() {
        VideoView video = activity.wallpaperVideoView();
        if (!activity.isVideoWallpaperMode() || video == null) {
            return;
        }
        activity.uiHandler().removeCallbacks(healthTick);
        try {
            if (video.isPlaying()) {
                video.pause();
                pausedByManager = true;
                Log.i(TAG, "manager opened, video wallpaper paused");
            }
        } catch (Throwable t) {
            Log.w(TAG, "pause video on manager open failed: " + t);
        }
    }

    /** 管理页关闭：surface 还在就续播，否则重新 prepare */
    void resumeAfterOverlay() {
        VideoView video = activity.wallpaperVideoView();
        if (activity.isVideoWallpaperMode() && video != null) {
            if (activity.isVideoSurfaceLost() || playerRef == null) {
                Log.i(TAG, "manager closed, surface lost, re-prepare video wallpaper");
                refresh();
            } else if (pausedByManager || resumePending) {
                resumePending = false;
                try {
                    video.start();
                    ready = true;
                    activity.uiHandler().removeCallbacks(healthTick);
                    activity.uiHandler().postDelayed(healthTick, 5000);
                    Log.i(TAG, "manager closed, video wallpaper resumed");
                } catch (Throwable t) {
                    Log.w(TAG, "resume video on manager close failed: " + t);
                    refresh();
                }
            }
        }
        pausedByManager = false;
        resumePending = false;
    }

    /** 远程上传开始：为磁盘写入让路暂停解码 */
    void beginTransfer() {
        transferCount++;
        // 页面盖着桌面时视频本来就已经暂停了，不需要再动；
        // 只有人在桌面、视频正在播时，才为导入让路暂停解码。
        VideoView video = activity.wallpaperVideoView();
        if (transferCount == 1 && activity.isVideoWallpaperMode() && video != null
                && !activity.isManagerOpen() && !activity.isRemoteOpen()) {
            try {
                if (video.isPlaying()) {
                    video.pause();
                    pausedByTransfer = true;
                    activity.uiHandler().removeCallbacks(healthTick);
                    Log.i(TAG, "remote transfer started, video wallpaper paused");
                }
            } catch (Throwable t) {
                Log.w(TAG, "pause video for transfer failed: " + t);
            }
        }
    }

    void endTransfer() {
        if (transferCount > 0) {
            transferCount--;
        }
        if (transferCount > 0 || !pausedByTransfer) {
            return;
        }
        pausedByTransfer = false;
        if (activity.isManagerOpen() || activity.isRemoteOpen() || !activity.isUiForeground()) {
            // 传输结束时页面还开着、或桌面不在前台：等回到桌面后再续播
            resumePending = true;
            Log.i(TAG, "remote transfer done, resume later");
            return;
        }
        resumeAfterTransfer();
    }

    boolean isPausedForTransfer() {
        return pausedByTransfer;
    }

    boolean isResumePending() {
        return resumePending;
    }

    /** 调试 video_state：立刻打一行状态 */
    void requestHealthLog() {
        activity.uiHandler().postDelayed(healthTick, 500);
    }

    /** 退到后台/销毁时停掉看门狗 */
    void pauseHealth() {
        activity.uiHandler().removeCallbacks(healthTick);
    }

    /** 抓视频某一时刻的帧（缩到 1280x720）当切换封面，解不出来返回 null */
    private Bitmap grabFrame(File file, int positionMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getScaledFrameAtTime(positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 720);
            if (frame == null) {
                frame = retriever.getFrameAtTime(positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame != null) {
                Log.i(TAG, "video cover frame " + frame.getWidth() + "x" + frame.getHeight()
                        + " @" + positionMs + "ms");
            }
            return frame;
        } catch (Throwable t) {
            Log.w(TAG, "video cover frame failed: " + t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用正在播放的视频帧当过渡封面（视频→视频切换时用，避免闪静态壁纸） */
    private void showFrameCover(Bitmap frame) {
        if (activity.transitionLayerView() == null || activity.transitionNewView() == null
                || frame == null) {
            return;
        }
        if (activity.transitionOldView() != null) {
            activity.transitionOldView().setImageDrawable(null);
        }
        activity.transitionNewView().setImageBitmap(frame);
        activity.transitionNewView().setAlpha(1f);
        activity.transitionLayerView().setAlpha(1f);
        activity.transitionLayerView().setVisibility(View.VISIBLE);
        Log.i(TAG, "video frame cover shown");
    }
}
