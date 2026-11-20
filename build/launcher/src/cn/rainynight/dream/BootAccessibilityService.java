package cn.rainynight.dream;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 方案 B2：用无障碍服务实现开机自启。
 *
 * 系统在 boot 时会绑定已启用的无障碍服务，这条路径不经过被厂商拦截的
 * BOOT_COMPLETED 广播。绑定后稍等几秒（等厂商桌面先起来），再把我们的
 * 桌面拉到前台。
 *
 * 约束：
 * - 不读取窗口内容，不做任何辅助操作，只负责这一次 startActivity。
 * - 用 Settings.Global.BOOT_COUNT 记录“本次开机已处理过”，避免服务重连时
 *   反复把桌面顶到前台。每次开机只拉起一次。
 */
public class BootAccessibilityService extends AccessibilityService {

    private static final String TAG = "DSH_A11Y";
    private static final String PREFS = "launcher_prefs";
    private static final String KEY_LAST_BOOT = "a11y_last_boot_count";

    /** 等厂商桌面先完成启动，避免我们起来后又被它盖住 */
    private static final long LAUNCH_DELAY_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable launchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                startActivity(new Intent(BootAccessibilityService.this, LauncherActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                Log.i(TAG, "startActivity sent");
            } catch (Throwable t) {
                Log.w(TAG, "startActivity failed: " + t, t);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        int bootCount = Settings.Global.getInt(
                getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        int lastBoot = sp.getInt(KEY_LAST_BOOT, Integer.MIN_VALUE);

        Log.i(TAG, "connected bootCount=" + bootCount + " lastBoot=" + lastBoot);

        if (bootCount >= 0 && lastBoot == bootCount) {
            Log.i(TAG, "already handled this boot, skip");
            return;
        }
        if (bootCount >= 0) {
            sp.edit().putInt(KEY_LAST_BOOT, bootCount).apply();
        }

        handler.removeCallbacks(launchRunnable);
        handler.postDelayed(launchRunnable, LAUNCH_DELAY_MS);
        Log.i(TAG, "scheduled launch in " + LAUNCH_DELAY_MS + "ms");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只借这条绑定关系做自启，不处理任何事件
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(launchRunnable);
        super.onDestroy();
    }
}
