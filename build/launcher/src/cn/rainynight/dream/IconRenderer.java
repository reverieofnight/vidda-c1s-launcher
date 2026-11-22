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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用图标渲染：裁透明边距、统一圆角方形、Dock 柔和阴影与缓存。
 */
final class IconRenderer {

    private final LauncherActivity activity;
    private final Map<String, Bitmap> gridIconCache = new HashMap<>();
    /** Dock 图标阴影缓存：应用 key + 图标边长 → 带柔和阴影的位图（比图标大一圈） */
    private final Map<String, Bitmap> dockShadowCache = new HashMap<>();

    IconRenderer(LauncherActivity activity) {
        this.activity = activity;
    }

    /**
     * 图标归一化：先裁掉透明边距，再等比缩放居中放进正方形画布。
     * 这样方形图标、宽 banner、纯 logo 在视觉上大小一致。
     */
    Bitmap squareIcon(Drawable src, int size) {
        int raw = Math.max(size * 2, 192);
        Bitmap canvasBitmap = Bitmap.createBitmap(raw, raw, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);

        int iw = Math.max(1, src.getIntrinsicWidth());
        int ih = Math.max(1, src.getIntrinsicHeight());
        float preScale = Math.min((float) raw / iw, (float) raw / ih);
        int w = Math.round(iw * preScale);
        int h = Math.round(ih * preScale);
        int left = (raw - w) / 2;
        int top = (raw - h) / 2;
        src.setBounds(left, top, left + w, top + h);
        src.draw(canvas);

        int[] pixels = new int[raw * raw];
        canvasBitmap.getPixels(pixels, 0, raw, 0, 0, raw, raw);
        int minX = raw, minY = raw, maxX = -1, maxY = -1;
        for (int y = 0; y < raw; y++) {
            int row = y * raw;
            for (int x = 0; x < raw; x++) {
                if ((pixels[row + x] >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            minX = 0; minY = 0; maxX = raw - 1; maxY = raw - 1;
        }

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas outCanvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        float scale = Math.min((float) size / cw, (float) size / ch) * 0.94f;
        int dw = Math.max(1, Math.round(cw * scale));
        int dh = Math.max(1, Math.round(ch * scale));
        int dx = (size - dw) / 2;
        int dy = (size - dh) / 2;

        outCanvas.drawBitmap(canvasBitmap,
                new Rect(minX, minY, maxX + 1, maxY + 1),
                new Rect(dx, dy, dx + dw, dy + dh),
                paint);

        // 统一图标外形：所有 app 图标裁成圆角方形（半径约 22%）
        Bitmap masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas maskedCanvas = new Canvas(masked);
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path round = new Path();
        float radius = size * 0.22f;
        round.addRoundRect(new RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW);
        maskedCanvas.clipPath(round);
        maskedCanvas.drawBitmap(out, 0f, 0f, maskPaint);
        out.recycle();
        return masked;
    }

    /**
     * Dock 图标阴影：把图标 alpha 染成黑色、四周留出一圈空白后做一次模糊，
     * 得到一张比图标略大的柔和投影，垫在图标下面用。
     */
    Bitmap dockIconShadow(AppEntry entry, Bitmap icon) {
        if (icon == null || icon.isRecycled()) {
            return null;
        }
        int size = icon.getWidth();
        String key = entry.key + "@" + size;
        Bitmap cached = dockShadowCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        int pad = Math.max(4, Math.round(size * 0.12f));
        int total = size + pad * 2;
        Bitmap silhouette = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(silhouette);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        // 往下偏一点点，看起来像被上方光源打出的投影
        canvas.drawBitmap(icon, pad, pad + Math.round(size * 0.04f), paint);

        Bitmap blurred = activity.blurBitmap(silhouette, Math.max(3f, size * 0.09f));
        silhouette.recycle();
        if (blurred == null) {
            return null;
        }

        // 整体压一点透明度：亮壁纸上不会糊成一块黑，暗壁纸上仍能托住图标
        Bitmap shadow = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        Canvas shadowCanvas = new Canvas(shadow);
        Paint alphaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        alphaPaint.setAlpha(150);
        shadowCanvas.drawBitmap(blurred, 0f, 0f, alphaPaint);
        blurred.recycle();
        dockShadowCache.put(key, shadow);
        return shadow;
    }

    Bitmap gridIcon(AppEntry entry) {
        Bitmap cached = gridIconCache.get(entry.key);
        if (cached == null) {
            cached = squareIcon(entry.icon, activity.dp(activity.iconSizeDp()));
            gridIconCache.put(entry.key, cached);
        }
        return cached;
    }

    /** 图标尺寸变化后清空按尺寸生成的缓存 */
    void clearCaches() {
        gridIconCache.clear();
        dockShadowCache.clear();
    }
}
