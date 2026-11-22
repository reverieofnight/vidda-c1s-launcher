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

import android.app.AlertDialog;
import android.graphics.Color;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 背景设置对话框：4K 静态壁纸开关、轮播开关 / 间隔 / 顺序 / 特效、动态壁纸静音。
 *
 * 只负责界面与设置项交互，播放状态与实际生效由 LauncherActivity / 播放器处理。
 */
final class BackgroundSettingsPage {

    private static final String TAG = "DSH_LAUNCHER";
    private static final int[] SLIDESHOW_STEPS = {10, 30, 60, 300, 600, 1800};

    private final LauncherActivity activity;
    private final SlideshowController slideshow;
    private final VideoWallpaperPlayer videoPlayer;

    BackgroundSettingsPage(LauncherActivity activity, SlideshowController slideshow,
            VideoWallpaperPlayer videoPlayer) {
        this.activity = activity;
        this.slideshow = slideshow;
        this.videoPlayer = videoPlayer;
    }

    void show() {
        activity.loadPlaybackSettings();

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(activity.dp(30), activity.dp(18), activity.dp(30), activity.dp(6));

        LinearLayout row4k = activity.makeSettingsRow("4K 静态壁纸",
                activity.reveal4KVideo(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        activity.set4KStaticEnabled(isChecked);
                    }
                });
        box.addView(row4k, activity.settingsRowLp());

        LinearLayout rowSlide = activity.makeSettingsRow("幻灯片播放", slideshow.isEnabled(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        slideshow.setEnabled(isChecked);
                        activity.savePlaybackSettings();
                        activity.resetBgTimer();
                        Log.i(TAG, "slideshow = " + isChecked);
                    }
                });
        box.addView(rowSlide, activity.settingsRowLp());

        // 切换间隔：左侧名称、右侧「◀ 值 ▶」（箭头位置固定，只更新中间的值）
        LinearLayout rowInterval = makeStepperRow("切换间隔");
        final TextView intervalValue = addStepperValue(rowInterval);
        final Runnable refreshInterval = new Runnable() {
            @Override
            public void run() {
                intervalValue.setText(intervalLabel(slideshow.getIntervalSec()));
            }
        };
        refreshInterval.run();

        rowInterval.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                int delta = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                        : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
                if (delta == 0) {
                    return false;
                }
                stepSlideshowInterval(delta);
                refreshInterval.run();
                return true;
            }
        });
        rowInterval.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSlideshowInterval(1);
                refreshInterval.run();
            }
        });
        box.addView(rowInterval, activity.settingsRowLp());

        // 播放顺序：左侧名称、右侧「◀ 顺序/随机 ▶」
        LinearLayout rowOrder = makeStepperRow("播放顺序");
        final TextView orderValue = addStepperValue(rowOrder);
        final Runnable refreshOrder = new Runnable() {
            @Override
            public void run() {
                orderValue.setText(slideshow.isRandom() ? "随机" : "顺序");
            }
        };
        refreshOrder.run();

        rowOrder.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                        && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return false;
                }
                slideshow.setRandom(!slideshow.isRandom());
                activity.savePlaybackSettings();
                refreshOrder.run();
                return true;
            }
        });
        rowOrder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slideshow.setRandom(!slideshow.isRandom());
                activity.savePlaybackSettings();
                refreshOrder.run();
            }
        });
        box.addView(rowOrder, activity.settingsRowLp());

        LinearLayout rowFade = activity.makeSettingsRow("切换特效", slideshow.isFade(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        slideshow.setFade(isChecked);
                        activity.savePlaybackSettings();
                        Log.i(TAG, "slideshow fade = " + isChecked);
                    }
                });
        box.addView(rowFade, activity.settingsRowLp());

        LinearLayout rowMute = activity.makeSettingsRow("动态壁纸静音", videoPlayer.isMuted(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        videoPlayer.setMuted(isChecked);
                        activity.savePlaybackSettings();
                    }
                });
        box.addView(rowMute, activity.settingsRowLp());

        TextView hint4k = hint("4K 静态壁纸：将 4K 图片编码为 3 帧短视频，"
                + "利用硬件视频层播放，比直接渲染更清晰。");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = activity.dp(10);
        box.addView(hint4k, dlp);

        TextView hintCache = hint(activity.fourKCacheSummary()
                + "\n图片保存在 /sdcard/VIDAA桌面壁纸/静态、视频在 /动态，卸载应用不会删除；"
                + "4K 缓存位于应用内部存储，卸载或清空数据后会自动重建。");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = activity.dp(6);
        box.addView(hintCache, clp);

        new AlertDialog.Builder(activity)
                .setTitle("背景设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 设置项整行：左侧名称，右侧留给「◀ 值 ▶」 */
    private LinearLayout makeStepperRow(String title) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8));

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        label.setTextColor(Color.WHITE);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
        return row;
    }

    /** 「◀ 值 ▶」：左右箭头位置固定，值在中间居中；返回中间的值控件用于刷新 */
    private TextView addStepperValue(LinearLayout parent) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);

        int arrowColor = 0xFF7FB2E5;
        TextView left = new TextView(activity);
        left.setText("◀");
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        left.setTextColor(arrowColor);

        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        value.setTextColor(Color.WHITE);
        value.setGravity(Gravity.CENTER);

        TextView right = new TextView(activity);
        right.setText("▶");
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        right.setTextColor(arrowColor);

        box.addView(left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(value, new LinearLayout.LayoutParams(
                activity.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(box, new LinearLayout.LayoutParams(
                activity.dp(136), ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private TextView hint(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(0xFF9FB0C2);
        view.setLineSpacing(activity.dp(2), 1f);
        return view;
    }

    private String intervalLabel(int seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分钟";
    }

    private void stepSlideshowInterval(int delta) {
        int index = 0;
        for (int i = 0; i < SLIDESHOW_STEPS.length; i++) {
            if (SLIDESHOW_STEPS[i] == slideshow.getIntervalSec()) {
                index = i;
                break;
            }
        }
        index = (index + delta + SLIDESHOW_STEPS.length) % SLIDESHOW_STEPS.length;
        slideshow.setIntervalSec(SLIDESHOW_STEPS[index]);
        activity.savePlaybackSettings();
        activity.resetBgTimer();
        Log.i(TAG, "slideshow interval = " + slideshow.getIntervalSec() + "s");
    }
}
