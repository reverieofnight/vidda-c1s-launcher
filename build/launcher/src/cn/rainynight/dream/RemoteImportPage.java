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
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 远程导入页（局域网 HTTP 上传，全屏浮层，盖在管理页上面）。
 * 负责页面 UI、二维码与 HTTP 服务生命周期；壁纸读写仍由 RemoteImportHostBridge 转交桌面。
 */
final class RemoteImportPage {

    private static final String TAG = "DSH_LAUNCHER";

    private final LauncherActivity activity;
    private final FrameLayout module;
    private final TextView urlView;
    private final ImageView qrView;
    private final TextView statusView;
    private final TextView backButton;
    private final Switch keepSwitch;

    private RemoteImportServer server;
    private boolean open;

    RemoteImportPage(LauncherActivity activity) {
        this.activity = activity;

        module = new FrameLayout(activity);
        module.setBackgroundColor(0xFF0B1017);
        module.setVisibility(View.GONE);
        module.setClickable(true);

        // 左右两栏：左侧地址/开关/返回，右侧二维码，避免竖排堆叠过挤
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(activity.dp(64), activity.dp(30), activity.dp(64), activity.dp(26));
        module.addView(page, matchParent());

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.START);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.rightMargin = activity.dp(36);
        page.addView(column, columnParams);

        TextView title = new TextView(activity);
        title.setText("远程导入");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(activity);
        hint.setText("手机扫码，或在同一 WiFi 下用电脑/手机浏览器打开下面的地址，即可上传、切换、删除壁纸。\n"
                + "上传后需要点「设为当前」才会换画面；默认离开本页会关闭服务。");
        hint.setTextColor(0xFFB8C4D2);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setLineSpacing(activity.dp(6), 1f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = activity.dp(10);
        column.addView(hint, hintParams);

        urlView = new TextView(activity);
        urlView.setText("正在启动…");
        urlView.setTextColor(0xFF4C8DFF);
        urlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        urlView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlView.setGravity(Gravity.CENTER);
        urlView.setPadding(activity.dp(22), activity.dp(10), activity.dp(22), activity.dp(10));
        urlView.setBackgroundColor(0xFF131B26);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlParams.topMargin = activity.dp(18);
        column.addView(urlView, urlParams);

        statusView = new TextView(activity);
        statusView.setText("等待连接…");
        statusView.setTextColor(0xFF7F8FA3);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setPadding(0, activity.dp(10), 0, 0);
        column.addView(statusView);

        // 保持服务运行：关闭本页后浏览器仍可继续导入/管理
        LinearLayout keepRow = new LinearLayout(activity);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        keepRow.setGravity(Gravity.CENTER_VERTICAL);
        keepRow.setPadding(activity.dp(16), activity.dp(6), activity.dp(16), activity.dp(6));
        keepRow.setFocusable(true);
        keepRow.setFocusableInTouchMode(true);
        TextView keepLabel = new TextView(activity);
        keepLabel.setText("关闭本页后保持服务运行");
        keepLabel.setTextColor(0xFFB8C4D2);
        keepLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        keepRow.addView(keepLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        keepSwitch = new Switch(activity);
        keepSwitch.setFocusable(false);
        keepSwitch.setClickable(false);
        keepSwitch.setChecked(activity.isRemoteKeepRunning());
        keepRow.addView(keepSwitch);
        keepRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.setRemoteKeepRunning(!activity.isRemoteKeepRunning());
            }
        });
        keepRow.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
        LinearLayout.LayoutParams keepParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keepParams.topMargin = activity.dp(18);
        column.addView(keepRow, keepParams);

        View spacer = new View(activity);
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);
        backButton = activity.makeManagerButton("返回", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        actions.addView(backButton);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = activity.dp(20);
        column.addView(actions, actionsParams);

        qrView = new ImageView(activity);
        qrView.setVisibility(View.GONE);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12));
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                activity.dp(288), activity.dp(288));
        qrParams.gravity = Gravity.CENTER_VERTICAL;
        page.addView(qrView, qrParams);
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
        open = true;
        module.setVisibility(View.VISIBLE);
        module.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        activity.remotePageEnterFullscreen();
        if (server == null) {
            server = new RemoteImportServer(new RemoteImportHostBridge(activity));
            server.setAssetOpener(new RemoteImportServer.AssetOpener() {
                @Override
                public InputStream open(String assetName) throws IOException {
                    return activity.getAssets().open(assetName);
                }
            });
        }
        if (server.start(8080)) {
            String ip = localIpv4();
            String url = "http://" + (ip == null ? "127.0.0.1" : ip)
                    + ":" + server.getPort() + "/";
            urlView.setText(url);
            if (ip == null) {
                // 没有局域网地址时，二维码指向 127.0.0.1，手机扫了也没用
                qrView.setVisibility(View.GONE);
            } else {
                updateQr(url);
            }
            statusView.setText(ip == null
                    ? "没有检测到局域网地址，请确认电视已连接网络"
                    : "等待浏览器连接…");
            Log.i(TAG, "remote import opened: " + url);
        } else {
            urlView.setText("启动失败");
            qrView.setVisibility(View.GONE);
            statusView.setText("端口 8080-8089 都被占用，请重试");
            Log.w(TAG, "remote import server start failed");
        }
        backButton.post(new Runnable() {
            @Override
            public void run() {
                backButton.requestFocus();
            }
        });
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        if (server != null) {
            if (activity.isRemoteKeepRunning()) {
                // 开着「保持服务运行」：只关页面，HTTP 服务继续跑
                Log.i(TAG, "remote import closed, server kept on port " + server.getPort());
            } else {
                server.stop();
            }
        }
        module.setVisibility(View.GONE);
        activity.remotePageExitFullscreen();
        Log.i(TAG, "remote import closed");
    }

    void setStatus(String message) {
        statusView.setText(message);
    }

    void setKeepChecked(boolean keep) {
        keepSwitch.setChecked(keep);
    }

    /** 停掉 HTTP 服务但保留实例；下次打开页面会重新 start */
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** Activity 销毁时停掉可能还在后台跑的服务 */
    void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** 把当前地址编码成二维码，遥控器扫不到也没关系，地址仍然在下面。 */
    private void updateQr(String url) {
        try {
            QrCode.Symbol symbol = QrCode.encode(url, QrCode.EC_M);
            // 静区只留 2 个模块（标准建议 4），把省下的空间给内容区；外侧白卡片再补一点边
            final int quiet = 2;
            int scale = QrCode.scaleFor(symbol, activity.dp(264), quiet);
            int[] pixels = QrCode.renderPixels(symbol, quiet, scale, 0xFF000000, 0xFFFFFFFF);
            int px = symbol.size + quiet * 2;
            px *= scale;
            Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, px, 0, 0, px, px);
            qrView.setImageBitmap(bitmap);
            qrView.setVisibility(View.VISIBLE);
            Log.i(TAG, "remote import qr: v" + symbol.version + " " + px + "px mask=" + symbol.mask);
        } catch (RuntimeException e) {
            qrView.setVisibility(View.GONE);
            Log.w(TAG, "remote import qr failed: " + e.getMessage());
        }
    }

    /** 取第一个非回环 IPv4，优先 wlan/eth 网卡 */
    private static String localIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName();
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("eth")) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
