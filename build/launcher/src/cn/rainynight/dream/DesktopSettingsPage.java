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
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 桌面设置页（全屏浮层）：左侧一级菜单（图标尺寸 / 桌面控件），右侧二级内容。
 * 只负责界面；图标尺寸与控件开关的实际生效仍由 LauncherActivity 执行。
 */
final class DesktopSettingsPage {

    private final LauncherActivity activity;
    private final FrameLayout module;

    private TextView navIconSize;
    private TextView smallButton;
    private TextView mediumButton;
    private TextView largeButton;
    private TextView iconDesc;
    private TextView navWidgets;
    private LinearLayout iconPage;
    private LinearLayout widgetPage;
    private LinearLayout widgetFirstRow;
    private TextView modeAlways;
    private TextView modeHidden;
    private TextView modeAuto;

    /** 当前选中的二级页：icons / widgets */
    private String section = "icons";
    private boolean open;

    DesktopSettingsPage(LauncherActivity activity) {
        this.activity = activity;

        module = new FrameLayout(activity);
        module.setBackgroundColor(0xFF0B1017);
        module.setVisibility(View.GONE);
        module.setClickable(true);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(activity.dp(28), activity.dp(28), activity.dp(28), activity.dp(20));
        module.addView(page, matchParent());

        // ---------- 左侧：一级菜单 ----------
        // 左侧：右侧 = 4 : 6
        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.VERTICAL);
        page.addView(nav, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 4f));

        TextView title = new TextView(activity);
        title.setText("桌面设置");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(activity.dp(16), 0, 0, activity.dp(12));
        nav.addView(title);

        navIconSize = makeNavItem("图标尺寸", "icons");
        nav.addView(navIconSize);
        navWidgets = makeNavItem("桌面控件", "widgets");
        nav.addView(navWidgets);

        View divider = new View(activity);
        divider.setBackgroundColor(0xFF1F2A36);
        page.addView(divider, new LinearLayout.LayoutParams(
                activity.dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- 右侧：二级内容 ----------
        LinearLayout detail = new LinearLayout(activity);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(activity.dp(36), 0, 0, 0);
        page.addView(detail, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 6f));

        // ---- 页一：图标尺寸 ----
        iconPage = new LinearLayout(activity);
        iconPage.setOrientation(LinearLayout.VERTICAL);
        detail.addView(iconPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView sectionTitle = new TextView(activity);
        sectionTitle.setText("图标尺寸");
        sectionTitle.setTextColor(Color.WHITE);
        sectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        iconPage.addView(sectionTitle);

        TextView sectionDesc = new TextView(activity);
        sectionDesc.setText("调整 Dock 与抽屉的图标尺寸，每行数量会自动适配。");
        sectionDesc.setTextColor(0xFF8FA0B3);
        sectionDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams sectionDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionDescLp.topMargin = activity.dp(8);
        iconPage.addView(sectionDesc, sectionDescLp);

        // 分段按钮：具体尺寸
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setBackgroundColor(0xFF141C27);
        smallButton = makeIconSizeOption("40×40", LauncherActivity.ICON_SIZE_SMALL);
        mediumButton = makeIconSizeOption("50×50", LauncherActivity.ICON_SIZE_MEDIUM);
        largeButton = makeIconSizeOption("60×60", LauncherActivity.ICON_SIZE_LARGE);
        group.addView(smallButton);
        group.addView(mediumButton);
        group.addView(largeButton);
        LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44));
        groupLp.topMargin = activity.dp(24);
        iconPage.addView(group, groupLp);

        iconDesc = new TextView(activity);
        iconDesc.setTextColor(0xFF8FA0B3);
        iconDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = activity.dp(18);
        iconPage.addView(iconDesc, descLp);

        // ---- 页二：桌面控件 ----
        widgetPage = new LinearLayout(activity);
        widgetPage.setOrientation(LinearLayout.VERTICAL);
        widgetPage.setVisibility(View.GONE);
        detail.addView(widgetPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView widgetTitle = new TextView(activity);
        widgetTitle.setText("桌面控件");
        widgetTitle.setTextColor(Color.WHITE);
        widgetTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        widgetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        widgetPage.addView(widgetTitle);

        TextView widgetDesc = new TextView(activity);
        widgetDesc.setText("选择桌面上要显示的元素，关闭后对应位置留空。");
        widgetDesc.setTextColor(0xFF8FA0B3);
        widgetDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams widgetDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        widgetDescLp.topMargin = activity.dp(8);
        widgetPage.addView(widgetDesc, widgetDescLp);

        widgetFirstRow = makeFlagRow("时钟", LauncherActivity.KEY_WIDGET_CLOCK);
        widgetPage.addView(widgetFirstRow, activity.settingsRowLp());

        widgetPage.addView(makeFlagRow("天气", LauncherActivity.KEY_WIDGET_WEATHER),
                activity.settingsRowLp());

        widgetPage.addView(makeFlagRow("Dock 应用栏", LauncherActivity.KEY_WIDGET_DOCK),
                activity.settingsRowLp());

        widgetPage.addView(makeFlagRow("壁纸切换按钮（◀ ▶）",
                LauncherActivity.KEY_WIDGET_WALLPAPER_ARROWS), activity.settingsRowLp());

        LinearLayout drawerRow = makeFlagRow("抽屉（↓ 打开应用）",
                LauncherActivity.KEY_WIDGET_DRAWER);
        widgetPage.addView(drawerRow, activity.settingsRowLp());

        TextView modeTitle = new TextView(activity);
        modeTitle.setText("显示方式");
        modeTitle.setTextColor(Color.WHITE);
        modeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        modeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams modeTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeTitleLp.topMargin = activity.dp(20);
        widgetPage.addView(modeTitle, modeTitleLp);

        LinearLayout modeGroup = new LinearLayout(activity);
        modeGroup.setOrientation(LinearLayout.HORIZONTAL);
        modeGroup.setBackgroundColor(0xFF141C27);
        modeAlways = makeWidgetModeOption("常驻", LauncherActivity.WIDGET_MODE_ALWAYS);
        modeHidden = makeWidgetModeOption("隐藏", LauncherActivity.WIDGET_MODE_HIDDEN);
        modeAuto = makeWidgetModeOption("自动隐藏", LauncherActivity.WIDGET_MODE_AUTO);
        modeGroup.addView(modeAlways);
        modeGroup.addView(modeHidden);
        modeGroup.addView(modeAuto);
        LinearLayout.LayoutParams modeGroupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44));
        modeGroupLp.topMargin = activity.dp(10);
        widgetPage.addView(modeGroup, modeGroupLp);

        TextView modeDesc = new TextView(activity);
        modeDesc.setText("自动隐藏：桌面空闲 30 秒后隐藏全部控件，按任意键或触摸后恢复。");
        modeDesc.setTextColor(0xFF8FA0B3);
        modeDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams modeDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeDescLp.topMargin = activity.dp(12);
        widgetPage.addView(modeDesc, modeDescLp);

        // 左右方向键在菜单与内容之间切换
        navIconSize.setId(0x2010);
        smallButton.setId(0x2011);
        navWidgets.setId(0x2012);
        widgetFirstRow.setId(0x2013);
        drawerRow.setId(0x2014);
        modeAlways.setId(0x2015);
        modeHidden.setId(0x2016);
        modeAuto.setId(0x2017);
        navIconSize.setNextFocusRightId(0x2011);
        widgetFirstRow.setNextFocusLeftId(0x2012);
        navWidgets.setNextFocusRightId(0x2013);
        smallButton.setNextFocusLeftId(0x2010);
        // 一级菜单最后一项按下键停住，避免直接跳进右侧内容
        navWidgets.setNextFocusDownId(0x2012);
        // 显示方式一行：下键固定落到「常驻」，左右在三个选项之间移动
        drawerRow.setNextFocusDownId(0x2015);
        modeAlways.setNextFocusUpId(0x2014);
        modeHidden.setNextFocusUpId(0x2014);
        modeAuto.setNextFocusUpId(0x2014);
        modeAlways.setNextFocusLeftId(0x2012);
        modeAlways.setNextFocusRightId(0x2016);
        modeHidden.setNextFocusLeftId(0x2015);
        modeHidden.setNextFocusRightId(0x2017);
        modeAuto.setNextFocusLeftId(0x2016);
        modeAuto.setNextFocusRightId(0x2017);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    View getView() {
        return module;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) {
            return;
        }
        activity.settingsEnterFullscreen();
        open = true;
        updateIconSizeOptions();
        selectSection(section);
        updateWidgetModeOptions();
        module.setVisibility(View.VISIBLE);
        // 默认焦点落在左侧一级菜单，按右键进入右侧设置项
        TextView navItem = "widgets".equals(section) ? navWidgets : navIconSize;
        if (navItem != null) {
            navItem.requestFocus();
        }
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        module.setVisibility(View.GONE);
        activity.settingsExitFullscreen();
    }

    private LinearLayout makeFlagRow(String label, final String key) {
        return activity.makeSettingsRow(label, activity.widgetEnabled(key),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        activity.setWidgetEnabled(key, checked);
                    }
                });
    }

    private TextView makeNavItem(String label, final String target) {
        TextView item = new TextView(activity);
        item.setText(label);
        item.setTextColor(Color.WHITE);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(activity.dp(16), 0, activity.dp(16), 0);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(48)));
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                // 焦点移到哪一项，右侧就切到对应的二级页
                if (hasFocus) {
                    selectSection(target);
                }
                updateNav();
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点击后把焦点交给右侧页面的第一项
                selectSection(target);
                if ("icons".equals(target)) {
                    smallButton.requestFocus();
                } else {
                    widgetFirstRow.requestFocus();
                }
            }
        });
        return item;
    }

    private void selectSection(String target) {
        if (target == null) {
            return;
        }
        section = target;
        boolean icons = "icons".equals(target);
        iconPage.setVisibility(icons ? View.VISIBLE : View.GONE);
        widgetPage.setVisibility(icons ? View.GONE : View.VISIBLE);
        updateNav();
    }

    /** 一级菜单：选中项保持深色底，聚焦时高亮 */
    private void updateNav() {
        applyNavStyle(navIconSize, "icons");
        applyNavStyle(navWidgets, "widgets");
    }

    private void applyNavStyle(TextView item, String target) {
        if (item.isFocused()) {
            item.setBackgroundResource(R.drawable.dock_focus);
        } else if (target.equals(section)) {
            item.setBackgroundColor(0xFF1E2A38);
        } else {
            item.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private TextView makeIconSizeOption(String label, final String preset) {
        TextView option = new TextView(activity);
        option.setText(label);
        option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        option.setGravity(Gravity.CENTER);
        option.setFocusable(true);
        option.setFocusableInTouchMode(true);
        option.setPadding(activity.dp(18), 0, activity.dp(18), 0);
        option.setLayoutParams(new LinearLayout.LayoutParams(activity.dp(120), activity.dp(44)));
        option.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateIconSizeOptions();
            }
        });
        option.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.setIconSizePreset(preset);
                updateIconSizeOptions();
            }
        });
        return option;
    }

    private void updateIconSizeOptions() {
        applyIconSizeOption(smallButton, LauncherActivity.ICON_SIZE_SMALL);
        applyIconSizeOption(mediumButton, LauncherActivity.ICON_SIZE_MEDIUM);
        applyIconSizeOption(largeButton, LauncherActivity.ICON_SIZE_LARGE);
        iconDesc.setText("当前：" + activity.iconSizeDp() + "×" + activity.iconSizeDp()
                + " · Dock 与抽屉每行 " + activity.iconsPerRow() + " 个");
        updateNav();
    }

    private void applyIconSizeOption(TextView option, String preset) {
        boolean selected = preset.equals(activity.iconSizePreset());
        boolean focused = option.isFocused();
        if (focused) {
            option.setBackgroundResource(R.drawable.dock_focus);
        } else {
            option.setBackgroundColor(selected ? 0xFF1E2A38 : 0xFF141C27);
        }
        option.setTextColor(selected || focused ? Color.WHITE : 0xFF8FA0B3);
    }

    private TextView makeWidgetModeOption(String label, final String mode) {
        TextView option = new TextView(activity);
        option.setText(label);
        option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        option.setGravity(Gravity.CENTER);
        option.setFocusable(true);
        option.setFocusableInTouchMode(true);
        option.setPadding(activity.dp(18), 0, activity.dp(18), 0);
        option.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44)));
        option.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateWidgetModeOptions();
            }
        });
        option.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.setWidgetMode(mode);
                updateWidgetModeOptions();
            }
        });
        return option;
    }

    private void updateWidgetModeOptions() {
        applyWidgetModeOption(modeAlways, LauncherActivity.WIDGET_MODE_ALWAYS);
        applyWidgetModeOption(modeHidden, LauncherActivity.WIDGET_MODE_HIDDEN);
        applyWidgetModeOption(modeAuto, LauncherActivity.WIDGET_MODE_AUTO);
    }

    private void applyWidgetModeOption(TextView option, String mode) {
        boolean selected = mode.equals(activity.widgetModeValue());
        boolean focused = option.isFocused();
        if (focused) {
            option.setBackgroundResource(R.drawable.dock_focus);
        } else {
            option.setBackgroundColor(selected ? 0xFF1E2A38 : 0xFF141C27);
        }
        option.setTextColor(selected || focused ? Color.WHITE : 0xFF8FA0B3);
    }
}
