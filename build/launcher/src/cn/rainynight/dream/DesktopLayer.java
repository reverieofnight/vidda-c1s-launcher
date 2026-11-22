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
import android.graphics.Canvas;
import android.graphics.Color;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.VideoView;

/**
 * 桌面大层：承载壁纸两层、视频层、过渡层与遮罩，并负责菜单打开时的整体下压。
 * 播放器、控件和抽屉等仍由各自的类挂到这一层上。
 */
final class DesktopLayer {

    private static final String TAG = "DSH_LAYER";

    private final LauncherActivity activity;
    private final FrameLayout module;
    private ImageView bgFront;
    private ImageView bgBack;
    private final VideoView video;
    private final FrameLayout transitionLayer;
    private final ImageView transitionOld;
    private final ImageView transitionNew;

    DesktopLayer(LauncherActivity activity) {
        this.activity = activity;

        module = new FrameLayout(activity);

        // 两张背景图交替淡化：bgFront 是当前图，bgBack 是下一张
        bgFront = new ImageView(activity);
        bgFront.setScaleType(ImageView.ScaleType.CENTER_CROP);
        module.addView(bgFront, matchParent());

        bgBack = new ImageView(activity);
        bgBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgBack.setAlpha(0f);
        module.addView(bgBack, matchParent());
        applyWallpaperAlignment(bgFront);
        applyWallpaperAlignment(bgBack);

        // 4K 视频壁纸层：普通 SurfaceView 会被压到 1080p，VideoView 走硬件视频 plane
        video = new VideoView(activity);
        video.setFocusable(false);
        video.setVisibility(View.GONE);
        module.addView(video, matchParent());

        // 4K 视频切换时的 1080p 快照交叉淡化层（位于 VideoView 之上、scrim 之下）
        transitionLayer = new FrameLayout(activity);
        transitionLayer.setBackgroundColor(Color.TRANSPARENT);
        transitionLayer.setVisibility(View.GONE);

        transitionOld = new ImageView(activity);
        transitionOld.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transitionLayer.addView(transitionOld, matchParent());

        transitionNew = new ImageView(activity);
        transitionNew.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transitionNew.setAlpha(0f);
        transitionLayer.addView(transitionNew, matchParent());
        transitionLayer.setTranslationX(LauncherActivity.WALLPAPER_NUDGE_X);
        transitionLayer.setTranslationY(LauncherActivity.WALLPAPER_NUDGE_Y);
        transitionLayer.setScaleX(LauncherActivity.WALLPAPER_OVERSCAN);
        transitionLayer.setScaleY(LauncherActivity.WALLPAPER_OVERSCAN);
        module.addView(transitionLayer, matchParent());

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x33000000);
        module.addView(scrim, matchParent());
    }

    private void applyWallpaperAlignment(ImageView view) {
        view.setTranslationX(LauncherActivity.WALLPAPER_NUDGE_X);
        view.setTranslationY(LauncherActivity.WALLPAPER_NUDGE_Y);
        view.setScaleX(LauncherActivity.WALLPAPER_OVERSCAN);
        view.setScaleY(LauncherActivity.WALLPAPER_OVERSCAN);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    FrameLayout getModule() {
        return module;
    }

    ImageView bgFront() {
        return bgFront;
    }

    ImageView bgBack() {
        return bgBack;
    }

    VideoView video() {
        return video;
    }

    FrameLayout transitionLayer() {
        return transitionLayer;
    }

    ImageView transitionOld() {
        return transitionOld;
    }

    ImageView transitionNew() {
        return transitionNew;
    }

    /** 添加桌面内的子层（控件层、抽屉等），按调用顺序叠放 */
    void addLayer(View view) {
        module.addView(view, matchParent());
    }

    void setFocusBlocked(boolean blocked) {
        module.setDescendantFocusability(blocked
                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    }

    /** 交叉淡化后交换前后两层，下一张继续从 bgBack 进来 */
    void swapBackgrounds() {
        ImageView front = bgFront;
        bgFront = bgBack;
        bgBack = front;
    }

    // ---------- 菜单打开时的整体位移 ----------

    /** opening=true 打开菜单；floating=true 表示 4K 视频层露出，菜单悬浮、桌面不动 */
    void animateForMenu(boolean opening, boolean floating) {
        module.animate().cancel();
        if (opening && !floating) {
            module.animate().translationY(activity.dp(LauncherActivity.MENU_HEIGHT_DP))
                    .setDuration(LauncherActivity.MENU_ANIM_MS)
                    .setInterpolator(LauncherActivity.OPEN_EASE).start();
        } else if (opening) {
            module.setTranslationY(0f);
        } else {
            module.animate().translationY(0f)
                    .setDuration(LauncherActivity.MENU_ANIM_MS)
                    .setInterpolator(LauncherActivity.CLOSE_EASE).start();
        }
    }

    void resetOffset() {
        module.animate().cancel();
        module.setTranslationY(0f);
    }

    void relayoutForMenu(boolean floating) {
        module.animate().cancel();
        float target = floating ? 0f : activity.dp(LauncherActivity.MENU_HEIGHT_DP);
        module.animate().translationY(target)
                .setDuration(LauncherActivity.MENU_ANIM_MS)
                .setInterpolator(target == 0f
                        ? LauncherActivity.CLOSE_EASE : LauncherActivity.OPEN_EASE).start();
    }

    // ---------- 抽屉毛玻璃：壁纸截图与模糊 ----------

    /** 把 bgFront 当前实际显示的壁纸抓成 960px 宽的位图 */
    Bitmap captureWallpaper() {
        if (bgFront.getDrawable() == null) {
            return null;
        }
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        int width = 960;
        int height = Math.max(1, Math.round(width * sh / (float) sw));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(width / (float) sw, height / (float) sh);
        bgFront.draw(canvas);
        return out;
    }

    Bitmap blur(Bitmap source) {
        return blur(source, 24f);
    }

    Bitmap blur(Bitmap source, float radius) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        Bitmap output = null;
        RenderScript rs = null;
        Allocation in = null;
        Allocation out = null;
        ScriptIntrinsicBlur blur = null;
        try {
            output = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            rs = RenderScript.create(activity);
            in = Allocation.createFromBitmap(rs, source);
            out = Allocation.createFromBitmap(rs, output);
            blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(radius);
            blur.setInput(in);
            blur.forEach(out);
            out.copyTo(output);
            return output;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper blur failed", t);
            if (output != null) {
                output.recycle();
            }
            return null;
        } finally {
            if (blur != null) {
                blur.destroy();
            }
            if (in != null) {
                in.destroy();
            }
            if (out != null) {
                out.destroy();
            }
            if (rs != null) {
                rs.destroy();
            }
        }
    }
}
