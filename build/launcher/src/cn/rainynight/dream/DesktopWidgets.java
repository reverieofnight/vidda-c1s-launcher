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
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 桌面控件层：时钟 / 天气 / 底部 Dock / 壁纸左右键 / 「↓ 打开应用」提示。
 * 负责这些控件的构建、显隐、自动隐藏和各控件开关；
 * 图标的生成、应用启动与壁纸切换仍由 LauncherActivity 执行。
 */
final class DesktopWidgets {

    private static final String TAG = "DSH_WIDGETS";

    /** 自动隐藏：桌面空闲多久后收起控件 */
    private static final long AUTO_HIDE_MS = 30 * 1000L;
    /** 自动隐藏/恢复时控件的淡入淡出时长 */
    private static final long FADE_MS = 260L;
    /** 自动隐藏时控件向上位移的距离（dp），与淡出同步 */
    private static final int FADE_OFFSET_DP = 12;

    private final LauncherActivity activity;
    private final FrameLayout layer;

    private LinearLayout dock;
    private LinearLayout wallpaperControls;
    private TextView wallpaperControlPrev;
    private TextView wallpaperControlNext;
    private LinearLayout clockBox;
    private LinearLayout weatherBox;
    private TextView clockTime;
    private TextView clockDate;
    private TextView hintView;
    private TextView weatherIcon;
    private TextView weatherTemp;
    private TextView weatherDesc;

    /** 桌面控件开关；壁纸切换按钮默认关闭 */
    private boolean widgetClock = true;
    private boolean widgetWeather = true;
    private boolean widgetDock = true;
    private boolean widgetArrows = false;
    private boolean widgetDrawer = true;
    /** 当前显示方式：常驻 / 隐藏 / 自动隐藏 */
    private String widgetMode = LauncherActivity.WIDGET_MODE_ALWAYS;
    /** 自动隐藏模式下，空闲计时到点后置位，任意按键复位 */
    private boolean autoHidden;

    DesktopWidgets(LauncherActivity activity) {
        this.activity = activity;

        SharedPreferences prefs = activity.getSharedPreferences(
                LauncherActivity.PREFS, Context.MODE_PRIVATE);
        widgetClock = prefs.getBoolean(LauncherActivity.KEY_WIDGET_CLOCK, true);
        widgetWeather = prefs.getBoolean(LauncherActivity.KEY_WIDGET_WEATHER, true);
        widgetDock = prefs.getBoolean(LauncherActivity.KEY_WIDGET_DOCK, true);
        widgetArrows = prefs.getBoolean(LauncherActivity.KEY_WIDGET_WALLPAPER_ARROWS, false);
        widgetDrawer = prefs.getBoolean(LauncherActivity.KEY_WIDGET_DRAWER, true);
        widgetMode = prefs.getString(LauncherActivity.KEY_WIDGET_MODE,
                LauncherActivity.WIDGET_MODE_ALWAYS);

        layer = new FrameLayout(activity);
        buildClock();
        buildWeather();
        buildBottomBar();
        applyVisibility();
    }

    FrameLayout getLayer() {
        return layer;
    }

    LinearLayout getDock() {
        return dock;
    }

    LinearLayout getWallpaperControls() {
        return wallpaperControls;
    }

    TextView getPrevControl() {
        return wallpaperControlPrev;
    }

    TextView getNextControl() {
        return wallpaperControlNext;
    }

    boolean isDockVisible() {
        return dock != null && dock.getVisibility() == View.VISIBLE;
    }

    int dockChildCount() {
        return dock == null ? 0 : dock.getChildCount();
    }

    View dockChildAt(int index) {
        return dock == null ? null : dock.getChildAt(index);
    }

    /** 焦点落在 dock 条目的子 View 上时，沿父链找回顶层条目下标 */
    int dockIndexOf(View v) {
        if (dock == null || v == null) {
            return -1;
        }
        View current = v;
        while (current != null) {
            if (current.getParent() == dock) {
                return dock.indexOfChild(current);
            }
            android.view.ViewParent p = current.getParent();
            current = p instanceof View ? (View) p : null;
        }
        return -1;
    }

    /** 左右键焦点环是否可用：开关打开、控件可见且 Dock 有内容 */
    boolean isArrowRingActive() {
        return widgetArrows && widgetsShown() && dockChildCount() > 0;
    }

    // ---------- 构建 ----------

    private void buildClock() {
        clockBox = new LinearLayout(activity);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        // 时钟不铺半透明底板，直接压在壁纸上；保留内边距以维持原来的文字位置
        clockBox.setPadding(activity.dp(26), activity.dp(16), activity.dp(26), activity.dp(18));

        clockTime = new TextView(activity);
        clockTime.setTextColor(Color.WHITE);
        clockTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        clockTime.setShadowLayer(9f, 0f, 3f, 0xCC000000);
        clockTime.setSingleLine(true);
        clockBox.addView(clockTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clockDate = new TextView(activity);
        clockDate.setTextColor(0xFFE8EEF6);
        clockDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        clockDate.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = activity.dp(2);
        clockBox.addView(clockDate, dlp);

        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                activity.dp(330), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clockLp.leftMargin = activity.dp(56);
        clockLp.topMargin = activity.dp(44);
        layer.addView(clockBox, clockLp);
    }

    private void buildWeather() {
        // 右上天气：与时钟同一行，左右两端对齐
        weatherBox = new LinearLayout(activity);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.END);

        LinearLayout weatherRow = new LinearLayout(activity);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.END | Gravity.BOTTOM);

        weatherIcon = new TextView(activity);
        weatherIcon.setText("☁");
        weatherIcon.setTextColor(0xFFE8EEF6);
        weatherIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        weatherIcon.setShadowLayer(7f, 0f, 2f, 0xCC000000);
        LinearLayout.LayoutParams weatherIconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherIconLp.rightMargin = activity.dp(8);
        weatherRow.addView(weatherIcon, weatherIconLp);

        weatherTemp = new TextView(activity);
        weatherTemp.setText("--°");
        weatherTemp.setTextColor(Color.WHITE);
        weatherTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        weatherTemp.setShadowLayer(9f, 0f, 3f, 0xCC000000);
        weatherTemp.setSingleLine(true);
        weatherRow.addView(weatherTemp);

        LinearLayout.LayoutParams weatherRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherBox.addView(weatherRow, weatherRowLp);

        weatherDesc = new TextView(activity);
        weatherDesc.setText("天气加载中…");
        weatherDesc.setTextColor(0xFFE8EEF6);
        weatherDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        weatherDesc.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        weatherDesc.setGravity(Gravity.END);
        weatherDesc.setSingleLine(true);
        LinearLayout.LayoutParams weatherDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherDescLp.topMargin = activity.dp(2);
        weatherBox.addView(weatherDesc, weatherDescLp);

        FrameLayout.LayoutParams weatherLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        weatherLp.rightMargin = activity.dp(56);
        weatherLp.topMargin = activity.dp(60); // 与时钟时间文字顶部对齐
        layer.addView(weatherBox, weatherLp);
    }

    private void buildBottomBar() {
        // 底栏上方：壁纸左右切换控制行
        wallpaperControls = new LinearLayout(activity);
        wallpaperControls.setOrientation(LinearLayout.HORIZONTAL);
        wallpaperControls.setGravity(Gravity.START);
        wallpaperControlPrev = makeWallpaperControl("◀", -1);
        wallpaperControlNext = makeWallpaperControl("▶", 1);
        wallpaperControls.addView(wallpaperControlPrev);
        wallpaperControls.addView(wallpaperControlNext);

        // 右箭头去掉右边距，使其右端与提示文字右端对齐
        LinearLayout.LayoutParams nextLp = (LinearLayout.LayoutParams)
                wallpaperControlNext.getLayoutParams();
        nextLp.rightMargin = 0;

        // 底部栏：左侧"最近使用"占满剩余宽度，右侧是「左右键 + 提示」竖排；
        // 提示文字与 Dock 应用名称共用同一条底线。
        LinearLayout bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.BOTTOM);

        dock = new LinearLayout(activity);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.START | Gravity.BOTTOM);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(dock, dockLp);

        hintView = new TextView(activity);
        hintView.setText("↓ 打开应用");
        hintView.setTextColor(0xCCFFFFFF);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hintView.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        hintView.setGravity(Gravity.END | Gravity.BOTTOM);
        hintView.setPadding(0, 0, 0, 0);

        LinearLayout rightColumn = new LinearLayout(activity);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.setGravity(Gravity.END | Gravity.BOTTOM);

        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.leftMargin = activity.dp(16);
        // 提示文字改由外层单独摆放，这里留出它原来的高度，左右键位置不变
        controlsLp.bottomMargin = activity.dp(20);
        rightColumn.addView(wallpaperControls, controlsLp);

        bottomBar.addView(rightColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        columnLp.leftMargin = activity.dp(30);
        columnLp.rightMargin = activity.dp(30);
        columnLp.bottomMargin = activity.dp(30);
        layer.addView(bottomBar, columnLp);

        // 提示文字单独贴底：比底栏更低，靠近屏幕底部并留一点空隙
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        hintLp.rightMargin = activity.dp(30);
        hintLp.bottomMargin = activity.dp(12);
        layer.addView(hintView, hintLp);
    }

    private TextView makeWallpaperControl(String glyph, final int direction) {
        TextView button = new TextView(activity);
        button.setText(glyph);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.tile_bg);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                activity.dp(52), activity.dp(52));
        lp.rightMargin = activity.dp(8);
        button.setLayoutParams(lp);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.dock_focus : R.drawable.tile_bg);
            }
        });
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.switchWallpaperBy(direction);
            }
        });
        return button;
    }

    // ---------- Dock ----------

    void refreshDock() {
        // 重建前记住焦点所在的应用：removeAllViews 会清掉焦点，
        // 不恢复的话 Android 会把焦点甩给同一行的 ◀ ▶（点应用启动瞬间闪一下左右键）。
        String keepKey = null;
        if (dock != null && activity.isFocusIn(dock)) {
            int idx = dockIndexOf(activity.getCurrentFocus());
            if (idx >= 0 && idx < dock.getChildCount()) {
                Object tag = dock.getChildAt(idx).getTag();
                if (tag instanceof String) {
                    keepKey = (String) tag;
                }
            }
        }
        dock.removeAllViews();
        List<AppEntry> source = activity.buildDockSource();
        int count = Math.min(source.size(), activity.iconsPerRow());
        if (count == 0) {
            TextView empty = new TextView(activity);
            empty.setText("　");
            empty.setFocusable(true);
            empty.setFocusableInTouchMode(true);
            dock.addView(empty);
            return;
        }
        for (int i = 0; i < count; i++) {
            dock.addView(activity.makeDockItem(source.get(i)));
        }
        if (keepKey != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                if (keepKey.equals(dock.getChildAt(i).getTag())) {
                    dock.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
    }

    /** Dock 可见且有条目时把焦点放到第一个应用，返回是否成功 */
    boolean focusDockFirst() {
        if (dock != null && dock.getVisibility() == View.VISIBLE && dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
            return true;
        }
        return false;
    }

    // ---------- 时钟 / 天气 ----------

    void refreshClock() {
        java.util.Date now = new java.util.Date();
        clockTime.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(now));
        clockDate.setText(new java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.CHINA)
                .format(now));
    }

    void applyWeather(String temp, String condition, String place, String symbol) {
        if (weatherTemp == null) {
            return;
        }
        if (android.text.TextUtils.isEmpty(temp)) {
            weatherTemp.setText("--°");
            weatherIcon.setText("☁");
            weatherDesc.setText("天气暂不可用");
            return;
        }
        weatherTemp.setText(temp + "°");
        weatherIcon.setText(symbol);
        StringBuilder desc = new StringBuilder(condition);
        if (!android.text.TextUtils.isEmpty(place)) {
            if (desc.length() > 0) {
                desc.append(" · ");
            }
            desc.append(place);
        }
        weatherDesc.setText(desc.length() == 0 ? "—" : desc.toString());
    }

    // ---------- 显示 / 自动隐藏 ----------

    /** 当前是否该显示桌面控件（显示方式为隐藏，或自动隐藏已到点时不显示） */
    boolean widgetsShown() {
        if (LauncherActivity.WIDGET_MODE_HIDDEN.equals(widgetMode)) {
            return false;
        }
        return !(LauncherActivity.WIDGET_MODE_AUTO.equals(widgetMode) && autoHidden);
    }

    void applyVisibility() {
        applyVisibility(false);
    }

    /**
     * @param animate true 时用淡入淡出过渡（自动隐藏/恢复用）；
     *                false 时立即生效（开关、显示方式切换、回到桌面时用）
     */
    void applyVisibility(boolean animate) {
        boolean shown = widgetsShown();
        // 只在自动隐藏模式下做动画，常驻/隐藏方式的切换保持即时
        boolean fade = animate && LauncherActivity.WIDGET_MODE_AUTO.equals(widgetMode);
        setVisible(clockBox, widgetClock && shown, fade);
        setVisible(weatherBox, widgetWeather && shown, fade);
        setVisible(dock, widgetDock && shown, fade);
        setVisible(wallpaperControls, widgetArrows && shown, fade);
        setVisible(hintView, widgetDrawer && shown, fade);
        if (!widgetDrawer && activity.isDrawerOpen()) {
            activity.closeDrawer();
        }
    }

    /** 用 INVISIBLE 而不是 GONE：保留原有排版位置，隐藏/恢复时界面不跳动 */
    private void setVisible(View v, boolean visible, boolean animate) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (visible) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
                v.setAlpha(0f);
                v.setTranslationY(-activity.dp(FADE_OFFSET_DP));
            }
            v.animate().alpha(1f).translationY(0f)
                    .setDuration(FADE_MS)
                    .setInterpolator(LauncherActivity.OPEN_EASE)
                    .start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).translationY(-activity.dp(FADE_OFFSET_DP))
                    .setDuration(FADE_MS)
                    .setInterpolator(LauncherActivity.CLOSE_EASE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // 动画期间用户又按了键的话，不要把它重新藏起来
                            if (!widgetsShown()) {
                                v.setVisibility(View.INVISIBLE);
                                v.setAlpha(1f);
                                v.setTranslationY(0f);
                            }
                        }
                    })
                    .start();
        }
    }

    private final Runnable autoHideTick = new Runnable() {
        @Override
        public void run() {
            if (!LauncherActivity.WIDGET_MODE_AUTO.equals(widgetMode) || autoHidden) {
                return;
            }
            if (!activity.onDesktopIdle()) {
                // 浮层打开时不计空闲，等回到桌面再继续计时
                activity.uiHandler().postDelayed(this, AUTO_HIDE_MS);
                return;
            }
            autoHidden = true;
            applyVisibility(true);
            Log.i(TAG, "widgets auto hidden after " + (AUTO_HIDE_MS / 1000) + "s idle");
        }
    };

    /** 任意按键/触摸后调用：恢复自动隐藏的控件，并重新开始空闲计时 */
    void resetAutoHide() {
        Handler handler = activity.uiHandler();
        handler.removeCallbacks(autoHideTick);
        if (!LauncherActivity.WIDGET_MODE_AUTO.equals(widgetMode) || !activity.onDesktopIdle()) {
            return;
        }
        if (autoHidden) {
            autoHidden = false;
            applyVisibility(true);
            View focused = activity.getCurrentFocus();
            if (dock != null && dock.getVisibility() == View.VISIBLE
                    && (focused == null || !focused.isShown())) {
                activity.focusFirstDockItem();
            }
        }
        handler.postDelayed(autoHideTick, AUTO_HIDE_MS);
    }

    /** 回到桌面时允许重新显示控件（不清除计时） */
    void resetAutoHidden() {
        autoHidden = false;
    }

    /** 退到后台时停掉自动隐藏计时 */
    void pauseAutoHide() {
        activity.uiHandler().removeCallbacks(autoHideTick);
    }

    boolean isDrawerEnabled() {
        return widgetDrawer;
    }

    // ---------- 控件开关 / 显示方式 ----------

    /** 桌面控件开关的当前值（供设置页初始化开关状态） */
    boolean isEnabled(String key) {
        if (LauncherActivity.KEY_WIDGET_CLOCK.equals(key)) {
            return widgetClock;
        }
        if (LauncherActivity.KEY_WIDGET_WEATHER.equals(key)) {
            return widgetWeather;
        }
        if (LauncherActivity.KEY_WIDGET_DOCK.equals(key)) {
            return widgetDock;
        }
        if (LauncherActivity.KEY_WIDGET_WALLPAPER_ARROWS.equals(key)) {
            return widgetArrows;
        }
        return widgetDrawer;
    }

    void setEnabled(String key, boolean enabled) {
        if (LauncherActivity.KEY_WIDGET_CLOCK.equals(key)) {
            widgetClock = enabled;
        } else if (LauncherActivity.KEY_WIDGET_WEATHER.equals(key)) {
            widgetWeather = enabled;
        } else if (LauncherActivity.KEY_WIDGET_DOCK.equals(key)) {
            widgetDock = enabled;
        } else if (LauncherActivity.KEY_WIDGET_WALLPAPER_ARROWS.equals(key)) {
            widgetArrows = enabled;
        } else if (LauncherActivity.KEY_WIDGET_DRAWER.equals(key)) {
            widgetDrawer = enabled;
        }
        activity.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, enabled).apply();
        applyVisibility();
    }

    String getMode() {
        return widgetMode;
    }

    void setMode(String mode) {
        if (mode == null) {
            return;
        }
        widgetMode = mode;
        activity.getSharedPreferences(LauncherActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(LauncherActivity.KEY_WIDGET_MODE, mode).apply();
        autoHidden = false;
        activity.uiHandler().removeCallbacks(autoHideTick);
        applyVisibility();
        resetAutoHide();
        Log.i(TAG, "widget mode: " + mode);
    }
}
