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

import android.graphics.Color;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 顶部菜单条（横条 + 底部描边）：从屏幕外滑入，焦点在菜单项之间移动。
 * 只负责菜单自身；桌面下移、焦点归还和打开哪个页面仍由 LauncherActivity 执行。
 */
final class DesktopMenu {

    private static final String TAG = "DSH_MENU";

    private final LauncherActivity activity;
    private final LinearLayout module;
    private final LinearLayout bar;

    private boolean open;

    DesktopMenu(LauncherActivity activity) {
        this.activity = activity;

        module = new LinearLayout(activity);
        module.setOrientation(LinearLayout.VERTICAL);
        module.setVisibility(View.GONE);
        module.setElevation(activity.dp(8));

        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.menu_strip_bg);
        bar.setPadding(
                activity.dp(LauncherActivity.MENU_PADDING_H_DP),
                activity.dp(LauncherActivity.MENU_PADDING_V_DP),
                activity.dp(LauncherActivity.MENU_PADDING_H_DP),
                activity.dp(LauncherActivity.MENU_PADDING_V_DP));

        bar.addView(makeItem(android.R.drawable.ic_menu_gallery, "壁纸管理"));
        bar.addView(makeItem(android.R.drawable.ic_menu_manage, "桌面设置"));

        module.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(LauncherActivity.MENU_BAR_HEIGHT_DP)));

        View line = new View(activity);
        line.setBackgroundColor(0xFFBDBDBD);
        module.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(LauncherActivity.MENU_BORDER_DP)));

        module.setTranslationY(-activity.dp(LauncherActivity.MENU_HEIGHT_DP));
    }

    View getView() {
        return module;
    }

    FrameLayout.LayoutParams layoutParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(LauncherActivity.MENU_HEIGHT_DP),
                Gravity.TOP);
    }

    boolean isOpen() {
        return open;
    }

    /** 管理页 / 设置页等全屏浮层打开时挡住菜单焦点 */
    void setFocusBlocked(boolean blocked) {
        module.setDescendantFocusability(blocked
                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    }

    void open() {
        if (open || activity.isDrawerOpen()) {
            return;
        }
        open = true;
        activity.rememberReturnFocus();
        activity.setDesktopOffsetForMenu(true);

        module.animate().cancel();
        module.setVisibility(View.VISIBLE);
        module.setTranslationY(-activity.dp(LauncherActivity.MENU_HEIGHT_DP));
        module.animate().translationY(0f).setDuration(LauncherActivity.MENU_ANIM_MS)
                .setInterpolator(LauncherActivity.OPEN_EASE).start();

        if (bar.getChildCount() > 0) {
            bar.getChildAt(0).requestFocus();
        }
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        module.animate().cancel();
        module.animate().translationY(-activity.dp(LauncherActivity.MENU_HEIGHT_DP))
                .setDuration(LauncherActivity.MENU_ANIM_MS)
                .setInterpolator(LauncherActivity.CLOSE_EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (open) {
                            return;
                        }
                        module.setVisibility(View.GONE);
                        activity.restoreReturnFocus();
                    }
                }).start();

        activity.setDesktopOffsetForMenu(false);
        activity.resetWidgetAutoHide();
    }

    /** 不带动画地收起（打开其它页面或回到前台时用） */
    void hideImmediately() {
        open = false;
        module.animate().cancel();
        module.setVisibility(View.GONE);
        module.setTranslationY(-activity.dp(LauncherActivity.MENU_HEIGHT_DP));
        activity.resetDesktopOffsetForMenu();
    }

    private View makeItem(int iconRes, final String label) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4));
        item.setBackgroundResource(R.drawable.tile_bg);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.dock_focus : R.drawable.tile_bg);
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("壁纸管理".equals(label)) {
                    // 菜单直达壁纸管理页（播放设置在管理页里）
                    activity.openWallpaperManager();
                } else if ("桌面设置".equals(label)) {
                    activity.openDesktopSettings();
                } else {
                    // 其它菜单项功能待实现
                    Log.i(TAG, "menu item clicked: " + label + " (todo)");
                }
            }
        });

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFF2F2F2);
        item.addView(icon, new LinearLayout.LayoutParams(
                activity.dp(28), activity.dp(28)));

        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextColor(0xFFF2F2F2);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setSingleLine(true);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = activity.dp(2);
        item.addView(text, textLp);

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                activity.dp(LauncherActivity.MENU_ITEM_WIDTH_DP),
                activity.dp(LauncherActivity.MENU_ITEM_HEIGHT_DP));
        itemLp.rightMargin = activity.dp(8);
        item.setLayoutParams(itemLp);
        return item;
    }
}
