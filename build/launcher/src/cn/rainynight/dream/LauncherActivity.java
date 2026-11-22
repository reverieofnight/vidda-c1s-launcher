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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

/**
 * 极简 TV 桌面
 * - 左上角时钟
 * - 底部一排"最近使用"
 * - 按 ↓ 滑出应用抽屉（7 列网格），返回键或第一行再按 ↑ 收起
 */
public class LauncherActivity extends Activity {

    static final String PREFS = "launcher_prefs";
    /** 背景设置：转场后隐藏 UI 壁纸，显示 4K 视频层 */
    private static final String KEY_REVEAL_4K = "reveal_4k_video";
    private static final String KEY_4K_BACKGROUND = "current_4k_background";
    /** 桌面设置：图标大小预设（small / medium / large） */
    private static final String KEY_ICON_SIZE = "icon_size";
    static final String ICON_SIZE_SMALL = "small";
    static final String ICON_SIZE_MEDIUM = "medium";
    static final String ICON_SIZE_LARGE = "large";
    /** 桌面控件：时钟 / 天气 / Dock / 壁纸切换按钮 / 抽屉各自独立开关 */
    static final String KEY_WIDGET_CLOCK = "widget_clock";
    static final String KEY_WIDGET_WEATHER = "widget_weather";
    static final String KEY_WIDGET_DOCK = "widget_dock";
    static final String KEY_WIDGET_WALLPAPER_ARROWS = "widget_wallpaper_arrows";
    static final String KEY_WIDGET_DRAWER = "widget_drawer";
    /** 桌面控件显示方式：always 常驻 / hidden 隐藏 / auto 自动隐藏 */
    static final String KEY_WIDGET_MODE = "widget_mode";
    static final String WIDGET_MODE_ALWAYS = "always";
    static final String WIDGET_MODE_HIDDEN = "hidden";
    static final String WIDGET_MODE_AUTO = "auto";
    /** 新视频启动后给 4K 视频 plane 的稳定时间，再开始 UI 层渐隐 */
    private static final long FRAME_SETTLE_MS = 1000L;

    private static final String TAG = "DSH_LAUNCHER";
    /** 厂商天气 App 导出的实时天气（只读，不需要网络权限） */
    private static final Uri WEATHER_URI =
            Uri.parse("content://com.hisense.tv.weather/real_time_info");
    private static final long WEATHER_REFRESH_MS = 30 * 60 * 1000L;

    /** 背景轮播间隔 / 交叉淡化时长（目录与文件规则见 WallpaperStore） */
    private static final long BACKGROUND_INTERVAL_MS = 30 * 1000L;
    private static final long BG_FADE_MS = 1200L;
    private static final String KEY_SLIDESHOW = "slideshow_enabled";
    private static final String KEY_SLIDESHOW_SEC = "slideshow_interval_sec";
    private static final String KEY_SLIDESHOW_FADE = "slideshow_fade";
    /** 幻灯片播放顺序：false=顺序，true=随机 */
    private static final String KEY_SLIDESHOW_RANDOM = "slideshow_random";
    private static final String KEY_VIDEO_MUTED = "video_muted";
    /** 远程导入：关闭页面后是否继续保持 HTTP 服务；默认关闭 */
    private static final String KEY_REMOTE_KEEP = "remote_keep_running";
    private static final int[] SLIDESHOW_STEPS = {10, 30, 60, 300, 600, 1800};
    /** UI 壁纸相对视频 plane 的 1px 对齐微调 */
    static final float WALLPAPER_NUDGE_X = 1f;
    static final float WALLPAPER_NUDGE_Y = 1f;

    /** 已弹过一次写权限申请，避免每次启动都打扰 */
    private static final String KEY_WRITE_ASKED = "write_permission_asked";
    private static final int REQ_WRITE_STORAGE = 4102;
    private static final String KEY_VIDEO_BACKGROUND = "current_video_background";
    private static final String KEY_WALLPAPER_TYPE = "wallpaper_type";
    /** 用户主动删掉当前壁纸后置为「无壁纸（黑屏）」，直到重新设置壁纸 */
    private static final String KEY_WALLPAPER_CLEARED = "wallpaper_cleared";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";

    /** 内置壁纸：打包在 assets/backgrounds/ 下，直接从 APK 读取，不写入壁纸目录 */
    private static final String BUILTIN_ASSET = "backgrounds/bg_01.jpg";
    /** 内置壁纸在壁纸库中的文件名（管理页显示这个名字，并加「内置」角标） */
    private static final String BUILTIN_WALLPAPER = "月海云境.jpg";
    /** 内置壁纸项：4K 列表、1080p 兜底列表、最后的兜底渲染共用同一份 */
    private static final BackgroundItem BUILTIN_ITEM = new BackgroundItem(
            BUILTIN_WALLPAPER, BUILTIN_ASSET, null, 0L, true);
    static final int FOUR_K_W = 3840;
    static final int FOUR_K_H = 2160;
    static final int FOUR_K_ENCODE_FRAMES = 3;
    /** 首帧渲染回调后给合成器一点时间，避免揭盖瞬间黑一帧 */
    static final long FOUR_K_FRAME_SETTLE_MS = 150L;
    /** 静态壁纸首帧上屏后，等后续几帧覆盖掉解码器切换瞬间的错帧再揭盖 */
    static final long FOUR_K_STATIC_SETTLE_MS = 350L;
    /** 静态壁纸首帧上屏后，再过这么久主动暂停，把这一帧留在 surface 上 */
    static final long FOUR_K_PAUSE_AFTER_MS = 700L;
    /** 硬切模式：UI 预览画出后 100ms 再切视频，避免点击瞬间黑 */
    private static final long FOUR_K_VIDEO_SWITCH_MS = 100L;
    /** 视频层延迟启动：UI 立即换图，用户停止操作这么久后才加载视频，避免频繁重配闪屏 */
    static final long FOUR_K_SWITCH_DEBOUNCE_MS = 2000L;
    /** UI 和 4K 视频使用同一个 overscan，避免一层放大一层不放大 */
    static final float WALLPAPER_OVERSCAN = 1.002f;

    /** 打开/关闭动画时长（抽屉与桌面组件保持一致） */
    static final long OPEN_MS = 400;
    static final long CLOSE_MS = 320;
    /** 菜单按钮 60×60dp；横条高度 = 按钮高度 + 上下内边距 */
    static final int MENU_ITEM_WIDTH_DP = 60;
    static final int MENU_ITEM_HEIGHT_DP = 60;
    static final int MENU_PADDING_V_DP = 6;
    /** 横条本体高度（按钮 + 上下等距留白） */
    static final int MENU_BAR_HEIGHT_DP = MENU_ITEM_HEIGHT_DP + MENU_PADDING_V_DP * 2;
    /** 底边浅色描边高度，画在横条下方，不占按钮下边距 */
    static final int MENU_BORDER_DP = 2;
    /** 菜单模块总高度 = 横条 + 底部描边 */
    static final int MENU_HEIGHT_DP = MENU_BAR_HEIGHT_DP + MENU_BORDER_DP;
    /** 菜单左侧留白：上下边距的 2 倍（6dp → 12dp） */
    static final int MENU_PADDING_H_DP = MENU_PADDING_V_DP * 2;
    static final long MENU_ANIM_MS = 300;
    /** Material 标准缓动：进入减速、退出加速 */
    static final PathInterpolator OPEN_EASE =
            new PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f);
    static final PathInterpolator CLOSE_EASE =
            new PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f);
    /** 背景切换的淡入淡出缓动 */
    private static final PathInterpolator BG_EASE =
            new PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f);

    static boolean x264Available;
    static {
        try {
            System.loadLibrary("x264jni");
            x264Available = true;
        } catch (Throwable t) {
            Log.w("DSH_LAUNCHER", "x264jni unavailable", t);
        }
    }

    static native byte[] encodeI4204K(byte[] yuv, int width, int height, int frames);

    /** 应用列表仓储：扫描结果供抽屉与 Dock 共用 */
    private AppRepository appRepository;
    /** 壁纸文件仓储：目录解析 / 扫描 / 复制入库 / 迁移 */
    private WallpaperStore store;
    /** 壁纸位图解码：原图 / 4K 裁切 / 缩略图 / 视频取帧 */
    private BackgroundImageLoader imageLoader;
    /** 壁纸导入流程：本机 / USB 浏览与入库 */
    private WallpaperImporter importer;
    /** 壁纸缓存：4K 转码缓存与缩略图磁盘缓存 */
    private WallpaperCaches caches;
    /** 最近使用：打开时间持久化与 Dock 排序 */
    private RecentApps recentApps;
    /** 壁纸清单：目录扫描与自定义排序 */
    private WallpaperCatalog catalog;
    /** 应用图标渲染：裁边、圆角、Dock 阴影与缓存 */
    private IconRenderer icons;

    /** 桌面大层：壁纸两层 / 视频层 / 过渡层 / 遮罩 */
    private DesktopLayer desktopLayer;
    private FrameLayout desktopModule;
    private FrameLayout homeLayer;
    /** 应用抽屉 */
    private AppDrawer drawer;
    private Bitmap wallpaperBlurBitmap;
    private String wallpaperBlurKey;
    /** 桌面控件层：时钟 / 天气 / Dock / 壁纸左右键 / 提示 */
    private DesktopWidgets widgets;
    private boolean reveal4KVideo = true;
    private Bitmap currentUiSnapshot;
    private Runnable pendingRevealRunnable;
    private final List<BackgroundItem> backgroundItems = new ArrayList<>();
    private final List<BackgroundItem> fourKItems = new ArrayList<>();
    private int fourKIndex = -1;
    /** 4K 静态壁纸播放器：编码、缓存、播放与首帧状态 */
    private FourKStaticPlayer fourKStatic;
    private String current4KName;
    private final List<BackgroundItem> videoItems = new ArrayList<>();
    private int videoIndex = -1;
    private String currentVideoName;
    private boolean videoWallpaperMode;
    private boolean videoSurfaceLost;
    /** 动态视频壁纸播放器：播放/暂停/看门狗/surface 重建 */
    private VideoWallpaperPlayer videoPlayer;
    /** 桌面是否在前台：后台时导入结束不要偷偷恢复视频 */
    private boolean uiForeground = true;
    private boolean cacheCleanupStarted;
    private View startupBlackout;
    private boolean startupRevealed;
    private boolean startupAppScanDone;
    /** 后台重扫应用列表是否进行中（避免重复起线程） */
    private boolean appScanRunning;
    /** 1080p 背景轮播控制器 */
    private SlideshowController slideshow;
    /** 顶部菜单条 */
    private DesktopMenu menu;
    /** 从左右键打开菜单/抽屉时，关闭后把焦点还回去 */
    private boolean returnFocusToArrows;

    /** 壁纸管理页（全屏浮层） */
    private WallpaperManagerPage wallpaperManager;
    /** 缩略图缓存：key = 文件名|stamp；按访问顺序保留最近 64 张，壁纸再多也不会无限涨内存 */
    private final Map<String, Bitmap> thumbnailCache =
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 64;
                }
            };

    /** 桌面设置页（全屏浮层） */
    private DesktopSettingsPage settingsPage;
    /** 远程导入页（局域网 HTTP 上传，全屏浮层，盖在管理页上面） */
    private RemoteImportPage remotePage;
    /** 当前图标大小预设：small / medium / large */
    private String iconSizePreset = ICON_SIZE_MEDIUM;

    private final Handler handler = new Handler();
    private boolean drawerOpen;
    /** 桌面上按 HOME/返回取消选中后，避免 onResume 又自动聚焦第一个图标 */
    private boolean desktopFocusCleared;
    /** 取消选中时承接焦点的透明空控件 */
    private View focusSink;
    private boolean firstResume = true;
    int screenHeight;

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 当前图标边长（dp）：小 40 / 中 50 / 大 60 */
    int iconSizeDp() {
        if (ICON_SIZE_SMALL.equals(iconSizePreset)) {
            return 40;
        }
        if (ICON_SIZE_LARGE.equals(iconSizePreset)) {
            return 60;
        }
        return 50;
    }

    /** Dock 图标数量与抽屉每行列数：小 8 / 中 7 / 大 6 */
    int iconsPerRow() {
        if (ICON_SIZE_SMALL.equals(iconSizePreset)) {
            return 8;
        }
        if (ICON_SIZE_LARGE.equals(iconSizePreset)) {
            return 6;
        }
        return 7;
    }

    /** 图标外层遮罩边长（dp）：比图标大一圈，保持正方形 */
    int iconMaskDp() {
        return iconSizeDp() + 12;
    }

    /** 应用名称字号随图标尺寸缩放：小 10 / 中 11 / 大 12sp */
    float iconLabelSp() {
        if (ICON_SIZE_SMALL.equals(iconSizePreset)) {
            return 10f;
        }
        if (ICON_SIZE_LARGE.equals(iconSizePreset)) {
            return 12f;
        }
        return 11f;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new WallpaperStore(this);
        imageLoader = new BackgroundImageLoader(this);
        importer = new WallpaperImporter(this, store);
        caches = new WallpaperCaches(this);
        catalog = new WallpaperCatalog(this, store, BUILTIN_ITEM);
        icons = new IconRenderer(this);
        fourKStatic = new FourKStaticPlayer(this);
        videoPlayer = new VideoWallpaperPlayer(this);
        slideshow = new SlideshowController(this);
        appRepository = new AppRepository(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        reveal4KVideo = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_REVEAL_4K, true);
        iconSizePreset = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ICON_SIZE, ICON_SIZE_MEDIUM);

        recentApps = new RecentApps(this, appRepository);
        recentApps.load();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ---- 桌面大层：壁纸两层 + 视频层 + 过渡层 + 遮罩 ----
        desktopLayer = new DesktopLayer(this);
        desktopModule = desktopLayer.getModule();
        root.addView(desktopModule, matchParent());

        // surface 生命周期仍由 Activity 转发给动态视频与 4K 播放器
        desktopLayer.video().getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "video surface created");
                if (!videoPlayer.onSurfaceCreated() && videoSurfaceLost) {
                    // 4K 静态壁纸的补播（含重试次数上限）由 FourKStaticPlayer 处理
                    fourKStatic.handleSurfaceRecreated();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "video surface destroyed");
                videoSurfaceLost = true;
                // surface 没了，旧画面就不在了：重置 4K 状态，返回时才会重新 prepare
                fourKStatic.onSurfaceDestroyed();
            }
        });

        // 有写权限时先把旧的私有壁纸迁到公共目录，再扫描
        ensurePublicWallpaperAccess();
        removeLegacyBuiltinCopy();
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        cleanup4KCacheAsync();

        // ---- 桌面控件层：时钟 / 天气 / 底部 Dock / 壁纸左右键 / 提示 ----
        widgets = new DesktopWidgets(this);
        homeLayer = widgets.getLayer();
        desktopLayer.addLayer(homeLayer);

        // ---- 应用抽屉：毛玻璃底层 + 应用网格 ----
        drawer = new AppDrawer(this, appRepository);
        desktopLayer.addLayer(drawer.getView());

        // ---- 顶部菜单模块：横条 + 底部浅色描边，整体从屏幕外挤入 ----
        menu = new DesktopMenu(this);
        root.addView(menu.getView(), menu.layoutParams());

        // 壁纸管理页：全屏浮层，盖住菜单和桌面
        wallpaperManager = new WallpaperManagerPage(this);
        root.addView(wallpaperManager.getView(), matchParent());

        // 远程导入页：局域网 HTTP 上传，盖在管理页上面
        remotePage = new RemoteImportPage(this);
        root.addView(remotePage.getView(), matchParent());

        // 桌面设置页：全屏浮层
        settingsPage = new DesktopSettingsPage(this);
        root.addView(settingsPage.getView(), matchParent());

        startupBlackout = new View(this);
        startupBlackout.setBackgroundColor(Color.BLACK);
        startupBlackout.setClickable(false);
        startupBlackout.setFocusable(false);
        root.addView(startupBlackout, matchParent());

        setContentView(root);
        // 第一帧先画 1080p 桌面；之后再初始化 4K，避免冷启动黑屏
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh4KStaticMode();
                    }
                });
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        revealStartupFromBlack();
                    }
                }, 4000);
                return true;
            }
        });
        refreshClock();
        refreshDock();
        // 启动后不聚焦任何图标：焦点先交给透明焦点位，等用户第一次按键再落到第一个应用
        clearDesktopFocus();
        loadPlaybackSettings();
        restoreWallpaperMode();
        handleDebugWallpaperIntent(getIntent());
        if (getIntent() != null && getIntent().getBooleanExtra("wallpaper_manager", false)) {
            if ("video".equals(getIntent().getStringExtra("manager_tab"))) {
                setManagerType(WallpaperManagerPage.VIDEOS);
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    openWallpaperManager();
                    if (getIntent().getBooleanExtra("remote_import", false)) {
                        openRemoteImport();
                    }
                }
            }, 1200);
        }
    }

    // ---------- 动态壁纸（视频文件，循环播放） ----------

    /** 首次启动申请写权限；已有权限则先把私有目录里的壁纸迁到公共目录 */
    private void ensurePublicWallpaperAccess() {
        if (store.canWritePublic()) {
            store.migratePrivateToPublic();
            return;
        }
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WRITE_ASKED, false)) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WRITE_ASKED, true).apply();
        requestPermissions(new String[]{
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                store.migratePrivateToPublic();
                reload4KBackgrounds();
                reloadVideoBackgrounds();
                refresh4KStaticMode();
                Toast.makeText(this, "壁纸已保存到公共目录，卸载不会丢失", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        importer.onRequestPermissionsResult(requestCode, grantResults);
    }

    private void reloadVideoBackgrounds() {
        List<BackgroundItem> found = catalog.scanVideos();
        if (WallpaperCatalog.sameItems(videoItems, found)) {
            return;
        }
        String keep = currentVideoName;
        if (keep == null) {
            keep = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_VIDEO_BACKGROUND, null);
        }
        videoItems.clear();
        videoItems.addAll(found);
        int index = 0;
        if (keep != null) {
            for (int i = 0; i < videoItems.size(); i++) {
                if (keep.equals(videoItems.get(i).name)) {
                    index = i;
                    break;
                }
            }
        }
        videoIndex = videoItems.isEmpty() ? -1 : index;
        currentVideoName = videoItems.isEmpty() ? null : videoItems.get(videoIndex).name;
        if (isWallpaperCleared()) {
            videoIndex = -1;
            currentVideoName = null;
        }
        Log.i(TAG, "video background items: " + videoItems.size());
    }

    /** 启动时按上次记住的类型恢复壁纸：视频优先，否则走 4K 静态 / 1080p */
    private void restoreWallpaperMode() {
        if (isWallpaperCleared()) {
            // 上次是「删除当前壁纸后黑屏」的状态，不要自动挑一张
            videoWallpaperMode = false;
            stopVideoWallpaper();
            refresh4KStaticMode();
            return;
        }
        String type = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_WALLPAPER_TYPE, TYPE_IMAGE);
        if (TYPE_VIDEO.equals(type) && !videoItems.isEmpty()) {
            videoWallpaperMode = true;
            if (videoIndex < 0 || videoIndex >= videoItems.size()) {
                videoIndex = 0;
            }
            currentVideoName = videoItems.get(videoIndex).name;
            Log.i(TAG, "restore video wallpaper: " + currentVideoName);
            refreshVideoWallpaperMode();
            return;
        }
        videoWallpaperMode = false;
        refresh4KStaticMode();
    }

    // ---------- 动态视频壁纸（VideoWallpaperPlayer）宿主 ----------

    private void refreshVideoWallpaperMode() {
        videoPlayer.refresh();
    }

    /** 停止视频并收起视频层 */
    private void stopVideoWallpaper() {
        videoPlayer.stop();
    }

    /** 远程导入结束：把为传输让路而暂停的视频从原位置续播 */
    private void resumeVideoAfterTransfer() {
        videoPlayer.resumeAfterTransfer();
    }

    /** 回到前台：能续播就续播，surface 丢了就重新 prepare */
    private void restoreVideoWallpaperOnForeground() {
        videoPlayer.restoreOnForeground();
    }

    /**
     * 调试入口（不进 UI、绕开遥控器焦点）：
     * --ez apply_video Ocean.mp4   直接应用指定视频壁纸
     * --ez apply_image 6.jpg       直接应用指定图片壁纸
     * --ez video_state true        立刻打一行视频状态
     */
    private void handleDebugWallpaperIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String video = intent.getStringExtra("apply_video");
        if (video != null && WallpaperStore.isVideoName(video)) {
            File file = new File(store.videoDir(), video);
            if (file.exists()) {
                Log.i(TAG, "debug: apply video " + video);
                reloadVideoBackgrounds();
                applyVideoByName(video);
            } else {
                Log.w(TAG, "debug: video not found " + video);
            }
        }
        String image = intent.getStringExtra("apply_image");
        if (image != null && WallpaperStore.isImageName(image)) {
            Log.i(TAG, "debug: apply image " + image);
            reloadBackgrounds();
            reload4KBackgrounds();
            applyWallpaperByName(image);
        }
        if (intent.getBooleanExtra("video_state", false)) {
            videoPlayer.requestHealthLog();
        }
    }

    void applyVideoByName(String name) {
        int index = -1;
        for (int i = 0; i < videoItems.size(); i++) {
            if (videoItems.get(i).name.equals(name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        videoIndex = index;
        currentVideoName = name;
        videoWallpaperMode = true;
        videoPlayer.resetRetries();
        setWallpaperCleared(false);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_VIDEO_BACKGROUND, name)
                .putString(KEY_WALLPAPER_TYPE, TYPE_VIDEO).apply();
        refreshVideoWallpaperMode();
        refreshManager();
        keepManagerTabFocus(index);
        Log.i(TAG, "video wallpaper applied: " + name);
    }

    private void switchVideoBy(int direction) {
        if (videoItems.isEmpty()) {
            return;
        }
        if (videoIndex < 0) {
            videoIndex = 0;
        }
        int next = (videoIndex + direction + videoItems.size()) % videoItems.size();
        videoIndex = next;
        currentVideoName = videoItems.get(next).name;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_VIDEO_BACKGROUND, currentVideoName).apply();
        videoPlayer.play(new File(videoItems.get(next).filePath));
        Log.i(TAG, "video wallpaper switch -> " + currentVideoName);
    }

    /** 放弃视频模式，回到静态壁纸（图片） */
    private void leaveVideoWallpaperMode() {
        if (!videoWallpaperMode) {
            return;
        }
        videoWallpaperMode = false;
        stopVideoWallpaper();
        bgFrontView().setVisibility(View.VISIBLE);
        bgBackView().setVisibility(View.VISIBLE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        // 播放视频时 4K 静态模式被关掉了；这里必须重新进入图片模式，
        // 否则 applyWallpaperByName/switch4KStatic 都会因为模式未开启直接 return，
        // 表现为「换了壁纸但画面不变」。
        refresh4KStaticMode();
    }

    // ---------- 4K 静态壁纸（设备端 x264 单帧编码） ----------

    WallpaperCaches caches() {
        return caches;
    }

    IconRenderer icons() {
        return icons;
    }

    boolean fourKStaticEnabled() {
        return reveal4KVideo && x264Available && !fourKItems.isEmpty();
    }

    /** 后台清理：图片已删除或已变更后遗留的 4K 缓存 */
    private void cleanup4KCacheAsync() {
        if (cacheCleanupStarted) {
            return;
        }
        cacheCleanupStarted = true;
        caches.cleanupFourK(new ArrayList<>(fourKItems));
    }

    /**
     * 内置壁纸现在直接从 APK assets 读，不占用壁纸目录。
     * 早期版本曾把它复制到壁纸库，这里把那份遗留副本删掉（大小一致才删，
     * 避免误删用户自己上传的同名图片）。
     */
    private void removeLegacyBuiltinCopy() {
        File legacy = new File(store.staticDir(), BUILTIN_WALLPAPER);
        if (!legacy.isFile()) {
            return;
        }
        long assetBytes = assetLength(BUILTIN_ASSET);
        if (assetBytes > 0 && legacy.length() != assetBytes) {
            return;
        }
        if (legacy.delete()) {
            Log.i(TAG, "legacy builtin wallpaper copy removed");
        }
    }

    private long assetLength(String assetPath) {
        InputStream in = null;
        try {
            in = getAssets().open(assetPath);
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
            }
            return total;
        } catch (Throwable t) {
            Log.w(TAG, "read asset length failed: " + assetPath, t);
            return -1;
        } finally {
            BackgroundImageLoader.closeQuietly(in);
        }
    }

    private void reload4KBackgrounds() {
        List<BackgroundItem> found = catalog.scanStill();
        if (WallpaperCatalog.sameItems(fourKItems, found)) {
            return;
        }
        String keep = current4KName;
        if (keep == null) {
            keep = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_4K_BACKGROUND, null);
        }
        fourKItems.clear();
        fourKItems.addAll(found);
        int index = 0;
        if (keep != null) {
            for (int i = 0; i < fourKItems.size(); i++) {
                if (keep.equals(fourKItems.get(i).name)) {
                    index = i;
                    break;
                }
            }
        }
        fourKIndex = fourKItems.isEmpty() ? -1 : index;
        current4KName = fourKItems.isEmpty() ? null : fourKItems.get(fourKIndex).name;
        if (isWallpaperCleared()) {
            fourKIndex = -1;
            current4KName = null;
        }
        Log.i(TAG, "4K background items: " + fourKItems.size());
    }

    boolean isWallpaperCleared() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_WALLPAPER_CLEARED, false);
    }

    private void setWallpaperCleared(boolean cleared) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_WALLPAPER_CLEARED, cleared).apply();
    }

    /** 把两层 UI 壁纸都撤掉，露出桌面根容器的黑色背景 */
    void clearWallpaperToBlack() {
        slideshow.stopTimer();
        if (bgFrontView() != null) {
            bgFrontView().setImageDrawable(null);
            bgFrontView().setVisibility(View.INVISIBLE);
        }
        if (bgBackView() != null) {
            bgBackView().setImageDrawable(null);
            bgBackView().setVisibility(View.INVISIBLE);
        }
    }

    private void refresh4KStaticMode() {
        if (isWallpaperCleared()) {
            // 当前壁纸被删掉：不再自动挑一张，保持黑屏
            fourKStatic.setMode(false);
            fourKStatic.reset();
            slideshow.stopTimer();
            fourKStatic.stop();
            clearWallpaperToBlack();
            return;
        }
        if (videoWallpaperMode) {
            return;
        }
        if (wallpaperVideoView() == null) {
            return;
        }
        if (!fourKStaticEnabled()) {
            fourKStatic.setMode(false);
            fourKStatic.reset();
            fourKStatic.stop();
            if (bgFrontView() != null) {
                bgFrontView().setVisibility(View.VISIBLE);
                bgBackView().setVisibility(View.VISIBLE);
            }
            resetBgTimer();
            preloadNextBackground();
            precomputeWallpaperBlur();
            revealStartupFromBlack();
            return;
        }
        fourKStatic.setMode(true);
        // 这里以前会 removeCallbacks(bgTick) 且不再续期，导致进 4K 模式后幻灯片永远不触发
        show4KCurrent();
        ensureBgTimer();
    }

    private void show4KCurrent() {
        if (!fourKStatic.isMode() || fourKItems.isEmpty() || isWallpaperCleared()) {
            return;
        }
        if (fourKIndex < 0 || fourKIndex >= fourKItems.size()) {
            fourKIndex = 0;
        }
        BackgroundItem item = fourKItems.get(fourKIndex);
        // 同一张图已经在播（或正在准备）就不重来：从其他应用返回时 onResume 和
        // onNewIntent 会各调一次，重复重播会让视频层多黑 1 秒多。
        if (item.name.equals(current4KName)
                && (fourKStatic.isReady() || fourKStatic.isPreparing())) {
            Log.i(TAG, "4K static already shown, skip replay");
            return;
        }
        Log.i(TAG, "show4KCurrent " + item.name + " ready=" + fourKStatic.isReady()
                + " preparing=" + fourKStatic.isPreparing() + " surfaceLost=" + videoSurfaceLost);
        current4KName = item.name;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_4K_BACKGROUND, item.name).apply();
        // 标记"准备中"必须同步落下：视频层在防抖窗口结束后才真正启动，
        // 这期间 onResume/onNewIntent 的第二次调用不能又排一遍。
        fourKStatic.resetForShow();
        show4KPreview(item);
        // 防抖：连续切图时取消上一次尚未启动的视频，只播最后停在的那张，
        // 避免快速点按导致多次 setVideoPath 抢表面，出现旧帧闪回。
        fourKStatic.scheduleStart();
    }

    private void show4KPreview(BackgroundItem item) {
        fourKStatic.markPreviewShown();
        // 与 1080p 壁纸共用同一个解码缓存：同一张图只解码一次
        Bitmap bitmap = loadBackground(item);
        if (bitmap != null) {
            bgFrontView().setImageBitmap(bitmap);
            bgFrontView().setAlpha(1f);
            bgBackView().setAlpha(0f);
            slideshow.adoptCurrent(fourKIndex, item.name);
            wallpaperBlurKey = null;

            transitionOldView().animate().cancel();
            transitionNewView().animate().cancel();
            transitionOldView().setImageDrawable(null);
            transitionOldView().setAlpha(0f);
            transitionNewView().setImageBitmap(bitmap);
            transitionNewView().setAlpha(1f);
            transitionLayerView().setAlpha(1f);
            transitionLayerView().setVisibility(View.VISIBLE);
        }
        fourKStatic.markUiAnimationDone(true);
        maybeHide4KUiCover();
        // 预览图已经就绪，先淡出启动黑幕，不等 4K 首帧
        revealStartupFromBlack();
    }

    private void set4KStaticEnabled(boolean enabled) {
        leaveVideoWallpaperMode();
        if (reveal4KVideo == enabled) {
            refresh4KStaticMode();
            return;
        }
        reveal4KVideo = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_REVEAL_4K, enabled).apply();
        Log.i(TAG, "4K static wallpaper = " + enabled);
        relayoutDesktopForMenu();
        refresh4KStaticMode();
    }

    private void switch4KStatic(int direction) {
        switch4KStatic(direction, false);
    }

    void switch4KStatic(int direction, boolean random) {
        if (!fourKStaticEnabled() || fourKItems.isEmpty()) {
            return;
        }
        int size = fourKItems.size();
        if (size < 2) {
            return;
        }
        int next;
        if (random) {
            next = new java.util.Random().nextInt(size - 1);
            if (next >= fourKIndex) {
                next++;
            }
        } else {
            next = (fourKIndex + direction + size) % size;
        }
        if (next == fourKIndex) {
            return;
        }
        // UI 立即换图；视频层交给 FourKStaticPlayer 延迟加载，期间再切图会重置计时
        fourKIndex = next;
        fourKStatic.resetEncoding();
        fourKStatic.invalidate();
        show4KCurrent();
    }

    /** 当前 4K 壁纸已编码好的缓存文件，没有则返回 null */
    File current4KCacheOrNull() {
        if (current4KName == null) {
            return null;
        }
        for (BackgroundItem item : fourKItems) {
            if (item.name.equals(current4KName)) {
                File cache = caches.fourKCacheFile(item);
                return cache.exists() && cache.length() > 0 ? cache : null;
            }
        }
        return null;
    }

    /** 视频层在准备期间是空的黑 surface，用当前静态壁纸铺一层封面挡住 */
    void showVideoCover() {
        if (transitionLayerView() == null || transitionNewView() == null) {
            return;
        }
        if (transitionLayerView().getVisibility() == View.VISIBLE
                && transitionNewView().getDrawable() != null) {
            return;
        }
        Bitmap cover = currentImageCoverBitmap();
        if (cover == null) {
            return;
        }
        if (transitionOldView() != null) {
            transitionOldView().setImageDrawable(null);
        }
        transitionNewView().setImageBitmap(cover);
        transitionNewView().setAlpha(1f);
        transitionLayerView().setAlpha(1f);
        transitionLayerView().setVisibility(View.VISIBLE);
        Log.i(TAG, "video cover shown");
    }

    void hideVideoCover() {
        if (transitionLayerView() == null || transitionNewView() == null) {
            return;
        }
        if (transitionNewView().getDrawable() == null) {
            return;
        }
        transitionLayerView().setVisibility(View.GONE);
        transitionOldView().setImageDrawable(null);
        transitionNewView().setImageDrawable(null);
        Log.i(TAG, "video cover hidden");
    }

    /** 当前正在展示的静态壁纸位图（4K 图优先），失败返回 null */
    private Bitmap currentImageCoverBitmap() {
        try {
            if (!fourKItems.isEmpty()) {
                int idx = Math.max(fourKIndex, 0);
                if (idx < fourKItems.size()) {
                    BackgroundItem item = fourKItems.get(idx);
                    Bitmap bitmap = loadBackground(item);
                    if (bitmap != null) {
                        return bitmap;
                    }
                }
            }
            if (!backgroundItems.isEmpty()) {
                int idx = Math.max(slideshow.currentIndex(), 0) % backgroundItems.size();
                return loadBackground(backgroundItems.get(idx));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover bitmap failed", t);
        }
        return null;
    }

    void maybeHide4KUiCover() {
        if (fourKStatic.isMode() && fourKStatic.isUiAnimationDone()
                && fourKStatic.isReady()) {
            hide4KUiCover();
        }
    }

    /** 首帧或预览图就绪后移除启动黑幕，并触发后台应用扫描 */
    void revealStartupFromBlack() {
        if (startupRevealed || startupBlackout == null) {
            return;
        }
        startupRevealed = true;
        startupBlackout.animate().cancel();
        startupBlackout.setAlpha(0f);
        startupBlackout.setVisibility(View.GONE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                loadAppsDeferred();
            }
        }, 300);
        Log.i(TAG, "startup blackout removed (no fade)");
    }

    /** 无动画硬切：直接移除 UI 覆盖层，露出已经稳定显示的 4K 视频帧 */
    private void hide4KUiCover() {
        if (transitionLayerView() == null
                || transitionLayerView().getVisibility() != View.VISIBLE) {
            return;
        }
        if (transitionLayerView() != null) {
            transitionLayerView().animate().cancel();
            transitionLayerView().setVisibility(View.GONE);
            transitionLayerView().setAlpha(1f);
            transitionOldView().setImageDrawable(null);
            transitionNewView().setImageDrawable(null);
        }
        if (wallpaperVideoView() != null) {
            wallpaperVideoView().setVisibility(View.VISIBLE);
        }
        Log.i(TAG, "4K UI cover hidden (hard cut)");
        revealStartupFromBlack();
        // 画面已经清晰，趁空闲把下一张预编码好，用户切过去时可直接命中缓存
        fourKStatic.prefetch();
    }

    /** 后台预编码下一张 4K 壁纸；由 FourKStaticPlayer 保证同一时刻只有一个编码任务 */
    int[] targetSizeFor(BackgroundItem item) {
        if (imageLoader.readImageBounds(item) == null) {
            return null;
        }
        // 固定 4K 画布：所有壁纸编码尺寸一致，视频 plane 切换时不需要重配缩放/色彩，
        // 避免首帧按上一张的配置显示（放大、偏蓝）。比例不同的图在 decode4KBitmap 里居中裁切。
        return new int[]{FOUR_K_W, FOUR_K_H};
    }

    Bitmap decode4KBitmap(BackgroundItem item, int targetW, int targetH) {
        return imageLoader.decode4KBitmap(item, targetW, targetH);
    }

    static byte[] bitmapToI420(Bitmap bitmap, int width, int height) {
        byte[] yuv = new byte[width * height * 3 / 2];
        int[] row = new int[width];
        int uOff = width * height;
        int vOff = uOff + (width / 2) * (height / 2);
        int uvRowSize = width / 2;
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            int uvRow = (y / 2) * uvRowSize;
            for (int x = 0; x < width; x++) {
                int c = row[x];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                // BT.709 limited range，与 4K 视频的 sRGB/BT.709 显示保持一致
                int yv = ((47 * r + 157 * g + 16 * b + 128) >> 8) + 16;
                yuv[y * width + x] = (byte) clampByte(yv);
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 102 * g - 10 * b + 128) >> 8) + 128;
                    int uvIndex = uvRow + x / 2;
                    yuv[uOff + uvIndex] = (byte) clampByte(u);
                    yuv[vOff + uvIndex] = (byte) clampByte(v);
                }
            }
        }
        return yuv;
    }

    private static int clampByte(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    static void write4KMp4(File file, byte[] data, int width, int height) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        int spsLen = bb.getInt();
        byte[] sps = new byte[spsLen];
        bb.get(sps);
        int ppsLen = bb.getInt();
        byte[] pps = new byte[ppsLen];
        bb.get(pps);
        int frameCount = bb.getInt();
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            int len = bb.getInt();
            byte[] frame = new byte[len];
            bb.get(frame);
            frames.add(frame);
        }

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        // 帧率必须够高：解码器/显示 plane 在切换尺寸后，第一帧可能按旧缩放/旧色彩展示，
        // 只有 1fps 时这张错帧会停留近 1 秒；30fps 下第二帧 33ms 后就覆盖掉它
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setByteBuffer("csd-0", withStartCode(sps));
        format.setByteBuffer("csd-1", withStartCode(pps));

        file.getParentFile().mkdirs();
        if (file.exists()) {
            file.delete();
        }
        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean started = false;
        try {
            int track = muxer.addTrack(format);
            muxer.start();
            started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = 0; i < frames.size(); i++) {
                byte[] annexB = toAnnexB(frames.get(i));
                ByteBuffer buf = ByteBuffer.wrap(annexB);
                info.set(0, annexB.length, i * 33_333L,
                        MediaCodec.BUFFER_FLAG_KEY_FRAME);
                muxer.writeSampleData(track, buf, info);
            }
        } finally {
            if (started) {
                muxer.stop();
            }
            muxer.release();
        }
    }

    private static ByteBuffer withStartCode(byte[] nal) {
        ByteBuffer b = ByteBuffer.allocate(nal.length + 4);
        b.put(new byte[]{0, 0, 0, 1});
        b.put(nal);
        b.position(0);
        return b;
    }

    private static byte[] toAnnexB(byte[] avcc) {
        ByteBuffer in = ByteBuffer.wrap(avcc);
        ByteArrayOutputStream out = new ByteArrayOutputStream(avcc.length + 32);
        while (in.remaining() >= 4) {
            int len = in.getInt();
            if (len <= 0 || len > in.remaining()) {
                return avcc;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            byte[] nal = new byte[len];
            in.get(nal);
            try {
                out.write(nal);
            } catch (IOException e) {
                return avcc;
            }
        }
        return out.toByteArray();
    }

    private void loadPlaybackSettings() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        slideshow.setEnabled(sp.getBoolean(KEY_SLIDESHOW, false));
        slideshow.setIntervalSec(sp.getInt(KEY_SLIDESHOW_SEC, 30));
        slideshow.setFade(sp.getBoolean(KEY_SLIDESHOW_FADE, true));
        slideshow.setRandom(sp.getBoolean(KEY_SLIDESHOW_RANDOM, false));
        videoPlayer.setMuted(sp.getBoolean(KEY_VIDEO_MUTED, true));
    }

    private void savePlaybackSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SLIDESHOW, slideshow.isEnabled())
                .putInt(KEY_SLIDESHOW_SEC, slideshow.getIntervalSec())
                .putBoolean(KEY_SLIDESHOW_FADE, slideshow.isFade())
                .putBoolean(KEY_SLIDESHOW_RANDOM, slideshow.isRandom())
                .putBoolean(KEY_VIDEO_MUTED, videoPlayer.isMuted())
                .apply();
    }

    private String intervalLabel(int seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分钟";
    }

    /** 「◀ 值 ▶」：左右箭头位置固定，值在中间居中；返回中间的值控件用于刷新 */
    private TextView addStepperValue(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);

        int arrowColor = 0xFF7FB2E5;
        TextView left = new TextView(this);
        left.setText("◀");
        left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        left.setTextColor(arrowColor);

        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        value.setTextColor(Color.WHITE);
        value.setGravity(Gravity.CENTER);

        TextView right = new TextView(this);
        right.setText("▶");
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        right.setTextColor(arrowColor);

        box.addView(left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(value, new LinearLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(box, new LinearLayout.LayoutParams(
                dp(136), ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    /** 设置项整行：左侧文字、右侧开关；焦点落在整行上，聚焦时整行高亮 */
    LinearLayout makeSettingsRow(String label, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        text.setTextColor(Color.WHITE);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setFocusable(false);
        sw.setFocusableInTouchMode(false);
        sw.setClickable(false);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(!sw.isChecked());
            }
        });
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
        return row;
    }

    LinearLayout.LayoutParams settingsRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    void showBackgroundSettings() {
        loadPlaybackSettings();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(30), dp(18), dp(30), dp(6));

        LinearLayout row4k = makeSettingsRow("4K 静态壁纸", reveal4KVideo,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        set4KStaticEnabled(isChecked);
                    }
                });
        box.addView(row4k, settingsRowLp());

        LinearLayout rowSlide = makeSettingsRow("幻灯片播放", slideshow.isEnabled(),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        slideshow.setEnabled(isChecked);
                        savePlaybackSettings();
                        resetBgTimer();
                        Log.i(TAG, "slideshow = " + isChecked);
                    }
                });
        box.addView(rowSlide, settingsRowLp());

        // 切换间隔：左侧名称、右侧「◀ 值 ▶」（箭头位置固定，只更新中间的值）
        LinearLayout rowInterval = new LinearLayout(this);
        rowInterval.setOrientation(LinearLayout.HORIZONTAL);
        rowInterval.setGravity(Gravity.CENTER_VERTICAL);
        rowInterval.setFocusable(true);
        rowInterval.setFocusableInTouchMode(true);
        rowInterval.setPadding(dp(14), dp(8), dp(14), dp(8));

        TextView intervalTitle = new TextView(this);
        intervalTitle.setText("切换间隔");
        intervalTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        intervalTitle.setTextColor(Color.WHITE);
        rowInterval.addView(intervalTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView intervalValue = addStepperValue(rowInterval);
        final Runnable refreshInterval = new Runnable() {
            @Override
            public void run() {
                intervalValue.setText(intervalLabel(slideshow.getIntervalSec()));
            }
        };
        refreshInterval.run();

        rowInterval.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
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
        box.addView(rowInterval, settingsRowLp());

        // 播放顺序：左侧名称、右侧「◀ 顺序/随机 ▶」
        LinearLayout rowOrder = new LinearLayout(this);
        rowOrder.setOrientation(LinearLayout.HORIZONTAL);
        rowOrder.setGravity(Gravity.CENTER_VERTICAL);
        rowOrder.setFocusable(true);
        rowOrder.setFocusableInTouchMode(true);
        rowOrder.setPadding(dp(14), dp(8), dp(14), dp(8));

        TextView orderTitle = new TextView(this);
        orderTitle.setText("播放顺序");
        orderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        orderTitle.setTextColor(Color.WHITE);
        rowOrder.addView(orderTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView orderValue = addStepperValue(rowOrder);
        final Runnable refreshOrder = new Runnable() {
            @Override
            public void run() {
                orderValue.setText(slideshow.isRandom() ? "随机" : "顺序");
            }
        };
        refreshOrder.run();

        rowOrder.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
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
                savePlaybackSettings();
                refreshOrder.run();
                return true;
            }
        });
        rowOrder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slideshow.setRandom(!slideshow.isRandom());
                savePlaybackSettings();
                refreshOrder.run();
            }
        });
        box.addView(rowOrder, settingsRowLp());

        LinearLayout rowFade = makeSettingsRow("切换特效", slideshow.isFade(),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        slideshow.setFade(isChecked);
                        savePlaybackSettings();
                        Log.i(TAG, "slideshow fade = " + isChecked);
                    }
                });
        box.addView(rowFade, settingsRowLp());

        LinearLayout rowMute = makeSettingsRow("动态壁纸静音", videoPlayer.isMuted(),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        videoPlayer.setMuted(isChecked);
                        savePlaybackSettings();
                    }
                });
        box.addView(rowMute, settingsRowLp());


        TextView hint4k = new TextView(this);
        hint4k.setText("4K 静态壁纸：将 4K 图片编码为 3 帧短视频，利用硬件视频层播放，比直接渲染更清晰。");
        hint4k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint4k.setTextColor(0xFF9FB0C2);
        hint4k.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        box.addView(hint4k, dlp);

        TextView hintCache = new TextView(this);
        hintCache.setText(fourKCacheSummary()
                + "\n图片保存在 /sdcard/VIDAA桌面壁纸/静态、视频在 /动态，卸载应用不会删除；"
                + "4K 缓存位于应用内部存储，卸载或清空数据后会自动重建。");
        hintCache.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hintCache.setTextColor(0xFF9FB0C2);
        hintCache.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        box.addView(hintCache, clp);

        new AlertDialog.Builder(this)
                .setTitle("背景设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
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
        savePlaybackSettings();
        resetBgTimer();
        Log.i(TAG, "slideshow interval = " + slideshow.getIntervalSec() + "s");
    }

    void switchWallpaperBy(int direction) {
        if (videoWallpaperMode) {
            switchVideoBy(direction);
        } else if (fourKStaticEnabled()) {
            switch4KStatic(direction);
        } else {
            advanceBackground(direction);
            resetBgTimer();
        }
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    void openWallpaperManager() {
        if (wallpaperManager == null || isManagerOpen()) {
            return;
        }
        menu.hideImmediately();
        // 管理页完全盖住桌面，视频看不见；先暂停解码，
        // 避免导入大文件时解码器和磁盘写入、媒体扫描抢资源导致卡死。
        videoPlayer.pauseForOverlay();
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        menu.setFocusBlocked(true);
        wallpaperManager.open();
        pruneThumbCacheAsync();
        Log.i(TAG, "wallpaper manager opened, images=" + fourKItems.size()
                + " videos=" + videoItems.size());
    }

    private void closeWallpaperManager() {
        if (!isManagerOpen()) {
            return;
        }
        wallpaperManager.close();
        // 内存缓存留着，下次进来直接命中；只有条目太多时才整体释放
        if (thumbnailCache.size() > 200) {
            thumbnailCache.clear();
        }
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        menu.setFocusBlocked(false);
        focusFirstDockItem();
        // 管理页只是盖在桌面上：surface 还在就从暂停位置续播；
        // 只有 surface 真的被销毁（或播放器已不在）时才需要重新 prepare。
        videoPlayer.resumeAfterOverlay();
        resetWidgetAutoHide();
    }

    // ---------- 桌面设置页 ----------

    void setIconSizePreset(String preset) {
        if (!preset.equals(iconSizePreset)) {
            iconSizePreset = preset;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_ICON_SIZE, preset).apply();
            // 旧尺寸的图标位图必须清掉，否则抽屉里仍是旧大小
            icons.clearCaches();
            drawer.resetSelection();
            drawer.setColumns(iconsPerRow());
            drawer.resetAdapter();
            refreshDock();
            Log.i(TAG, "icon size changed: " + preset + " (" + iconSizeDp()
                    + "dp, " + iconsPerRow() + " per row)");
        }
    }

    boolean isRemoteKeepRunning() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_REMOTE_KEEP, false);
    }

    void setRemoteKeepRunning(boolean keep) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_REMOTE_KEEP, keep).apply();
        if (remotePage != null) {
            remotePage.setKeepChecked(keep);
        }
        if (!keep && !isRemoteOpen() && remotePage != null) {
            // 页面已关闭且关掉保持运行：立刻停服
            remotePage.stopServer();
            Log.i(TAG, "remote import server stopped by keep switch");
        }
        Toast.makeText(this,
                keep ? "已开启：关闭本页后服务继续运行" : "已关闭：离开本页即停止服务",
                Toast.LENGTH_SHORT).show();
    }

    // ---------- 页面委托：DesktopSettingsPage / RemoteImportPage 只负责界面 ----------

    void openDesktopSettings() {
        settingsPage.open();
    }

    private void closeDesktopSettings() {
        settingsPage.close();
    }

    void openRemoteImport() {
        remotePage.open();
    }

    private void closeRemoteImport() {
        remotePage.close();
    }

    boolean isRemoteOpen() {
        return remotePage != null && remotePage.isOpen();
    }

    boolean isSettingsOpen() {
        return settingsPage != null && settingsPage.isOpen();
    }

    boolean isManagerOpen() {
        return wallpaperManager != null && wallpaperManager.isOpen();
    }

    // ---------- 壁纸管理页需要的宿主数据与动作 ----------

    /** 管理页 / 远程页共用的胶囊按钮 */
    TextView makeManagerButton(String label, final View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.tile_bg);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.dock_focus : R.drawable.tile_bg);
            }
        });
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.rightMargin = dp(10);
        button.setLayoutParams(lp);
        return button;
    }

    List<BackgroundItem> managerImageItems() {
        return fourKItems;
    }

    List<BackgroundItem> managerVideoItems() {
        return videoItems;
    }

    /** 视频模式下静态栏没有「当前」项 */
    String managerImageCurrentName() {
        return videoWallpaperMode ? null : current4KName;
    }

    /** 图片模式下动态栏没有「当前」项 */
    String managerVideoCurrentName() {
        return videoWallpaperMode ? currentVideoName : null;
    }

    int managerImageIndex() {
        return fourKIndex;
    }

    int managerVideoIndex() {
        return videoIndex;
    }

    private void setManagerType(int type) {
        if (wallpaperManager != null) {
            wallpaperManager.setType(type);
        }
    }

    private void refreshManager() {
        if (wallpaperManager != null) {
            wallpaperManager.refresh();
        }
    }

    private void keepManagerTabFocus(int selection) {
        if (wallpaperManager != null) {
            wallpaperManager.keepTabFocus(selection);
        }
    }

    // ---------- 顶部菜单条（DesktopMenu）宿主 ----------

    boolean isMenuOpen() {
        return menu != null && menu.isOpen();
    }

    boolean isDrawerOpen() {
        return drawerOpen;
    }

    /** 打开菜单/抽屉前记录焦点是否在左右箭头上 */
    void rememberReturnFocus() {
        returnFocusToArrows = widgets != null
                && isFocusIn(widgets.getWallpaperControls());
    }

    /** 关闭菜单/抽屉后把焦点还回原处 */
    void restoreReturnFocus() {
        TextView prev = widgets == null ? null : widgets.getPrevControl();
        if (returnFocusToArrows && prev != null) {
            prev.requestFocus();
        } else {
            focusFirstDockItem();
        }
        returnFocusToArrows = false;
    }

    /** 菜单打开/关闭时把桌面整体下压或复位；4K 视频层露出时菜单悬浮、桌面不动 */
    void setDesktopOffsetForMenu(boolean opening) {
        if (desktopLayer != null) {
            desktopLayer.animateForMenu(opening, shouldFloatMenu());
        }
    }

    void resetDesktopOffsetForMenu() {
        if (desktopLayer != null) {
            desktopLayer.resetOffset();
        }
    }

    /** 菜单开着时 4K 模式切换：重新计算桌面是否需要下压 */
    private void relayoutDesktopForMenu() {
        if (isMenuOpen() && desktopLayer != null) {
            desktopLayer.relayoutForMenu(shouldFloatMenu());
        }
    }

    // ---------- 4K 静态播放器（FourKStaticPlayer）宿主 ----------

    boolean isVideoWallpaperMode() {
        return videoWallpaperMode;
    }

    void setVideoSurfaceLost(boolean lost) {
        videoSurfaceLost = lost;
    }

    VideoView wallpaperVideoView() {
        return desktopLayer.video();
    }

    ImageView bgFrontView() {
        return desktopLayer.bgFront();
    }

    ImageView bgBackView() {
        return desktopLayer.bgBack();
    }

    FrameLayout transitionLayerView() {
        return desktopLayer.transitionLayer();
    }

    ImageView transitionOldView() {
        return desktopLayer.transitionOld();
    }

    ImageView transitionNewView() {
        return desktopLayer.transitionNew();
    }

    // ---------- 动态视频播放器（VideoWallpaperPlayer）宿主 ----------

    int videoItemCount() {
        return videoItems.size();
    }

    BackgroundItem videoItemAt(int index) {
        return videoItems.get(index);
    }

    int currentVideoIndex() {
        return videoIndex;
    }

    void setCurrentVideoIndex(int index) {
        videoIndex = index;
    }

    void setCurrentVideoName(String name) {
        currentVideoName = name;
    }

    void persistVideoWallpaper(String name) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_VIDEO_BACKGROUND, name)
                .putString(KEY_WALLPAPER_TYPE, TYPE_VIDEO).apply();
    }

    /** 视频播放期间停掉 1080p 幻灯片计时，避免两套壁纸管线抢占 */
    void pauseSlideshowForVideo() {
        slideshow.stopTimer();
    }

    /** 播放视频前关闭 4K 静态管线 */
    void stopFourKForVideo() {
        fourKStatic.setMode(false);
        fourKStatic.reset();
        fourKStatic.stop();
    }

    /** 视频首帧稳定后，允许 4K/视频共用的过渡层收起 */
    void markFourKVideoReady() {
        fourKStatic.markReady();
        maybeHide4KUiCover();
    }

    /** 视频播放失败/卡死兜底：退回图片模式 */
    void onVideoFallbackToImage(boolean notifyUser) {
        videoWallpaperMode = false;
        stopVideoWallpaper();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        refresh4KStaticMode();
        if (notifyUser) {
            Toast.makeText(this, "视频壁纸播放异常，已恢复静态壁纸",
                    Toast.LENGTH_LONG).show();
        }
    }

    boolean isUiForeground() {
        return uiForeground;
    }

    boolean isVideoSurfaceLost() {
        return videoSurfaceLost;
    }

    /** 远程页进入全屏：挡住下层焦点，并保持亮屏 */
    void remotePageEnterFullscreen() {
        // 远程页在最上层，必须挡住管理页/桌面的焦点，否则遥控器会点到下面的网格
        if (wallpaperManager != null) {
            wallpaperManager.getView().setDescendantFocusability(
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        menu.setFocusBlocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 远程页退出全屏：恢复下层焦点，必要时续播为导入让路的视频 */
    void remotePageExitFullscreen() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isManagerOpen()) {
            // 回到管理页只把焦点放回 tab，不定位当前壁纸
            wallpaperManager.getView().setDescendantFocusability(
                    ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            wallpaperManager.requestTabFocus();
        } else {
            if (desktopModule != null) {
                desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            focusFirstDockItem();
            // 传输期间为导入让路而暂停的视频，回到桌面后从原位置续播
            if (videoWallpaperMode
                && (videoPlayer.isPausedForTransfer() || videoPlayer.isResumePending())) {
                resumeVideoAfterTransfer();
            }
        }
        resetWidgetAutoHide();
    }

    void settingsEnterFullscreen() {
        menu.hideImmediately();
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        menu.setFocusBlocked(true);
    }

    void settingsExitFullscreen() {
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        menu.setFocusBlocked(false);
        focusFirstDockItem();
        resetWidgetAutoHide();
    }

    /** 桌面控件开关的当前值（供设置页初始化开关状态） */
    boolean widgetEnabled(String key) {
        return widgets != null && widgets.isEnabled(key);
    }

    void setWidgetEnabled(String key, boolean enabled) {
        if (widgets != null) {
            widgets.setEnabled(key, enabled);
        }
    }

    String widgetModeValue() {
        return widgets == null ? WIDGET_MODE_ALWAYS : widgets.getMode();
    }

    String iconSizePreset() {
        return iconSizePreset;
    }

    // ===== 远程导入宿主接口：仅供 RemoteImportHostBridge 使用 =====

    List<BackgroundItem> remoteImageItems() {
        return fourKItems;
    }

    List<BackgroundItem> remoteVideoItems() {
        return videoItems;
    }

    String remoteCurrentImageName() {
        return videoWallpaperMode ? null : current4KName;
    }

    String remoteCurrentVideoName() {
        return videoWallpaperMode ? currentVideoName : null;
    }

    File remoteImageDir() {
        return store.staticDir();
    }

    File remoteVideoDir() {
        return store.videoDir();
    }

    /** 远程上传的图片落盘后：刷新列表，壁纸库原本为空时自动设为当前 */
    void onRemoteImageSaved(final String saved) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean wasEmpty = fourKItems.isEmpty();
                reloadBackgrounds();
                reload4KBackgrounds();
                refreshManager();
                if (wasEmpty) {
                    applyWallpaperByName(saved);
                }
                if (!isRemoteOpen()) {
                    Toast.makeText(LauncherActivity.this,
                            "已收到壁纸 " + saved, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 远程上传的视频落盘后：刷新列表 */
    void onRemoteVideoSaved(final String saved) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                reloadVideoBackgrounds();
                refreshManager();
                if (!isRemoteOpen()) {
                    Toast.makeText(LauncherActivity.this,
                            "已收到动态壁纸 " + saved, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    void postRemoteStatus(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (remotePage != null) {
                    remotePage.setStatus(message);
                }
            }
        });
    }

    void beginRemoteTransfer() {
        callOnUi(new Callable<Void>() {
            @Override
            public Void call() {
                videoPlayer.beginTransfer();
                return null;
            }
        }, 3000);
    }

    void endRemoteTransfer() {
        callOnUi(new Callable<Void>() {
            @Override
            public Void call() {
                videoPlayer.endTransfer();
                return null;
            }
        }, 3000);
    }

    /** 把任务丢到主线程执行并等结果；已经在主线程则直接执行 */
    <T> T callOnUi(final Callable<T> task, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return task.call();
            } catch (Exception e) {
                Log.w(TAG, "ui task failed", e);
                return null;
            }
        }
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = task.call();
                } catch (Exception e) {
                    Log.w(TAG, "ui task failed", e);
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result;
    }

    /** 背景设置里展示的缓存概况：已生成张数 + 占用体积 */
    private String fourKCacheSummary() {
        return "4K 缓存：已生成 " + caches.fourKCacheFileCount() + " / " + fourKItems.size()
                + " 张，占用 " + WallpaperCaches.formatSize(caches.totalFourKCacheSize()) + "。";
    }

    /** 删除单张壁纸的缩略图缓存（内存 + 磁盘） */
    private void deleteThumbFor(BackgroundItem item) {
        thumbnailCache.remove(item.name + "|" + item.stamp);
        caches.deleteThumb(item);
    }

    /** 壁纸列表变化后清掉孤儿缩略图（源文件已删的那些） */
    private void pruneThumbCacheAsync() {
        Set<String> keep = new HashSet<>();
        for (BackgroundItem item : fourKItems) {
            keep.add(caches.thumbFileName(item));
        }
        for (BackgroundItem item : videoItems) {
            keep.add(caches.thumbFileName(item));
        }
        for (BackgroundItem item : backgroundItems) {
            keep.add(caches.thumbFileName(item));
        }
        caches.pruneThumbs(keep);
    }

    void loadThumbnail(final ImageView view, final BackgroundItem item) {
        final String key = item.name + "|" + item.stamp;
        view.setTag(key);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        Bitmap onDisk = caches.loadThumbFromDisk(item);
        if (onDisk != null) {
            thumbnailCache.put(key, onDisk);
            view.setImageBitmap(onDisk);
            return;
        }
        view.setImageDrawable(null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = imageLoader.decodeThumbnail(item, 400, 225);
                if (bitmap == null) {
                    return;
                }
                thumbnailCache.put(key, bitmap);
                caches.saveThumbToDisk(item, bitmap);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }, "wallpaper-thumb").start();
    }

    void applyWallpaperByName(String name) {
        int index = -1;
        for (int i = 0; i < fourKItems.size(); i++) {
            if (fourKItems.get(i).name.equals(name)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        current4KName = name;
        fourKIndex = index;
        setWallpaperCleared(false);
        leaveVideoWallpaperMode();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_4K_BACKGROUND, name)
                .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        if (fourKStaticEnabled()) {
            fourKStatic.resetEncoding();
            fourKStatic.invalidate();
            if (!fourKStatic.isMode()) {
                // 刚离开视频模式：重新进 4K 静态模式（会按新的 current4KName 显示）
                refresh4KStaticMode();
            } else {
                show4KCurrent();
            }
        } else {
            if (index < backgroundItems.size()) {
                showBackgroundImmediately(index);
            }
            resetBgTimer();
        }
        Log.i(TAG, "wallpaper applied by manager: " + name);
        refreshManager();
        keepManagerTabFocus(index);
    }

    void moveWallpaper(int position, int delta) {
        int target = position + delta;
        if (target < 0 || target >= fourKItems.size()) {
            return;
        }
        Collections.swap(fourKItems, position, target);
        catalog.saveOrder(fourKItems);
        // 列表已手动重排：重新定位当前项，避免 indexes 错位
        int current = 0;
        for (int i = 0; i < fourKItems.size(); i++) {
            if (fourKItems.get(i).name.equals(current4KName)) {
                current = i;
                break;
            }
        }
        fourKIndex = current;
        reloadBackgrounds();
        reload4KBackgrounds();
        refresh4KStaticMode();
        refreshManager();
        keepManagerTabFocus(target);
    }

    void confirmDeleteWallpaper(final BackgroundItem item) {
        if (item.builtin) {
            Toast.makeText(this, "内置壁纸不能删除", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean video = WallpaperStore.isVideoName(item.name);
        new AlertDialog.Builder(this)
                .setTitle(video ? "删除视频壁纸" : "删除壁纸")
                .setMessage((video ? "视频文件会被删除。" : "同时删除它的 4K 缓存，原始图片会被删除。")
                        + "\n\n" + item.name)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteWallpaper(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void deleteWallpaper(BackgroundItem item) {
        if (item.builtin) {
            Toast.makeText(this, "内置壁纸不能删除", Toast.LENGTH_SHORT).show();
            return;
        }
        if (WallpaperStore.isVideoName(item.name)) {
            deleteVideoWallpaper(item);
            return;
        }
        File file = new File(item.filePath);
        boolean wasCurrent = item.name.equals(current4KName);
        boolean wasVideoMode = videoWallpaperMode;
        if (!file.delete()) {
            Toast.makeText(this, "删除失败：" + item.name, Toast.LENGTH_LONG).show();
            return;
        }
        caches.deleteFourKCacheFor(item);
        deleteThumbFor(item);
        if (wasCurrent && !wasVideoMode) {
            // 删除的就是正在显示的静态壁纸：直接置黑，不自动挑别的
            setWallpaperCleared(true);
            current4KName = null;
            fourKIndex = -1;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_4K_BACKGROUND).apply();
        }
        reload4KBackgrounds();
        reloadBackgrounds();
        if (wasCurrent && !wasVideoMode) {
            fourKStatic.setMode(false);
            fourKStatic.reset();
            fourKStatic.stop();
            clearWallpaperToBlack();
            Toast.makeText(this, "已删除当前壁纸，桌面已置黑", Toast.LENGTH_SHORT).show();
        } else {
            if (wasCurrent) {
                // 动态壁纸还在播，删的只是图片备用项：只更新记录
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_4K_BACKGROUND,
                                current4KName == null ? "" : current4KName).apply();
            }
            refresh4KStaticMode();
        }
        refreshManager();
        if (isManagerOpen() && !fourKItems.isEmpty()) {
            keepManagerTabFocus(Math.max(fourKIndex, 0));
        }
        Toast.makeText(this, "已删除 " + item.name, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "wallpaper deleted: " + item.name);
    }

    void deleteVideoWallpaper(BackgroundItem item) {
        boolean wasCurrent = videoWallpaperMode && item.name.equals(currentVideoName);
        File file = new File(item.filePath);
        if (!file.delete()) {
            Toast.makeText(this, "删除失败：" + item.name, Toast.LENGTH_LONG).show();
            return;
        }
        deleteThumbFor(item);
        if (wasCurrent) {
            // 删除的是正在播放的动态壁纸：直接置黑，不自动切别的视频/图片
            setWallpaperCleared(true);
            videoWallpaperMode = false;
            stopVideoWallpaper();
            currentVideoName = null;
            videoIndex = -1;
            current4KName = null;
            fourKIndex = -1;
            fourKStatic.setMode(false);
            fourKStatic.reset();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_VIDEO_BACKGROUND)
                    .remove(KEY_4K_BACKGROUND)
                    .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
            reloadVideoBackgrounds();
            fourKStatic.stop();
            clearWallpaperToBlack();
            Toast.makeText(this, "已删除当前壁纸，桌面已置黑", Toast.LENGTH_SHORT).show();
        } else {
            reloadVideoBackgrounds();
            if (!videoWallpaperMode) {
                refresh4KStaticMode();
            }
        }
        refreshManager();
        if (isManagerOpen() && !videoItems.isEmpty()) {
            keepManagerTabFocus(Math.max(videoIndex, 0));
        }
        Toast.makeText(this, "已删除 " + item.name, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "video wallpaper deleted: " + item.name);
    }

    void confirmClearAllCache() {
        final long size = caches.totalFourKCacheSize() + caches.thumbCacheSize();
        if (size <= 0) {
            Toast.makeText(this, "没有缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空缓存")
                .setMessage("将删除全部缓存（" + WallpaperCaches.formatSize(size)
                        + "）：静态壁纸的 4K 视频缓存 + 缩略图缓存。\n下次使用会重新生成，原始壁纸不会删除。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        caches.deleteAll();
                        thumbnailCache.clear();
                        refreshManager();
                        Toast.makeText(LauncherActivity.this, "缓存已清空", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 壁纸导入 ----------

    /** 「导入」按钮：先选导入方式，再走对应的流程。 */
    void showImportChooser() {
        importer.showChooser();
    }

    /** 静态壁纸导入完成：刷新列表并应用最后一张成功导入的图 */
    void onWallpapersImported(String lastName) {
        reloadBackgrounds();
        reload4KBackgrounds();
        refresh4KStaticMode();
        if (lastName != null) {
            applyWallpaperByName(lastName);
        }
        refreshManager();
    }

    /** 动态壁纸导入完成：刷新视频列表 */
    void onVideosImported() {
        reloadVideoBackgrounds();
        refreshManager();
    }

    /** 导入浏览目录时按管理页当前分栏过滤文件 */
    boolean managerWantsVideo() {
        return wallpaperManager.getType() == WallpaperManagerPage.VIDEOS;
    }

    // ---------- 背景轮播（SlideshowController）宿主 ----------

    private void showBackgroundImmediately(int index) {
        slideshow.showImmediately(index);
    }

    private void advanceBackground(int delta) {
        slideshow.advance(delta);
    }

    private void advanceBackground(int delta, boolean random) {
        slideshow.advance(delta, random);
    }

    private void preloadNextBackground() {
        slideshow.preloadNext();
    }

    private void resetBgTimer() {
        slideshow.resetTimer();
    }

    private void ensureBgTimer() {
        slideshow.ensureTimer();
    }

    List<BackgroundItem> backgroundItems() {
        return backgroundItems;
    }

    /** 交叉淡化后交换前后两层，下一张继续从另一层进来 */
    void swapBackgroundViews(ImageView front, ImageView back) {
        desktopLayer.swapBackgrounds();
    }

    void invalidateWallpaperBlur() {
        wallpaperBlurKey = null;
    }

    /**
     * 优先读设备外部目录（/sdcard/Android/data/cn.rainynight.dream/files/backgrounds），
     * 目录里没有图片时才回退到构建时打进 APK 的 assets。
     */
    private void reloadBackgrounds() {
        // 一套原图：优先用 backgrounds_4k 的原图，UI 运行时按 1080p 采样；
        // 没有原图时才回退到 backgrounds / APK assets 的 1080p 版本。
        List<BackgroundItem> found = catalog.scanStill();
        if (found.isEmpty()) {
            found = catalog.scanLegacy();
        }
        if (WallpaperCatalog.sameItems(backgroundItems, found)) {
            return;
        }

        String keep = slideshow.currentName();
        backgroundItems.clear();
        backgroundItems.addAll(found);
        // 列表变了，之前预解码的那张作废
        slideshow.invalidatePreload();
        
        

        int index = 0;
        if (keep != null) {
            for (int i = 0; i < backgroundItems.size(); i++) {
                if (keep.equals(backgroundItems.get(i).name)) {
                    index = i;
                    break;
                }
            }
        }
        showBackgroundImmediately(index);
        if (isWallpaperCleared()) {
            // 「删掉当前壁纸」的黑屏状态：列表照常刷新，但不重新铺图、不启轮播
            slideshow.clearCurrent();
            
            clearWallpaperToBlack();
            return;
        }
        resetBgTimer();
        String source = backgroundItems.isEmpty()
                ? "none"
                : (backgroundItems.get(0).assetName != null ? "assets" : "original");
        Log.i(TAG, "background items: " + backgroundItems.size() + " (" + source + ")");
    }

    /** 列表为空或解码失败时的最后兜底：直接画内置壁纸，连它都读不出来才用深色底 */
    void showBuiltinFallback() {
        Bitmap bitmap = loadBackground(BUILTIN_ITEM);
        bgFrontView().setImageDrawable(null);
        if (bitmap != null) {
            bgFrontView().setBackgroundColor(Color.TRANSPARENT);
            bgFrontView().setImageBitmap(bitmap);
        } else {
            bgFrontView().setBackgroundColor(0xFF0B1017);
        }
        bgFrontView().setAlpha(1f);
    }

    Bitmap loadBackground(BackgroundItem item) {
        return imageLoader.loadBackground(item);
    }

    /** 自动轮播：两张图交叉淡入淡出，避免直接闪切 */
    /** 壁纸确定后提前把抽屉毛玻璃算好（截图在主线程，模糊在后台） */
    void precomputeWallpaperBlur() {
        if (fourKStaticEnabled() || videoWallpaperMode) {
            return;
        }
        final String key = slideshow.currentName() + "#" + slideshow.currentIndex();
        if (key.equals(wallpaperBlurKey) && wallpaperBlurBitmap != null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (fourKStaticEnabled() || videoWallpaperMode) {
                    return;
                }
                if (!key.equals(slideshow.currentName() + "#" + slideshow.currentIndex())) {
                    return; // 期间已经换图
                }
                final Bitmap captured = captureWallpaperDisplayed();
                if (captured == null) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap blurred = blurBitmap(captured);
                        captured.recycle();
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (blurred == null) {
                                    return;
                                }
                                if (!key.equals(slideshow.currentName() + "#" + slideshow.currentIndex())) {
                                    blurred.recycle();
                                    return;
                                }
                                wallpaperBlurBitmap = blurred;
                                wallpaperBlurKey = key;
                            }
                        });
                    }
                }, "frost-blur").start();
            }
        }, 1200);
    }

    /** 抽屉毛玻璃：当前壁纸的模糊副本，深色 tint 叠在上面 */
    private void prepareDrawerFrost() {
        if (fourKStaticEnabled()) {
            drawer.setFrost(null);
            return;
        }
        drawer.setFrost(getWallpaperBlur());
    }

    /** 模糊壁纸缓存：整个屏幕只算一次，供抽屉毛玻璃使用 */
    private Bitmap getWallpaperBlur() {
        String key = slideshow.currentName() + "#" + slideshow.currentIndex();
        if (key.equals(wallpaperBlurKey) && wallpaperBlurBitmap != null) {
            return wallpaperBlurBitmap;
        }
        Bitmap captured = captureWallpaperDisplayed();
        Bitmap blurred = blurBitmap(captured);
        if (captured != null) {
            captured.recycle();
        }
        wallpaperBlurKey = key;
        wallpaperBlurBitmap = blurred;
        return blurred;
    }

    /** 把当前实际显示的壁纸抓成 960px 宽的位图 */
    private Bitmap captureWallpaperDisplayed() {
        return desktopLayer.captureWallpaper();
    }

    private Bitmap blurBitmap(Bitmap source) {
        return desktopLayer.blur(source);
    }

    Bitmap blurBitmap(Bitmap source, float radius) {
        return desktopLayer.blur(source, radius);
    }

    // ---------- 时钟 ----------

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            refreshClock();
            handler.postDelayed(this, 20000);
        }
    };

    private void refreshClock() {
        if (widgets != null) {
            widgets.refreshClock();
        }
    }

    // ---------- 天气 ----------

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            handler.postDelayed(this, WEATHER_REFRESH_MS);
        }
    };

    /** 从厂商天气 App 的 provider 读实时天气，失败就显示占位，不影响桌面 */
    private void refreshWeather() {
        final ContentResolver resolver = getContentResolver();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String temp = "";
                String condition = "";
                String place = "";
                String symbol = "☁";
                Cursor cursor = null;
                try {
                    cursor = resolver.query(WEATHER_URI,
                            new String[]{"city_name", "real_time_weather"}, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        String weatherJson = cursorString(cursor, "real_time_weather");
                        if (weatherJson != null) {
                            JSONObject weather = new JSONObject(weatherJson);
                            temp = weather.optString("temp", "");
                            condition = weather.optString("condition", "");
                            symbol = weatherSymbol(weather.optString("icon", ""), condition);
                        }
                        String cityJson = cursorString(cursor, "city_name");
                        if (cityJson != null) {
                            JSONObject city = new JSONObject(cityJson);
                            String district = city.optString("mDistrict", "");
                            String cityName = city.optString("mCity", "");
                            place = shortPlace(TextUtils.isEmpty(district) ? cityName : district);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "weather query failed: " + t, t);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }

                final String finalTemp = temp;
                final String finalCondition = condition;
                final String finalPlace = place;
                final String finalSymbol = symbol;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyWeather(finalTemp, finalCondition, finalPlace, finalSymbol);
                    }
                });
            }
        }, "weather-query").start();
    }

    private void applyWeather(String temp, String condition, String place, String symbol) {
        if (widgets != null) {
            widgets.applyWeather(temp, condition, place, symbol);
        }
    }

    private static String cursorString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 ? cursor.getString(index) : null;
    }

    private static String shortPlace(String place) {
        if (place != null && place.length() > 1 && place.endsWith("市")) {
            return place.substring(0, place.length() - 1);
        }
        return place == null ? "" : place;
    }

    private static String weatherSymbol(String icon, String condition) {
        String text = condition == null ? "" : condition;
        if (text.contains("雷")) return "⚡";
        if (text.contains("雪")) return "❄";
        if (text.contains("雨")) return "☂";
        if (text.contains("雾") || text.contains("霾")) return "☁";
        if (text.contains("阴")) return "☁";
        if (text.contains("云")) return "⛅";
        if (text.contains("晴")) return "☀";
        switch (icon) {
            case "0":
                return "☀";
            case "1":
                return "⛅";
            case "2":
                return "☁";
            case "3":
                return "☂";
            case "4":
                return "⚡";
            case "5":
                return "❄";
            default:
                return "☁";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiForeground = true;
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
        reloadBackgrounds();
        reload4KBackgrounds();
        // 只续期、不重置：频繁回到桌面不应该把幻灯片倒计时无限推后
        ensureBgTimer();
        // 回到桌面先恢复控件显示，再重新开始自动隐藏计时
        if (widgets != null) {
            widgets.resetAutoHidden();
        }
        applyWidgetVisibility();
        resetWidgetAutoHide();
        // 从其它应用/应用商店回到桌面时，重新扫一遍应用列表：
        // 新安装的应用才能按"安装时间倒序"立刻出现在底部补位里。
        // 扫描放后台线程，列表没变化就不动 dock（避免返回桌面时 UI 卡一下、dock 闪一下）。
        if (firstResume) {
            firstResume = false;
            refreshDock();
        } else {
            reloadAppsAsync();
            if (videoWallpaperMode) {
                restoreVideoWallpaperOnForeground();
            } else {
                refresh4KStaticMode();
            }
        }
        // 底栏默认聚焦第一项，左右键用来选择应用
        if (isManagerOpen()) {
            refreshManager();
            keepManagerTabFocus(-1);
        } else if (!isSettingsOpen()) {
            // 兜底：从抽屉里开过应用后，桌面层可能还停在隐藏状态
            if (!drawerOpen && homeLayer != null
                    && homeLayer.getVisibility() != View.VISIBLE) {
                showHomeLayer(0);
            }
            if (!desktopFocusCleared) {
                focusFirstDockItem();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask 下按 HOME 可能只走 onNewIntent，不触发 onResume
        // 在壁纸管理页（或远程导入页）按 HOME：直接回桌面
        boolean homePressed = intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
        boolean homeOnDesktop = homePressed && !isRemoteOpen() && !isManagerOpen() && !isSettingsOpen()
                && !isMenuOpen() && !drawerOpen;
        if (homePressed) {
            if (isRemoteOpen()) {
                closeRemoteImport();
            }
            if (isManagerOpen()) {
                closeWallpaperManager();
            }
            if (isSettingsOpen()) {
                closeDesktopSettings();
            }
        }
        menu.hideImmediately();
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        reloadAppsAsync();
        if (!isManagerOpen() && homeLayer != null
                && homeLayer.getVisibility() != View.VISIBLE) {
            showHomeLayer(0);
        }
        if (homeOnDesktop) {
            // 桌面上按 HOME：不自动选中第一个图标
            clearDesktopFocus();
        } else {
            focusFirstDockItem();
        }
        if (videoWallpaperMode) {
            restoreVideoWallpaperOnForeground();
        } else {
            refresh4KStaticMode();
        }
        handleDebugWallpaperIntent(intent);
        if (intent != null && intent.getBooleanExtra("wallpaper_manager", false)) {
            if ("video".equals(intent.getStringExtra("manager_tab"))) {
                setManagerType(WallpaperManagerPage.VIDEOS);
            }
            openWallpaperManager();
            if (intent.getBooleanExtra("remote_import", false)) {
                openRemoteImport();
            }
        }
    }

    /** 启动首屏显示后再扫描应用列表，避免首帧被 PackageManager 扫描拖慢（扫描本身也放后台） */
    private void loadAppsDeferred() {
        if (startupAppScanDone) {
            return;
        }
        startupAppScanDone = true;
        Log.i(TAG, "deferred app scan start");
        if (appScanRunning) {
            return;
        }
        appScanRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> scanned = appRepository.scan();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appScanRunning = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        appRepository.apply(scanned);
                        drawer.notifyAppsChanged();
                        refreshDock();
                        if (getCurrentFocus() == null && !desktopFocusCleared
                                && widgets != null) {
                            focusFirstDockItem();
                        }
                        Log.i(TAG, "deferred app scan done: " + appRepository.all().size());
                    }
                });
            }
        }, "app-scan").start();
    }

    /** 重新读取应用列表 + 最近使用，并刷新抽屉和底部栏 */
    private void reloadApps() {
        loadApps();
        drawer.notifyAppsChanged();
        refreshDock();
    }

    /**
     * 后台重扫应用列表：PackageManager 扫描 + 图标解码比较慢，放在主线程会让
     * 从其他应用返回桌面时卡一下、dock 重建一次（看起来像桌面重新加载）。
     * 扫描结果和当前列表一致时什么都不做。
     */
    private void reloadAppsAsync() {
        if (appScanRunning) {
            return;
        }
        appScanRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> scanned = appRepository.scan();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appScanRunning = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (appRepository.sameAs(scanned)) {
                            return;
                        }
                        appRepository.apply(scanned);
                        drawer.notifyAppsChanged();
                        refreshDock();
                    }
                });
            }
        }, "app-scan").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiForeground = false;
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        slideshow.stopTimer();
        if (widgets != null) {
            widgets.pauseAutoHide();
        }
        videoPlayer.pauseHealth();
        if (wallpaperVideoView() != null && wallpaperVideoView().isPlaying()) {
            wallpaperVideoView().pause();
        }
        // 注意：这里不能重置 4K 的 ready/preparing。桌面按 HOME 也会走一次 pause，
        // 一重置就会在 onNewIntent 里重新铺预览图 + 重播 4K（画面瞬间变糊再变清晰）。
        // 真正需要重播的场景（切到别的应用）会让 SurfaceView 销毁，那个在 surfaceDestroyed 里重置。
        menu.hideImmediately();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (remotePage != null) {
            remotePage.shutdown();
        }
        stopVideoWallpaper();
        fourKStatic.stop();
        // 清掉所有排队中的延时任务，避免它们继续持有已销毁的 Activity
        handler.removeCallbacksAndMessages(null);
    }

    // ---------- 抽屉 ----------

    private void openDrawer() {
        if (drawerOpen || widgets == null || !widgets.isDrawerEnabled()) return;
        drawerOpen = true;
        rememberReturnFocus();
        prepareDrawerFrost();
        hideHomeLayer();
        drawer.show();
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        homeLayer.setVisibility(View.VISIBLE);
        drawer.hide(new Runnable() {
            @Override
            public void run() {
                showHomeLayer(0);
            }
        });
        restoreReturnFocus();
        resetWidgetAutoHide();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 有的机型会把 HOME 直接派发给当前 Activity
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            // 已经在桌面（没有任何浮层/抽屉/菜单）时，HOME 只做取消选中
            boolean onDesktop = !isRemoteOpen() && !isManagerOpen() && !isSettingsOpen()
                    && !isMenuOpen() && !drawerOpen;
            if (isRemoteOpen()) {
                closeRemoteImport();
            }
            if (isManagerOpen()) {
                closeWallpaperManager();
            }
            if (isSettingsOpen()) {
                closeDesktopSettings();
            }
            if (onDesktop) {
                clearDesktopFocus();
            }
            return true;
        }
        if (isManagerOpen() || isSettingsOpen()) {
            return super.onKeyDown(keyCode, event);
        }
        if (!drawerOpen && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            openDrawer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (isRemoteOpen()) {
            closeRemoteImport();
            return;
        }
        if (isManagerOpen()) {
            closeWallpaperManager();
            return;
        }
        if (isSettingsOpen()) {
            closeDesktopSettings();
            return;
        }
        if (isMenuOpen()) {
            menu.close();
            return;
        }
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        // 桌面上按返回不退出，只取消当前图标的选中
        clearDesktopFocus();
    }

    // ---------- 顶部菜单条 ----------

    /** 4K 视频层真正露出来时菜单悬浮；否则（UI 壁纸/4K 关闭）沿用桌面下压 */
    private boolean shouldFloatMenu() {
        return videoWallpaperMode || fourKStaticEnabled();
    }

    /**
     * 底部焦点环：◀ ▶ 应用1 … 应用N，首尾相接。
     * 最左应用按左 → ▶；▶ 按左 → ◀；◀ 按左 → 最右应用；反向同理。
     */
    private boolean handleDockArrowRing(int key) {
        if (widgets == null || !widgets.isArrowRingActive()) {
            return false;
        }
        View focused = getCurrentFocus();
        if (focused == null) {
            return false;
        }
        LinearLayout controls = widgets.getWallpaperControls();
        LinearLayout dock = widgets.getDock();
        TextView prev = widgets.getPrevControl();
        TextView next = widgets.getNextControl();
        if (isFocusIn(controls)) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused == next && prev != null) {
                    prev.requestFocus();
                } else {
                    dock.getChildAt(dock.getChildCount() - 1).requestFocus();
                }
            } else {
                if (focused == prev && next != null) {
                    next.requestFocus();
                } else {
                    dock.getChildAt(0).requestFocus();
                }
            }
            return true;
        }
        if (isFocusIn(dock)) {
            int index = widgets.dockIndexOf(focused);
            if (index < 0) {
                return false;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && index == 0 && next != null) {
                next.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && index == dock.getChildCount() - 1
                    && prev != null) {
                prev.requestFocus();
                return true;
            }
        }
        return false;
    }

    boolean isFocusIn(View parent) {
        View current = getCurrentFocus();
        while (current != null) {
            if (current == parent) {
                return true;
            }
            ViewParent p = current.getParent();
            current = p instanceof View ? (View) p : null;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 管理页/设置页打开时只处理页面内的焦点导航，不再触发桌面菜单/抽屉
        if (isManagerOpen() || isSettingsOpen()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            View focused = getCurrentFocus();
            if (!isMenuOpen() && !drawerOpen && (focused == null || focused == focusSink)
                    && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER)) {
                // 桌面当前没有焦点（刚启动或按过返回）：第一次按键把焦点落到第一个应用
                focusFirstDockItem();
                return true;
            }
            if (isMenuOpen()) {
                // 菜单打开时，上/下或再按菜单键都收起菜单
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_MENU) {
                    menu.close();
                    return true;
                }
            } else if (key == KeyEvent.KEYCODE_MENU) {
                // 遥控器菜单键：呼出顶部菜单条（应用抽屉打开时先收起抽屉）
                if (drawerOpen) {
                    closeDrawer();
                }
                menu.open();
                return true;
            } else if (!drawerOpen && key == KeyEvent.KEYCODE_DPAD_UP) {
                // dock 和左右键现在同一行，上键一律直接呼出菜单
                menu.open();
                return true;
            } else if (!drawerOpen && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && handleDockArrowRing(key)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------- 最近使用 / 底部栏排序 ----------

    private void refreshDock() {
        if (widgets != null) {
            widgets.refreshDock();
        }
    }

    /** 底部栏数据：打开过的应用和新安装的应用放在同一条时间线上排序 */
    List<AppEntry> buildDockSource() {
        return recentApps.sorted(appRepository.all());
    }

    /** 打开应用：记录这次打开的时间，底部栏按时间重排 */
    private void rememberLaunch(AppEntry entry) {
        recentApps.remember(entry);
        refreshDock();
    }

    View makeDockItem(final AppEntry entry) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        // 重建 dock 时靠这个 key 把焦点还给同一个应用
        item.setTag(entry.key);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        item.setLayoutParams(lp);

        int iconSize = dp(iconSizeDp());
        // 遮罩层始终是正方形，图标放大缩小时不会被拉成长条；
        // 未聚焦时不画遮罩，聚焦时才显示亮色遮罩
        final FrameLayout mask = new FrameLayout(this);
        int maskSize = dp(iconMaskDp());
        item.addView(mask, new LinearLayout.LayoutParams(maskSize, maskSize));

        Bitmap dockIconBitmap = icons.squareIcon(entry.icon, iconSize);
        Bitmap iconShadow = icons.dockIconShadow(entry, dockIconBitmap);
        if (iconShadow != null) {
            ImageView shadowView = new ImageView(this);
            shadowView.setImageBitmap(iconShadow);
            mask.addView(shadowView, new FrameLayout.LayoutParams(
                    iconShadow.getWidth(), iconShadow.getHeight(), Gravity.CENTER));
        }

        ImageView icon = new ImageView(this);
        icon.setImageBitmap(dockIconBitmap);
        mask.addView(icon, new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.CENTER));

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconLabelSp());
        label.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                dp(iconMaskDp() + 16), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        item.addView(label, tlp);

        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mask.setBackgroundResource(hasFocus ? R.drawable.dock_focus : 0);
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(entry);
            }
        });
        return item;
    }

    /** 桌面组件淡出并轻微上移，与抽屉滑出同步 */
    private void hideHomeLayer() {
        homeLayer.animate().cancel();
        homeLayer.animate()
                .alpha(0f)
                .translationY(-dp(18))
                .setDuration(OPEN_MS)
                .setInterpolator(OPEN_EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOpen) {
                            homeLayer.setVisibility(View.INVISIBLE);
                        }
                    }
                })
                .start();
    }

    /** 桌面组件淡入并回落到原位 */
    private void showHomeLayer(long delay) {
        homeLayer.animate().cancel();
        homeLayer.setVisibility(View.VISIBLE);
        homeLayer.setAlpha(0f);
        homeLayer.setTranslationY(-dp(18));
        homeLayer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CLOSE_MS)
                .setStartDelay(delay)
                .setInterpolator(CLOSE_EASE)
                .start();
    }

    void focusFirstDockItem() {
        if (widgets != null && widgets.focusDockFirst()) {
            return;
        }
        // Dock 被关闭：焦点交给空焦点位，别留在不可见的控件上
        if (widgets == null || !widgets.isDockVisible()) {
            clearDesktopFocus();
        }
    }

    // ---------- 桌面控件显示 / 自动隐藏 ----------

    /** 当前是否该显示桌面控件（显示方式为隐藏，或自动隐藏已到点时不显示） */
    private boolean widgetsShown() {
        return widgets == null || widgets.widgetsShown();
    }

    private void applyWidgetVisibility() {
        if (widgets != null) {
            widgets.applyVisibility();
        }
    }

    private void applyWidgetVisibility(boolean animate) {
        if (widgets != null) {
            widgets.applyVisibility(animate);
        }
    }

    /** 当前没有任何浮层/抽屉，才算桌面空闲 */
    boolean onDesktopIdle() {
        return !isRemoteOpen() && !isManagerOpen() && !isSettingsOpen() && !isMenuOpen() && !drawerOpen;
    }

    /** 全局 UI Handler：控件自动隐藏等跨类计时任务共用 */
    Handler uiHandler() {
        return handler;
    }

    /** 任意按键/触摸后调用：恢复自动隐藏的控件，并重新开始空闲计时 */
    void resetWidgetAutoHide() {
        if (widgets != null) {
            widgets.resetAutoHide();
        }
    }

    void setWidgetMode(String mode) {
        if (widgets != null) {
            widgets.setMode(mode);
        }
    }

    /** 取消桌面图标选中：把焦点交给一个不可见的空焦点，Dock 图标全部无遮罩 */
    private void clearDesktopFocus() {
        if (homeLayer == null) {
            return;
        }
        // 部分 TV 系统在没有焦点时会强行把焦点给第一个可聚焦控件，
        // 所以这里主动聚焦一个 1dp 的透明焦点位，保证所有图标都保持未选中。
        if (focusSink == null) {
            focusSink = new View(this);
            focusSink.setFocusable(true);
            focusSink.setFocusableInTouchMode(true);
            focusSink.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(1), dp(1), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            homeLayer.addView(focusSink, lp);
        }
        focusSink.requestFocus();
        desktopFocusCleared = true;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        // 用户再次操作遥控器后恢复正常自动聚焦
        desktopFocusCleared = false;
        // 自动隐藏模式下，任意按键/触摸都重新显示控件并重新计时
        resetWidgetAutoHide();
    }

    // ---------- 应用列表 ----------

    void launch(AppEntry entry) {
        rememberLaunch(entry);
        // 启动应用后收起抽屉，回到主屏。
        // 必须走 closeDrawer()：它会恢复 homeLayer（时钟/应用坞）并把焦点放回 dock；
        // 原来直接 setVisibility(GONE) 会留下一张「只有壁纸、没有焦点」的桌面。
        // 开应用后焦点应回到刚点的应用（dock 首位），不要还给左右键。
        if (drawerOpen) {
            returnFocusToArrows = false;
            closeDrawer();
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(entry.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            // 启动失败留在桌面
        }
    }

    private void loadApps() {
        appRepository.load();
    }

}
