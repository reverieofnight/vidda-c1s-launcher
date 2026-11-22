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
import android.util.Log;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

import java.util.List;

/**
 * 1080p 桌面背景轮播：当前背景下标、交叉淡化、后台预解码、轮播计时。
 * 背景图列表的扫描/刷新仍由 LauncherActivity 负责，4K 模式切换也由宿主发起。
 */
final class SlideshowController {

    private static final String TAG = "DSH_SLIDESHOW";
    private static final long FADE_MS = 1200L;
    private static final PathInterpolator FADE_EASE =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private final LauncherActivity activity;

    private boolean enabled = true;
    private int intervalSec = 30;
    private boolean fade = true;
    private boolean random;

    private int index = -1;
    private String currentName;
    private boolean switching;
    private boolean tickScheduled;

    private Bitmap preloadBitmap;
    private String preloadKey;
    private int preloadIndex = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickScheduled = false;
            if (!enabled || activity.isVideoWallpaperMode()) {
                return;
            }
            if (activity.fourKStaticEnabled()) {
                if (activity.managerImageItems().size() > 1) {
                    activity.switch4KStatic(1, random);
                }
            } else if (activity.backgroundItems().size() > 1) {
                advance(1, random);
            }
            tickScheduled = true;
            activity.uiHandler().postDelayed(this, intervalSec * 1000L);
        }
    };

    SlideshowController(LauncherActivity activity) {
        this.activity = activity;
    }

    // ---------- 设置 ----------

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean value) {
        enabled = value;
    }

    int getIntervalSec() {
        return intervalSec;
    }

    void setIntervalSec(int value) {
        intervalSec = value;
    }

    boolean isFade() {
        return fade;
    }

    void setFade(boolean value) {
        fade = value;
    }

    boolean isRandom() {
        return random;
    }

    void setRandom(boolean value) {
        random = value;
    }

    // ---------- 当前背景 ----------

    int currentIndex() {
        return index;
    }

    String currentName() {
        return currentName;
    }

    /** 4K 预览接管当前画面时同步下标与文件名，但不碰 1080p 视图 */
    void adoptCurrent(int backgroundIndex, String name) {
        switching = false;
        index = backgroundIndex;
        currentName = name;
    }

    void clearCurrent() {
        index = -1;
        currentName = null;
    }

    /** 列表变化后作废预解码缓存 */
    void invalidatePreload() {
        preloadBitmap = null;
        preloadKey = null;
        preloadIndex = -1;
    }

    // ---------- 显示 / 轮播 ----------

    void showImmediately(int newIndex) {
        if (activity.isWallpaperCleared()) {
            activity.clearWallpaperToBlack();
            return;
        }
        switching = false;
        ImageView front = activity.bgFrontView();
        ImageView back = activity.bgBackView();
        front.animate().cancel();
        back.animate().cancel();
        back.setAlpha(0f);

        List<BackgroundItem> items = activity.backgroundItems();
        if (items.isEmpty()) {
            activity.showBuiltinFallback();
            index = -1;
            currentName = null;
            activity.invalidateWallpaperBlur();
            return;
        }
        if (newIndex < 0 || newIndex >= items.size()) {
            newIndex = 0;
        }
        BackgroundItem item = items.get(newIndex);
        Bitmap bitmap = activity.loadBackground(item);
        if (bitmap == null) {
            activity.showBuiltinFallback();
            index = -1;
            currentName = null;
            activity.invalidateWallpaperBlur();
            return;
        }
        front.setImageBitmap(bitmap);
        front.setAlpha(1f);
        index = newIndex;
        currentName = item.name;
        activity.invalidateWallpaperBlur();
        preloadNext();
        activity.precomputeWallpaperBlur();
    }

    /** 自动轮播 / 左右键切换：两张图交叉淡入淡出，避免直接闪切 */
    void advance(int delta) {
        advance(delta, false);
    }

    /** random=true 时随机挑一张（不重复当前），用于「播放顺序=随机」 */
    void advance(int delta, boolean randomPick) {
        List<BackgroundItem> items = activity.backgroundItems();
        if (activity.isWallpaperCleared() || activity.fourKStaticEnabled()
                || items.size() < 2 || switching) {
            return;
        }
        int size = items.size();
        int next;
        if (index < 0) {
            next = randomPick ? new java.util.Random().nextInt(size)
                    : (delta > 0 ? 0 : size - 1);
        } else if (randomPick) {
            next = new java.util.Random().nextInt(size - 1);
            if (next >= index) {
                next++;
            }
        } else {
            next = (index + delta + size) % size;
        }
        if (next == index) {
            return;
        }

        BackgroundItem item = items.get(next);
        Bitmap bitmap = null;
        // 优先用后台预解码好的那张，避免在主线程解码大图造成卡顿
        if (next == preloadIndex && preloadBitmap != null
                && !preloadBitmap.isRecycled()
                && backgroundKey(item).equals(preloadKey)) {
            bitmap = preloadBitmap;
            preloadBitmap = null;
            preloadIndex = -1;
            preloadKey = null;
        }
        if (bitmap == null) {
            bitmap = activity.loadBackground(item);
        }
        if (bitmap == null) {
            return;
        }

        switching = true;
        ImageView incoming = activity.bgBackView();
        ImageView outgoing = activity.bgFrontView();
        incoming.setImageBitmap(bitmap);
        if (fade) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(FADE_MS)
                    .setInterpolator(FADE_EASE).start();
            outgoing.animate().alpha(0f).setDuration(FADE_MS)
                    .setInterpolator(FADE_EASE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            switching = false;
                        }
                    })
                    .start();
        } else {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
            switching = false;
        }

        activity.swapBackgroundViews(incoming, outgoing);
        index = next;
        currentName = item.name;
        activity.invalidateWallpaperBlur();
        Log.i(TAG, "background -> " + item.name);
        preloadNext();
        activity.precomputeWallpaperBlur();
    }

    private String backgroundKey(BackgroundItem item) {
        return item.name + "|" + item.stamp + "|" + item.assetName;
    }

    /** 后台预解码下一张 1080p 壁纸（轮播/左右键切换时直接淡入） */
    void preloadNext() {
        List<BackgroundItem> items = activity.backgroundItems();
        if (items.size() < 2 || activity.fourKStaticEnabled()
                || activity.isVideoWallpaperMode()) {
            return;
        }
        int base = index < 0 ? 0 : index;
        final int next = (base + 1) % items.size();
        final BackgroundItem item = items.get(next);
        final String key = backgroundKey(item);
        if (key.equals(preloadKey) && preloadBitmap != null) {
            return;
        }
        preloadKey = key;
        preloadIndex = next;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = activity.loadBackground(item);
                if (bitmap == null) {
                    return;
                }
                activity.uiHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (!key.equals(preloadKey) || preloadIndex != next) {
                            return;
                        }
                        preloadBitmap = bitmap;
                    }
                });
            }
        }, "bg-preload").start();
    }

    // ---------- 计时 ----------

    void resetTimer() {
        activity.uiHandler().removeCallbacks(tick);
        tickScheduled = false;
        if (!enabled || activity.isVideoWallpaperMode()) {
            return;
        }
        int count = activity.fourKStaticEnabled()
                ? activity.managerImageItems().size() : activity.backgroundItems().size();
        if (count > 1) {
            activity.uiHandler().postDelayed(tick, intervalSec * 1000L);
            tickScheduled = true;
        }
    }

    /**
     * 只保证计时器在跑，不打断已有的倒计时。
     * 用在 onResume / 4K 模式刷新这类"可能已经排过队"的路径上，
     * 否则频繁回到桌面会把 30 分钟的倒计时无限推迟。
     */
    void ensureTimer() {
        if (!tickScheduled) {
            resetTimer();
        }
    }

    void stopTimer() {
        activity.uiHandler().removeCallbacks(tick);
        tickScheduled = false;
    }
}
