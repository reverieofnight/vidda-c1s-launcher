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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 开机自启：等系统把厂商桌面拉起来之后（约 5 秒），
 * 自己抢到前台，替代系统桌面。
 *
 * 设计意图：不修改系统默认桌面，避免"开机找不到桌面"导致黑屏。
 * 即使这里被系统拦截，最坏结果也只是停在厂商桌面，不会黑屏。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final long DELAY_MS = 5000;
    private static final String TAG = "DSH_BOOT";
    /** 仅用于联调：adb shell am broadcast -a cn.rainynight.dream.TEST_BOOT */
    private static final String ACTION_TEST = "cn.rainynight.dream.TEST_BOOT";

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean isTest = ACTION_TEST.equals(action);
        Log.i(TAG, "onReceive action=" + action + " boot=" + isBoot + " test=" + isTest);
        if (!isBoot && !isTest) {
            return;
        }
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    context.startActivity(new Intent(context, LauncherActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    Log.i(TAG, "startActivity sent");
                } catch (Throwable t) {
                    Log.w(TAG, "startActivity failed: " + t, t);
                    // 后台启动被拦截时不做事，厂商桌面保持在前台
                } finally {
                    pending.finish();
                }
            }
        }, DELAY_MS);
    }
}
