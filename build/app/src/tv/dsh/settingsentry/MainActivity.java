package tv.dsh.settingsentry;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 无界面入口：点开直接跳到"开发者选项"。
 * 依次尝试多个候选目标，全部失败才退回系统设置首页。
 */
public class MainActivity extends Activity {

    private static final ComponentName[] TARGETS = {
            new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.system.DeveloperOptionsActivity"),
            new ComponentName("com.android.tv.settings",
                    "com.hisense.systemsettings.MainActivity"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        for (ComponentName cn : TARGETS) {
            if (launch(cn)) {
                finish();
                return;
            }
        }

        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            // 全都打不开就安静退出
        }
        finish();
    }

    private boolean launch(ComponentName cn) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
