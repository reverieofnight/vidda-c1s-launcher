package tv.dsh.launcher;

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
    /** 仅用于联调：adb shell am broadcast -a tv.dsh.launcher.TEST_BOOT */
    private static final String ACTION_TEST = "tv.dsh.launcher.TEST_BOOT";

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
