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
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /** 旧格式（只有顺序、没有时间），仅用于升级迁移 */
    private static final String KEY_RECENT = "recent_components";
    /** 新格式：每行 "组件|最后打开时间(ms)" */
    private static final String KEY_OPEN_TIMES = "open_times";
    /** 背景设置：转场后隐藏 UI 壁纸，显示 4K 视频层 */
    private static final String KEY_REVEAL_4K = "reveal_4k_video";
    private static final String KEY_4K_BACKGROUND = "current_4k_background";
    /** 壁纸自定义排序：按行保存文件名，未列出的按名称排在后面 */
    private static final String KEY_BG_ORDER = "background_order";
    /** 导入壁纸时申请存储读取权限 */
    private static final int REQ_READ_STORAGE = 4101;
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
    private static final float WALLPAPER_NUDGE_X = 1f;
    private static final float WALLPAPER_NUDGE_Y = 1f;

    /** 已弹过一次写权限申请，避免每次启动都打扰 */
    private static final String KEY_WRITE_ASKED = "write_permission_asked";
    private static final int REQ_WRITE_STORAGE = 4102;
    private static final String KEY_VIDEO_BACKGROUND = "current_video_background";
    private static final String KEY_WALLPAPER_TYPE = "wallpaper_type";
    /** 用户主动删掉当前壁纸后置为「无壁纸（黑屏）」，直到重新设置壁纸 */
    private static final String KEY_WALLPAPER_CLEARED = "wallpaper_cleared";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";

    /** 4K 静态壁纸：设备端编码缓存（原图目录见 WallpaperStore.STATIC_DIR） */
    static final String FOUR_K_CACHE_DIR = "4k_cache";
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
    /** 编码参数版本；改颜色/编码设置时递增，旧缓存下次启动自动清掉 */
    static final String FOUR_K_CACHE_VERSION = "v6";
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
    private static final float WALLPAPER_OVERSCAN = 1.002f;

    /** 打开/关闭动画时长（抽屉与桌面组件保持一致） */
    private static final long OPEN_MS = 400;
    private static final long CLOSE_MS = 320;
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

    static final class AppEntry {
        final String label;
        final ComponentName component;
        final Drawable icon;
        final String key;
        /** 首次安装时间：与最后打开时间一起决定底部栏的先后 */
        final long firstInstallTime;

        AppEntry(String label, ComponentName component, Drawable icon, long firstInstallTime) {
            this.label = label;
            this.component = component;
            this.icon = icon;
            this.key = component.flattenToString();
            this.firstInstallTime = firstInstallTime;
        }
    }

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();
    /** 壁纸文件仓储：目录解析 / 扫描 / 复制入库 / 迁移 */
    private WallpaperStore store;
    /** 组件 → 最后一次打开时间；和新安装时间合并后决定底部栏顺序 */
    private final Map<String, Long> openTimes = new HashMap<>();

    private FrameLayout desktopModule;
    private FrameLayout homeLayer;
    private FrameLayout drawer;
    private GridView grid;
    private ImageView drawerFrostView;
    private Bitmap wallpaperBlurBitmap;
    private String wallpaperBlurKey;
    /** 桌面控件层：时钟 / 天气 / Dock / 壁纸左右键 / 提示 */
    private DesktopWidgets widgets;
    private ImageView bgFront;
    private ImageView bgBack;
    private VideoView wallpaperVideo;
    private FrameLayout wallpaperTransitionLayer;
    private ImageView transitionOld;
    private ImageView transitionNew;
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
    /** 最近解码过的壁纸位图：同一张图在启动/切图时只解码一次 */
    private Bitmap lastLoadedBitmap;
    private String lastLoadedKey;
    private View startupBlackout;
    private boolean startupRevealed;
    private boolean startupAppScanDone;
    /** 后台重扫应用列表是否进行中（避免重复起线程） */
    private boolean appScanRunning;
    private int drawerSelectedPosition = 0;
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
    private int screenHeight;

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
    private int iconMaskDp() {
        return iconSizeDp() + 12;
    }

    /** 应用名称字号随图标尺寸缩放：小 10 / 中 11 / 大 12sp */
    private float iconLabelSp() {
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
        fourKStatic = new FourKStaticPlayer(this);
        videoPlayer = new VideoWallpaperPlayer(this);
        slideshow = new SlideshowController(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        reveal4KVideo = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_REVEAL_4K, true);
        iconSizePreset = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ICON_SIZE, ICON_SIZE_MEDIUM);

        loadOpenTimes();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 桌面是一个整体模块：壁纸 + 遮罩 + 时钟/天气/底栏 + 抽屉都会一起移动
        desktopModule = new FrameLayout(this);
        root.addView(desktopModule, matchParent());

        // 两张背景图交替淡化：bgFront 是当前图，bgBack 是下一张
        bgFront = new ImageView(this);
        bgFront.setScaleType(ImageView.ScaleType.CENTER_CROP);
        desktopModule.addView(bgFront, matchParent());

        bgBack = new ImageView(this);
        bgBack.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgBack.setAlpha(0f);
        desktopModule.addView(bgBack, matchParent());
        bgFront.setTranslationX(WALLPAPER_NUDGE_X);
        bgFront.setTranslationY(WALLPAPER_NUDGE_Y);
        bgFront.setScaleX(WALLPAPER_OVERSCAN);
        bgFront.setScaleY(WALLPAPER_OVERSCAN);
        bgBack.setTranslationX(WALLPAPER_NUDGE_X);
        bgBack.setTranslationY(WALLPAPER_NUDGE_Y);
        bgBack.setScaleX(WALLPAPER_OVERSCAN);
        bgBack.setScaleY(WALLPAPER_OVERSCAN);

        // 4K 视频壁纸测试层：普通 SurfaceView 会被压到 1080p，VideoView 走硬件视频 plane
        wallpaperVideo = new VideoView(this);
        wallpaperVideo.getHolder().addCallback(new SurfaceHolder.Callback() {
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
        wallpaperVideo.setFocusable(false);
        wallpaperVideo.setVisibility(View.GONE);
        desktopModule.addView(wallpaperVideo, matchParent());

        // 4K 视频切换时的 1080p 快照交叉淡化层（位于 VideoView 之上、scrim 之下）
        wallpaperTransitionLayer = new FrameLayout(this);
        wallpaperTransitionLayer.setBackgroundColor(Color.TRANSPARENT);
        wallpaperTransitionLayer.setVisibility(View.GONE);

        transitionOld = new ImageView(this);
        transitionOld.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperTransitionLayer.addView(transitionOld, matchParent());

        transitionNew = new ImageView(this);
        transitionNew.setScaleType(ImageView.ScaleType.CENTER_CROP);
        transitionNew.setAlpha(0f);
        wallpaperTransitionLayer.addView(transitionNew, matchParent());
        wallpaperTransitionLayer.setTranslationX(WALLPAPER_NUDGE_X);
        wallpaperTransitionLayer.setTranslationY(WALLPAPER_NUDGE_Y);
        wallpaperTransitionLayer.setScaleX(WALLPAPER_OVERSCAN);
        wallpaperTransitionLayer.setScaleY(WALLPAPER_OVERSCAN);
        desktopModule.addView(wallpaperTransitionLayer, matchParent());

        // 有写权限时先把旧的私有壁纸迁到公共目录，再扫描
        ensurePublicWallpaperAccess();
        removeLegacyBuiltinCopy();
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        cleanup4KCacheAsync();

        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        desktopModule.addView(scrim, matchParent());

        // ---- 桌面控件层：时钟 / 天气 / 底部 Dock / 壁纸左右键 / 提示 ----
        widgets = new DesktopWidgets(this);
        homeLayer = widgets.getLayer();
        desktopModule.addView(homeLayer, matchParent());

        // ---- 抽屉层：应用网格 ----
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(Color.TRANSPARENT);
        drawer.setVisibility(View.GONE);

        drawerFrostView = new ImageView(this);
        drawerFrostView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        drawer.addView(drawerFrostView, matchParent());

        View drawerTint = new View(this);
        drawerTint.setBackgroundColor(0x8C0B1017);
        drawer.addView(drawerTint, matchParent());

        grid = new GridView(this);
        grid.setNumColumns(iconsPerRow());
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(26), dp(26), dp(26), dp(26));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(new AppAdapter());
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(allApps.get(position));
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                drawerSelectedPosition = position;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child instanceof ViewGroup) {
                        View mask = child.findViewById(0x2003);
                        if (mask != null) {
                            mask.setBackgroundResource(child == view
                                    ? R.drawable.dock_focus : R.drawable.tile_bg);
                        }
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child instanceof ViewGroup) {
                        View mask = child.findViewById(0x2003);
                        if (mask != null) {
                            mask.setBackgroundResource(R.drawable.tile_bg);
                        }
                    }
                }
            }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && grid.getSelectedItemPosition() < iconsPerRow()) {
                    closeDrawer();
                    return true;
                }
                return false;
            }
        });
        drawer.addView(grid, matchParent());
        desktopModule.addView(drawer, matchParent());

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

    private List<BackgroundItem> scanVideoBackgrounds() {
        List<BackgroundItem> items = new ArrayList<>();
        for (File file : store.listVideos(store.videoDir())) {
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file)));
        }
        return items;
    }

    private void reloadVideoBackgrounds() {
        List<BackgroundItem> found = scanVideoBackgrounds();
        if (found.size() == videoItems.size()) {
            boolean same = true;
            for (int i = 0; i < found.size(); i++) {
                BackgroundItem a = videoItems.get(i);
                BackgroundItem b = found.get(i);
                if (!a.name.equals(b.name) || a.stamp != b.stamp) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return;
            }
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
        bgFront.setVisibility(View.VISIBLE);
        bgBack.setVisibility(View.VISIBLE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        // 播放视频时 4K 静态模式被关掉了；这里必须重新进入图片模式，
        // 否则 applyWallpaperByName/switch4KStatic 都会因为模式未开启直接 return，
        // 表现为「换了壁纸但画面不变」。
        refresh4KStaticMode();
    }

    // ---------- 4K 静态壁纸（设备端 x264 单帧编码） ----------

    boolean fourKStaticEnabled() {
        return reveal4KVideo && x264Available && !fourKItems.isEmpty();
    }

    /** 后台清理：图片已删除或已变更后遗留的 4K 缓存 */
    private void cleanup4KCacheAsync() {
        if (cacheCleanupStarted) {
            return;
        }
        cacheCleanupStarted = true;
        final List<BackgroundItem> snapshot = new ArrayList<>(fourKItems);
        final File base = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null) : getFilesDir();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = new File(base, FOUR_K_CACHE_DIR);
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) {
                    return;
                }
                Set<String> keep = new HashSet<>();
                for (BackgroundItem item : snapshot) {
                    keep.add(fourKCacheFile(item).getName());
                }
                int deleted = 0;
                for (File file : files) {
                    if (!file.isFile()) {
                        continue;
                    }
                    if (!keep.contains(file.getName())) {
                        if (file.delete()) {
                            deleted++;
                        }
                    }
                }
                Log.i(TAG, "4K cache cleanup: kept=" + keep.size()
                        + " deleted=" + deleted);
            }
        }, "4k-cache-cleanup").start();
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
            closeQuietly(in);
        }
    }

    private void reload4KBackgrounds() {
        List<BackgroundItem> found = scan4KBackgrounds();
        if (same4KBackgrounds(found)) {
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

    private boolean same4KBackgrounds(List<BackgroundItem> found) {
        if (found.size() != fourKItems.size()) {
            return false;
        }
        for (int i = 0; i < found.size(); i++) {
            BackgroundItem a = fourKItems.get(i);
            BackgroundItem b = found.get(i);
            if (!a.name.equals(b.name) || a.stamp != b.stamp
                    || !TextUtils.equals(a.filePath, b.filePath)) {
                return false;
            }
        }
        return true;
    }

    private List<BackgroundItem> scan4KBackgrounds() {
        List<BackgroundItem> items = new ArrayList<>();
        // 内置壁纸不落盘：先入列表，位图直接从 APK assets 读；排序时固定排最后
        items.add(BUILTIN_ITEM);
        for (File file : store.listImages(store.staticDir())) {
            // 旧版本把内置图复制进了目录，现在改为直接读 APK，跳过遗留副本避免重名
            if (BUILTIN_WALLPAPER.equals(file.getName())) {
                continue;
            }
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file), false));
        }
        // 有自定义排序时按 order 排，没列出的按文件名排在后面
        final List<String> order = readBackgroundOrder();
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            if (!rank.containsKey(order.get(i))) {
                rank.put(order.get(i), i);
            }
        }
        Collections.sort(items, new Comparator<BackgroundItem>() {
            @Override
            public int compare(BackgroundItem a, BackgroundItem b) {
                // 内置壁纸固定排在最后，不受自定义排序影响
                if (a.builtin != b.builtin) {
                    return a.builtin ? 1 : -1;
                }
                int ra = rank.containsKey(a.name) ? rank.get(a.name) : Integer.MAX_VALUE;
                int rb = rank.containsKey(b.name) ? rank.get(b.name) : Integer.MAX_VALUE;
                if (ra != rb) {
                    return ra < rb ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return items;
    }

    private List<String> readBackgroundOrder() {
        List<String> order = new ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_BG_ORDER, "");
        if (TextUtils.isEmpty(raw)) {
            return order;
        }
        for (String line : raw.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                order.add(name);
            }
        }
        return order;
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
        if (bgFront != null) {
            bgFront.setImageDrawable(null);
            bgFront.setVisibility(View.INVISIBLE);
        }
        if (bgBack != null) {
            bgBack.setImageDrawable(null);
            bgBack.setVisibility(View.INVISIBLE);
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
        if (wallpaperVideo == null) {
            return;
        }
        if (!fourKStaticEnabled()) {
            fourKStatic.setMode(false);
            fourKStatic.reset();
            fourKStatic.stop();
            if (bgFront != null) {
                bgFront.setVisibility(View.VISIBLE);
                bgBack.setVisibility(View.VISIBLE);
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
            bgFront.setImageBitmap(bitmap);
            bgFront.setAlpha(1f);
            bgBack.setAlpha(0f);
            slideshow.adoptCurrent(fourKIndex, item.name);
            wallpaperBlurKey = null;

            transitionOld.animate().cancel();
            transitionNew.animate().cancel();
            transitionOld.setImageDrawable(null);
            transitionOld.setAlpha(0f);
            transitionNew.setImageBitmap(bitmap);
            transitionNew.setAlpha(1f);
            wallpaperTransitionLayer.setAlpha(1f);
            wallpaperTransitionLayer.setVisibility(View.VISIBLE);
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

    File fourKCacheFile(BackgroundItem item) {
        File dir = new File(getExternalFilesDir(null), FOUR_K_CACHE_DIR);
        dir.mkdirs();
        String safe = item.name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safe + "_" + item.stamp + "_"
                + FOUR_K_CACHE_VERSION + ".mp4");
    }

    /** 当前 4K 壁纸已编码好的缓存文件，没有则返回 null */
    File current4KCacheOrNull() {
        if (current4KName == null) {
            return null;
        }
        for (BackgroundItem item : fourKItems) {
            if (item.name.equals(current4KName)) {
                File cache = fourKCacheFile(item);
                return cache.exists() && cache.length() > 0 ? cache : null;
            }
        }
        return null;
    }

    /** 视频层在准备期间是空的黑 surface，用当前静态壁纸铺一层封面挡住 */
    void showVideoCover() {
        if (wallpaperTransitionLayer == null || transitionNew == null) {
            return;
        }
        if (wallpaperTransitionLayer.getVisibility() == View.VISIBLE
                && transitionNew.getDrawable() != null) {
            return;
        }
        Bitmap cover = currentImageCoverBitmap();
        if (cover == null) {
            return;
        }
        if (transitionOld != null) {
            transitionOld.setImageDrawable(null);
        }
        transitionNew.setImageBitmap(cover);
        transitionNew.setAlpha(1f);
        wallpaperTransitionLayer.setAlpha(1f);
        wallpaperTransitionLayer.setVisibility(View.VISIBLE);
        Log.i(TAG, "video cover shown");
    }

    void hideVideoCover() {
        if (wallpaperTransitionLayer == null || transitionNew == null) {
            return;
        }
        if (transitionNew.getDrawable() == null) {
            return;
        }
        wallpaperTransitionLayer.setVisibility(View.GONE);
        transitionOld.setImageDrawable(null);
        transitionNew.setImageDrawable(null);
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
        if (wallpaperTransitionLayer == null
                || wallpaperTransitionLayer.getVisibility() != View.VISIBLE) {
            return;
        }
        if (wallpaperTransitionLayer != null) {
            wallpaperTransitionLayer.animate().cancel();
            wallpaperTransitionLayer.setVisibility(View.GONE);
            wallpaperTransitionLayer.setAlpha(1f);
            transitionOld.setImageDrawable(null);
            transitionNew.setImageDrawable(null);
        }
        if (wallpaperVideo != null) {
            wallpaperVideo.setVisibility(View.VISIBLE);
        }
        Log.i(TAG, "4K UI cover hidden (hard cut)");
        revealStartupFromBlack();
        // 画面已经清晰，趁空闲把下一张预编码好，用户切过去时可直接命中缓存
        fourKStatic.prefetch();
    }

    /** 后台预编码下一张 4K 壁纸；由 FourKStaticPlayer 保证同一时刻只有一个编码任务 */
    int[] targetSizeFor(BackgroundItem item) {
        if (readImageBounds(item) == null) {
            return null;
        }
        // 固定 4K 画布：所有壁纸编码尺寸一致，视频 plane 切换时不需要重配缩放/色彩，
        // 避免首帧按上一张的配置显示（放大、偏蓝）。比例不同的图在 decode4KBitmap 里居中裁切。
        return new int[]{FOUR_K_W, FOUR_K_H};
    }

    Bitmap decode4KBitmap(BackgroundItem item, int targetW, int targetH) {
        int[] bounds = readImageBounds(item);
        if (bounds == null) {
            return null;
        }
        int sample = 1;
        while (bounds[0] / (sample * 2) >= targetW
                && bounds[1] / (sample * 2) >= targetH) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = decodeImage(item, options);
        if (src == null) {
            return null;
        }
        Bitmap dst = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        float scale = Math.max((float) targetW / src.getWidth(),
                (float) targetH / src.getHeight());
        int cropW = Math.min(src.getWidth(), Math.round(targetW / scale));
        int cropH = Math.min(src.getHeight(), Math.round(targetH / scale));
        int left = (src.getWidth() - cropW) / 2;
        int top = (src.getHeight() - cropH) / 2;
        Rect srcRect = new Rect(left, top, left + cropW, top + cropH);
        Rect dstRect = new Rect(0, 0, targetW, targetH);
        // UI 和视频统一 overscan；低清目标尺寸同样适用
        canvas.save();
        canvas.scale(WALLPAPER_OVERSCAN, WALLPAPER_OVERSCAN,
                targetW / 2f, targetH / 2f);
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        canvas.restore();
        src.recycle();
        return dst;
    }

    /** 读图片尺寸；文件壁纸和 APK 内置壁纸都支持，失败返回 null */
    int[] readImageBounds(BackgroundItem item) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        if (item.assetName != null) {
            InputStream in = null;
            try {
                in = getAssets().open(assetPath(item.assetName));
                BitmapFactory.decodeStream(in, null, bounds);
            } catch (Throwable t) {
                Log.w(TAG, "read asset bounds failed: " + item.assetName, t);
                return null;
            } finally {
                closeQuietly(in);
            }
        } else {
            BitmapFactory.decodeFile(item.filePath, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        return new int[]{bounds.outWidth, bounds.outHeight};
    }

    Bitmap decodeImage(BackgroundItem item, BitmapFactory.Options options) {
        if (item.assetName != null) {
            InputStream in = null;
            try {
                in = getAssets().open(assetPath(item.assetName));
                return BitmapFactory.decodeStream(in, null, options);
            } catch (Throwable t) {
                Log.w(TAG, "decode asset failed: " + item.assetName, t);
                return null;
            } finally {
                closeQuietly(in);
            }
        }
        return BitmapFactory.decodeFile(item.filePath, options);
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
            gridIconCache.clear();
            dockShadowCache.clear();
            drawerSelectedPosition = 0;
            if (grid != null) {
                grid.setNumColumns(iconsPerRow());
                grid.setAdapter(new AppAdapter());
            }
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

    private void openRemoteImport() {
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
        if (desktopModule == null) {
            return;
        }
        desktopModule.animate().cancel();
        if (opening && !shouldFloatMenu()) {
            desktopModule.animate().translationY(dp(MENU_HEIGHT_DP))
                    .setDuration(MENU_ANIM_MS).setInterpolator(OPEN_EASE).start();
        } else if (opening) {
            desktopModule.setTranslationY(0f);
        } else {
            desktopModule.animate().translationY(0f)
                    .setDuration(MENU_ANIM_MS).setInterpolator(CLOSE_EASE).start();
        }
    }

    void resetDesktopOffsetForMenu() {
        if (desktopModule != null) {
            desktopModule.animate().cancel();
            desktopModule.setTranslationY(0f);
        }
    }

    /** 菜单开着时 4K 模式切换：重新计算桌面是否需要下压 */
    private void relayoutDesktopForMenu() {
        if (!isMenuOpen() || desktopModule == null) {
            return;
        }
        desktopModule.animate().cancel();
        float target = shouldFloatMenu() ? 0f : dp(MENU_HEIGHT_DP);
        desktopModule.animate().translationY(target)
                .setDuration(MENU_ANIM_MS)
                .setInterpolator(target == 0f ? CLOSE_EASE : OPEN_EASE).start();
    }

    // ---------- 4K 静态播放器（FourKStaticPlayer）宿主 ----------

    boolean isVideoWallpaperMode() {
        return videoWallpaperMode;
    }

    void setVideoSurfaceLost(boolean lost) {
        videoSurfaceLost = lost;
    }

    VideoView wallpaperVideoView() {
        return wallpaperVideo;
    }

    ImageView bgFrontView() {
        return bgFront;
    }

    ImageView bgBackView() {
        return bgBack;
    }

    FrameLayout transitionLayerView() {
        return wallpaperTransitionLayer;
    }

    ImageView transitionOldView() {
        return transitionOld;
    }

    ImageView transitionNewView() {
        return transitionNew;
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
        File[] files = fourKCacheDir().listFiles();
        int count = files == null ? 0 : files.length;
        long bytes = 0;
        if (files != null) {
            for (File f : files) {
                bytes += f.length();
            }
        }
        return "4K 缓存：已生成 " + count + " / " + fourKItems.size()
                + " 张，占用 " + formatSize(bytes) + "。";
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---------- 缩略图磁盘缓存 ----------

    private File thumbCacheDir() {
        File base = getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir();
        File dir = new File(base, "thumb_cache");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private String thumbFileName(BackgroundItem item) {
        String safe = item.name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe + "_" + item.stamp + (WallpaperStore.isVideoName(item.name) ? "_v.jpg" : "_i.jpg");
    }

    private Bitmap loadThumbFromDisk(BackgroundItem item) {
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            if (!file.exists() || file.length() == 0L) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            Log.w(TAG, "load thumb cache failed: " + item.name, t);
            return null;
        }
    }

    private void saveThumbToDisk(BackgroundItem item, Bitmap bitmap) {
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
            out.close();
            if (file.exists()) {
                file.delete();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "save thumb cache failed: " + item.name, t);
        }
    }

    private void deleteThumbFor(BackgroundItem item) {
        thumbnailCache.remove(item.name + "|" + item.stamp);
        try {
            File file = new File(thumbCacheDir(), thumbFileName(item));
            if (file.exists() && file.delete()) {
                Log.i(TAG, "thumb cache deleted: " + item.name);
            }
        } catch (Throwable t) {
            Log.w(TAG, "delete thumb cache failed: " + item.name, t);
        }
    }

    /** 壁纸列表变化后清掉孤儿缩略图（源文件已删的那些） */
    private void pruneThumbCacheAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> keep = new HashSet<>();
                    for (BackgroundItem item : fourKItems) {
                        keep.add(thumbFileName(item));
                    }
                    for (BackgroundItem item : videoItems) {
                        keep.add(thumbFileName(item));
                    }
                    for (BackgroundItem item : backgroundItems) {
                        keep.add(thumbFileName(item));
                    }
                    File[] files = thumbCacheDir().listFiles();
                    int removed = 0;
                    if (files != null) {
                        for (File file : files) {
                            if (!keep.contains(file.getName())) {
                                if (file.delete()) {
                                    removed++;
                                }
                            }
                        }
                    }
                    Log.i(TAG, "thumb cache pruned: kept=" + keep.size() + " removed=" + removed);
                } catch (Throwable t) {
                    Log.w(TAG, "prune thumb cache failed", t);
                }
            }
        }, "thumb-prune").start();
    }

    void loadThumbnail(final ImageView view, final BackgroundItem item) {
        final String key = item.name + "|" + item.stamp;
        view.setTag(key);
        Bitmap cached = thumbnailCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        Bitmap onDisk = loadThumbFromDisk(item);
        if (onDisk != null) {
            thumbnailCache.put(key, onDisk);
            view.setImageBitmap(onDisk);
            return;
        }
        view.setImageDrawable(null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodeThumbnail(item, 400, 225);
                if (bitmap == null) {
                    return;
                }
                thumbnailCache.put(key, bitmap);
                saveThumbToDisk(item, bitmap);
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

    private Bitmap decodeThumbnail(BackgroundItem item, int targetW, int targetH) {
        if (WallpaperStore.isVideoName(item.name)) {
            return item.filePath == null ? null : decodeVideoThumbnail(item.filePath, targetW);
        }
        try {
            int[] bounds = readImageBounds(item);
            if (bounds == null) {
                return null;
            }
            int sample = 1;
            while (bounds[0] / (sample * 2) >= targetW
                    && bounds[1] / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return decodeImage(item, options);
        } catch (Throwable t) {
            Log.w(TAG, "decode thumbnail failed: " + item.name, t);
            return null;
        }
    }

    private Bitmap decodeVideoThumbnail(String path, int targetW) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            Bitmap frame = null;
            try {
                frame = retriever.getScaledFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW * 2, targetW);
            } catch (Throwable ignored) {
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                // 4K H.264 用 MediaMetadataRetriever 取不到帧（返回 null），
                // 退回用 MediaCodec 硬解一帧。
                frame = decodeVideoFrameWithCodec(path, targetW);
            }
            if (frame == null) {
                return null;
            }
            if (frame.getWidth() <= targetW * 2) {
                return frame;
            }
            int height = Math.max(1, Math.round(frame.getHeight() * (targetW * 2f)
                    / frame.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(frame, targetW * 2, height, true);
            if (scaled != frame) {
                frame.recycle();
            }
            return scaled;
        } catch (Throwable t) {
            Log.w(TAG, "decode video thumbnail failed: " + path, t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用 MediaCodec 解出视频第 1 秒附近的一帧；MediaMetadataRetriever 拿不到 4K 帧时兜底 */
    static Bitmap decodeVideoFrameWithCodec(String path, int targetW) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(track);
            extractor.seekTo(1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = System.currentTimeMillis() + 10000L;
            boolean inputDone = false;
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Bitmap frame = null;
                    if (info.size > 0) {
                        Image image = codec.getOutputImage(outIndex);
                        if (image != null) {
                            frame = yuvImageToBitmap(image, targetW);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if (frame != null) {
                        Log.i(TAG, "video thumb via codec: " + new File(path).getName()
                                + " " + frame.getWidth() + "x" + frame.getHeight());
                        return frame;
                    }
                    if (eos) {
                        break;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "codec thumb failed: " + path, t);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (extractor != null) {
                    extractor.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** YUV_420_888 的 Image 转 Bitmap（走 NV21 + JPEG，省掉手写 YUV→RGB） */
    static Bitmap yuvImageToBitmap(Image image, int targetW) {
        int width = image.getWidth() & ~1;
        int height = image.getHeight() & ~1;
        if (width <= 0 || height <= 0) {
            return null;
        }
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            yBuffer.get(nv21, row * width, width);
        }
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int offset = width * height;
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                nv21[offset + row * width + col * 2] =
                        vBuffer.get(row * vRowStride + col * vPixelStride);
                nv21[offset + row * width + col * 2 + 1] =
                        uBuffer.get(row * uRowStride + col * uPixelStride);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(new Rect(0, 0, width, height), 85, jpeg);
        byte[] data = jpeg.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        int sample = 1;
        while (sample < 16 && width / (sample * 2) >= targetW * 2) {
            sample *= 2;
        }
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
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
        saveBackgroundOrder();
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

    private void saveBackgroundOrder() {
        StringBuilder sb = new StringBuilder();
        for (BackgroundItem item : fourKItems) {
            sb.append(item.name).append('\n');
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_BG_ORDER, sb.toString()).apply();
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
        deleteCacheFor(item);
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

    private File fourKCacheDir() {
        File base = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null) : getFilesDir();
        return new File(base, FOUR_K_CACHE_DIR);
    }

    /** 与 fourKCacheFile() 的命名前缀保持一致 */
    private static String cachePrefixFor(BackgroundItem item) {
        return item.name.replaceAll("[^a-zA-Z0-9._-]", "_") + "_";
    }

    long cacheSizeFor(BackgroundItem item) {
        File[] files = fourKCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        String prefix = cachePrefixFor(item);
        long total = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix)) {
                total += file.length();
            }
        }
        return total;
    }

    long totalCacheSize() {
        File[] files = fourKCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    int deleteCacheFor(BackgroundItem item) {
        File[] files = fourKCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        String prefix = cachePrefixFor(item);
        int deleted = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix) && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 清空全部缓存：4K 转码缓存（静态壁纸的视频缓存）+ 缩略图缓存。返回删除的文件数。 */
    private long thumbCacheSize() {
        File[] files = thumbCacheDir().listFiles();
        if (files == null) {
            return 0;
        }
        long total = 0;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    private int deleteAllCache() {
        int deleted = 0;
        File[] files = fourKCacheDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.delete()) {
                    deleted++;
                }
            }
        }
        File[] thumbs = thumbCacheDir().listFiles();
        if (thumbs != null) {
            for (File file : thumbs) {
                if (file.isFile() && file.delete()) {
                    deleted++;
                }
            }
        }
        thumbnailCache.clear();
        return deleted;
    }

    void confirmClearAllCache() {
        final long size = totalCacheSize() + thumbCacheSize();
        if (size <= 0) {
            Toast.makeText(this, "没有缓存", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空缓存")
                .setMessage("将删除全部缓存（" + formatSize(size)
                        + "）：静态壁纸的 4K 视频缓存 + 缩略图缓存。\n下次使用会重新生成，原始壁纸不会删除。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteAllCache();
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
        final List<File> usbRoots = usbImportRoots();
        final List<String> labels = new ArrayList<>();
        labels.add("从本机导入");
        final int usbIndex = usbRoots.isEmpty() ? -1 : labels.size();
        if (usbIndex >= 0) {
            labels.add("从 USB 设备导入");
        }
        labels.add("远程导入（手机/电脑网页上传）");
        new AlertDialog.Builder(this)
                .setTitle("导入壁纸")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            importFromLocal();
                        } else if (usbIndex >= 0 && which == usbIndex) {
                            pickUsbRootAndBrowse(usbRoots);
                        } else {
                            openRemoteImport();
                        }
                    }
                })
                .show();
    }

    /** 从本机（内置存储）导入：直接进根目录浏览，不再弹位置选择。 */
    private void importFromLocal() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE);
            return;
        }
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.isDirectory()) {
            showImportRoots();
            return;
        }
        showFileBrowser(root);
    }

    /** 当前接入的 U 盘 / 外接盘（/storage 下非模拟卷、/mnt 下 usb|media）。 */
    private List<File> usbImportRoots() {
        List<File> candidates = new ArrayList<>();
        addImportRoots(candidates, new File("/storage"), false);
        addImportRoots(candidates, new File("/mnt"), true);
        List<File> roots = new ArrayList<>();
        for (File dir : candidates) {
            if (isRealMountPoint(dir)) {
                roots.add(dir);
            }
        }
        Log.i(TAG, "usb roots: " + roots + " (candidates=" + candidates + ")");
        return roots;
    }

    /** 是否出现在 /proc/mounts 里——厂商会预建空的 /mnt/usb 之类目录，不能只看目录存在。 */
    private boolean isRealMountPoint(File dir) {
        final String path = dir.getAbsolutePath();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length < 2) {
                    continue;
                }
                String mountPoint = parts[1].replace("\\040", " ");
                if (mountPoint.equals(path) || mountPoint.equals(path + "/")) {
                    return true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "read /proc/mounts failed: " + e);
            return isStorageVolumeRemovable(dir);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** /proc/mounts 读不到时的兜底：问 StorageManager 有没有可移动卷挂在 /storage/<uuid>。 */
    private boolean isStorageVolumeRemovable(File dir) {
        try {
            android.os.storage.StorageManager sm =
                    (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
            if (sm == null) {
                return false;
            }
            for (android.os.storage.StorageVolume volume : sm.getStorageVolumes()) {
                if (!volume.isRemovable()) {
                    continue;
                }
                String uuid = volume.getUuid();
                if (uuid != null && dir.getAbsolutePath().endsWith("/" + uuid)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void pickUsbRootAndBrowse(final List<File> roots) {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE);
            return;
        }
        if (roots.isEmpty()) {
            Toast.makeText(this, "没有检测到 USB 设备", Toast.LENGTH_LONG).show();
            return;
        }
        if (roots.size() == 1) {
            showFileBrowser(roots.get(0));
            return;
        }
        final String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            labels[i] = roots.get(i).getAbsolutePath();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 USB 设备")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showFileBrowser(roots.get(which));
                    }
                })
                .show();
    }

    private void requestImport() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            showImportRoots();
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ_STORAGE);
        }
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
        if (requestCode != REQ_READ_STORAGE) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showImportRoots();
        } else {
            Toast.makeText(this, "需要存储读取权限才能导入壁纸", Toast.LENGTH_LONG).show();
        }
    }

    private void showImportRoots() {
        final List<File> roots = new ArrayList<>();
        File sd = Environment.getExternalStorageDirectory();
        if (sd != null && sd.isDirectory()) {
            roots.add(sd);
        }
        addImportRoots(roots, new File("/storage"), false);
        addImportRoots(roots, new File("/mnt"), true);
        if (roots.isEmpty()) {
            Toast.makeText(this, "没有找到可访问的存储位置", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] labels = new String[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            String prefix = root.equals(sd) ? "内置存储" : "U 盘 / 外部存储";
            labels[i] = prefix + "  " + root.getAbsolutePath();
        }
        new AlertDialog.Builder(this)
                .setTitle("从哪个位置导入")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showFileBrowser(roots.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 只认真实的可移动卷名：usb / usb_x / media-uuid；排除内部卷 emulated、self、media_rw。 */
    private static boolean isUsbLikeVolume(String name) {
        if ("emulated".equals(name) || "self".equals(name) || "media_rw".equals(name)) {
            return false;
        }
        return name.startsWith("usb") || name.startsWith("media-") || name.startsWith("media_")
                || (name.startsWith("media") && name.indexOf('-') > 0);
    }

    private void addImportRoots(List<File> roots, File parent, boolean usbOnly) {
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        List<File> dirs = new ArrayList<>();
        for (File child : children) {
            if (!child.isDirectory() || !child.canRead()) {
                continue;
            }
            String name = child.getName().toLowerCase(Locale.US);
            if ("emulated".equals(name) || "self".equals(name)) {
                continue;
            }
            if (usbOnly && !isUsbLikeVolume(name)) {
                continue;
            }
            dirs.add(child);
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File dir : dirs) {
            boolean duplicated = false;
            for (File existing : roots) {
                if (existing.getAbsolutePath().equals(dir.getAbsolutePath())) {
                    duplicated = true;
                    break;
                }
            }
            if (!duplicated) {
                roots.add(dir);
            }
        }
    }

    private void showFileBrowser(final File dir) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            Toast.makeText(this, "无法读取该目录", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean wantVideo = wallpaperManager.getType() == WallpaperManagerPage.VIDEOS;
        File[] children = dir.listFiles();
        List<File> dirs = new ArrayList<>();
        final List<File> images = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    dirs.add(child);
                } else if (child.isFile()) {
                    boolean match = wantVideo
                            ? WallpaperStore.isVideoName(child.getName()) : WallpaperStore.isImageName(child.getName());
                    if (match) {
                        images.add(child);
                    }
                }
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(images, byName);

        final List<String> labels = new ArrayList<>();
        final List<File> targets = new ArrayList<>();
        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            labels.add("⬆ 上一级");
            targets.add(parent);
        }
        if (!images.isEmpty()) {
            labels.add(wantVideo
                    ? "导入本目录全部视频（" + images.size() + " 个）"
                    : "导入本目录全部图片（" + images.size() + " 张）");
            targets.add(null);
        }
        for (File child : dirs) {
            labels.add("📁 " + child.getName());
            targets.add(child);
        }
        for (File image : images) {
            labels.add((wantVideo ? "🎬 " : "🖼 ") + image.getName());
            targets.add(image);
        }
        if (labels.isEmpty()) {
            labels.add("（空目录）");
            targets.add(null);
        }

        new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File target = targets.get(which);
                        if (target == null) {
                            if (labels.get(which).startsWith("导入本目录")) {
                                if (wantVideo) {
                                    importVideos(images);
                                } else {
                                    importWallpapers(images);
                                }
                            }
                        } else if (target.isDirectory()) {
                            showFileBrowser(target);
                        } else {
                            List<File> one = new ArrayList<>();
                            one.add(target);
                            if (wantVideo) {
                                importVideos(one);
                            } else {
                                importWallpapers(one);
                            }
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importWallpapers(final List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Toast.makeText(this, "正在导入 " + files.size() + " 张…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                String lastName = null;
                for (File file : files) {
                    String name = copyWallpaperIntoDevice(file);
                    if (name != null) {
                        ok++;
                        lastName = name;
                    }
                }
                final int okCount = ok;
                final String last = lastName;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (okCount <= 0) {
                            Toast.makeText(LauncherActivity.this,
                                    "导入失败：不是有效图片或无法读取", Toast.LENGTH_LONG).show();
                            return;
                        }
                        reloadBackgrounds();
                        reload4KBackgrounds();
                        refresh4KStaticMode();
                        if (last != null) {
                            applyWallpaperByName(last);
                        }
                        refreshManager();
                        Toast.makeText(LauncherActivity.this,
                                "已导入 " + okCount + " 张", Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "wallpapers imported: " + okCount);
                    }
                });
            }
        }, "wallpaper-import").start();
    }

    private void importVideos(final List<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Toast.makeText(this, "正在导入 " + files.size() + " 个视频…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                for (File file : files) {
                    if (copyVideoIntoDevice(file) != null) {
                        ok++;
                    }
                }
                final int okCount = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        reloadVideoBackgrounds();
                        refreshManager();
                        Toast.makeText(LauncherActivity.this,
                                okCount > 0 ? ("已导入 " + okCount + " 个视频，点一下设为当前")
                                        : "导入失败：不是可播放的视频",
                                Toast.LENGTH_LONG).show();
                        Log.i(TAG, "videos imported: " + okCount);
                    }
                });
            }
        }, "video-import").start();
    }

    /** 校验并复制视频到 files/backgrounds_video，返回新文件名；失败返回 null */
    private String copyVideoIntoDevice(File src) {
        if (src == null || !src.isFile() || !WallpaperStore.isVideoName(src.getName())) {
            return null;
        }
        if (src.length() <= 0) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(src.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (TextUtils.isEmpty(duration) || "0".equals(duration)) {
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "reject video (cannot read): " + src.getName(), t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
        File dest = store.copyInto(src, store.videoDir());
        if (dest == null) {
            Log.w(TAG, "copy video failed: " + src.getName());
            return null;
        }
        return dest.getName();
    }

    /** 校验并复制到 files/backgrounds_4k，返回新文件名；失败返回 null */
    private String copyWallpaperIntoDevice(File src) {
        if (src == null || !src.isFile() || !WallpaperStore.isImageName(src.getName())) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        File dest = store.copyInto(src, store.staticDir());
        if (dest == null) {
            Log.w(TAG, "import wallpaper failed: " + src.getAbsolutePath());
            return null;
        }
        Log.i(TAG, "wallpaper imported: " + src.getAbsolutePath() + " -> " + dest.getName());
        return dest.getName();
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

    /** 交叉淡化后交换前后两层，下一张继续从 bgBack 进来 */
    void swapBackgroundViews(ImageView front, ImageView back) {
        bgFront = front;
        bgBack = back;
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
        List<BackgroundItem> found = scan4KBackgrounds();
        if (found.isEmpty()) {
            found = scanBackgrounds();
        }
        if (sameBackgrounds(found)) {
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

    private boolean sameBackgrounds(List<BackgroundItem> found) {
        if (found.size() != backgroundItems.size()) {
            return false;
        }
        for (int i = 0; i < found.size(); i++) {
            BackgroundItem a = backgroundItems.get(i);
            BackgroundItem b = found.get(i);
            if (!a.name.equals(b.name)
                    || a.stamp != b.stamp
                    || !TextUtils.equals(a.assetName, b.assetName)
                    || !TextUtils.equals(a.filePath, b.filePath)) {
                return false;
            }
        }
        return true;
    }

    private List<BackgroundItem> scanBackgrounds() {
        List<BackgroundItem> items = new ArrayList<>();
        for (File file : store.listImages(store.legacyImageDir())) {
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    WallpaperStore.stampOf(file)));
        }
        if (!items.isEmpty()) {
            return items;
        }
        // 设备上没有 1080p 壁纸：直接用内置壁纸兜底，APK 里不再单独打包一份 1080p 副本
        items.add(BUILTIN_ITEM);
        return items;
    }

    /** 列表为空或解码失败时的最后兜底：直接画内置壁纸，连它都读不出来才用深色底 */
    void showBuiltinFallback() {
        Bitmap bitmap = loadBackground(BUILTIN_ITEM);
        bgFront.setImageDrawable(null);
        if (bitmap != null) {
            bgFront.setBackgroundColor(Color.TRANSPARENT);
            bgFront.setImageBitmap(bitmap);
        } else {
            bgFront.setBackgroundColor(0xFF0B1017);
        }
        bgFront.setAlpha(1f);
    }

    Bitmap loadBackground(BackgroundItem item) {
        if (item.assetName != null) {
            return loadAsset(item.assetName);
        }
        return loadFileCached(item.filePath);
    }

    /** assets 内的路径：带 `/` 的按原样用（内置壁纸在 builtin/ 下），否则当作 backgrounds/ */
    private static String assetPath(String name) {
        return name.indexOf('/') >= 0 ? name : WallpaperStore.LEGACY_IMAGE_DIR + "/" + name;
    }

    private Bitmap loadAsset(String name) {
        String path = assetPath(name);
        InputStream input = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            input = getAssets().open(path);
            BitmapFactory.decodeStream(input, null, bounds);
            input.close();
            input = null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            input = getAssets().open(path);
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Throwable t) {
            Log.w(TAG, "load background asset failed: " + name, t);
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    private Bitmap loadFile(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            Log.w(TAG, "load background file failed: " + path, t);
            return null;
        }
    }

    /**
     * 带单张缓存的解码：4K 预览和 1080p UI 层用的是同一个原图文件，
     * 冷启动/换图时不必解码两遍（一张 4K 图解码约 150-400ms）。
     */
    private Bitmap loadFileCached(String path) {
        if (path == null) {
            return null;
        }
        File file = new File(path);
        String key = path + "|" + file.lastModified() + "|" + file.length();
        if (key.equals(lastLoadedKey) && lastLoadedBitmap != null
                && !lastLoadedBitmap.isRecycled()) {
            return lastLoadedBitmap;
        }
        Bitmap bitmap = loadFile(path);
        if (bitmap != null) {
            lastLoadedBitmap = bitmap;
            lastLoadedKey = key;
        }
        return bitmap;
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        if (width > 0 && height > 0) {
            while (width / (sample * 2) >= 1920 && height / (sample * 2) >= 1080) {
                sample *= 2;
            }
        }
        return sample;
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
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
        if (drawerFrostView == null) {
            return;
        }
        if (fourKStaticEnabled()) {
            drawerFrostView.setImageDrawable(null);
            return;
        }
        Bitmap blurred = getWallpaperBlur();
        if (blurred != null) {
            drawerFrostView.setImageBitmap(blurred);
        } else {
            drawerFrostView.setImageDrawable(null);
        }
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

    /** 把 bgFront 当前实际显示的壁纸抓成 960px 宽的位图 */
    private Bitmap captureWallpaperDisplayed() {
        if (bgFront == null || bgFront.getDrawable() == null) {
            return null;
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        int width = 960;
        int height = Math.max(1, Math.round(width * sh / (float) sw));
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(width / (float) sw, height / (float) sh);
        bgFront.draw(canvas);
        return out;
    }

    private Bitmap blurBitmap(Bitmap source) {
        return blurBitmap(source, 24f);
    }

    private Bitmap blurBitmap(Bitmap source, float radius) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        Bitmap output = null;
        RenderScript rs = null;
        Allocation in = null;
        Allocation out = null;
        ScriptIntrinsicBlur blur = null;
        try {
            output = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            rs = RenderScript.create(this);
            in = Allocation.createFromBitmap(rs, source);
            out = Allocation.createFromBitmap(rs, output);
            blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(radius);
            blur.setInput(in);
            blur.forEach(out);
            out.copyTo(output);
            return output;
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper blur failed", t);
            if (output != null) {
                output.recycle();
            }
            return null;
        } finally {
            if (blur != null) {
                blur.destroy();
            }
            if (in != null) {
                in.destroy();
            }
            if (out != null) {
                out.destroy();
            }
            if (rs != null) {
                rs.destroy();
            }
        }
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
                final List<AppEntry> scanned = scanApps();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appScanRunning = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        allApps.clear();
                        allApps.addAll(scanned);
                        byKey.clear();
                        for (AppEntry e : allApps) {
                            byKey.put(e.key, e);
                        }
                        if (grid != null && grid.getAdapter() instanceof BaseAdapter) {
                            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                        }
                        refreshDock();
                        if (getCurrentFocus() == null && !desktopFocusCleared
                                && widgets != null) {
                            focusFirstDockItem();
                        }
                        Log.i(TAG, "deferred app scan done: " + allApps.size());
                    }
                });
            }
        }, "app-scan").start();
    }

    /** 重新读取应用列表 + 最近使用，并刷新抽屉和底部栏 */
    private void reloadApps() {
        loadApps();
        if (grid != null && grid.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
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
                final List<AppEntry> scanned = scanApps();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        appScanRunning = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (sameAppList(scanned)) {
                            return;
                        }
                        allApps.clear();
                        allApps.addAll(scanned);
                        byKey.clear();
                        for (AppEntry e : allApps) {
                            byKey.put(e.key, e);
                        }
                        if (grid != null && grid.getAdapter() instanceof BaseAdapter) {
                            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                        }
                        refreshDock();
                    }
                });
            }
        }, "app-scan").start();
    }

    /** 与当前 allApps 的应用集合是否一致（顺序按 label 排序） */
    private boolean sameAppList(List<AppEntry> scanned) {
        if (scanned.size() != allApps.size()) {
            return false;
        }
        for (int i = 0; i < scanned.size(); i++) {
            if (!scanned.get(i).key.equals(allApps.get(i).key)) {
                return false;
            }
        }
        return true;
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
        if (wallpaperVideo != null && wallpaperVideo.isPlaying()) {
            wallpaperVideo.pause();
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
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationY(screenHeight);
        drawer.setAlpha(0f);
        drawer.animate().translationY(0f).alpha(1f).setDuration(OPEN_MS)
                .setInterpolator(OPEN_EASE).start();
        grid.requestFocus();
        grid.setSelection(0);
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        homeLayer.setVisibility(View.VISIBLE);
        drawer.animate().translationY(screenHeight).alpha(0f).setDuration(CLOSE_MS)
                .setInterpolator(CLOSE_EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        drawer.setVisibility(View.GONE);
                        showHomeLayer(0);
                    }
                }).start();
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

    /**
     * 打开时间持久化格式：每行 "组件|时间戳(ms)"。
     * 旧版本只有顺序（KEY_RECENT），首次升级时按原顺序迁移成合成时间。
     */
    private void loadOpenTimes() {
        openTimes.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.contains(KEY_OPEN_TIMES)) {
            migrateOldRecent(sp);
            return;
        }
        String raw = sp.getString(KEY_OPEN_TIMES, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        for (String line : raw.split("\\n")) {
            int split = line.lastIndexOf('|');
            if (split <= 0 || split >= line.length() - 1) {
                continue;
            }
            try {
                openTimes.put(line.substring(0, split),
                        Long.parseLong(line.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                // 单条坏数据不影响其它记录
            }
        }
    }

    /** 旧版 recent_components 只有先后顺序、没有时间，按原顺序补成递减时间 */
    private void migrateOldRecent(SharedPreferences sp) {
        String raw = sp.getString(KEY_RECENT, "");
        long now = System.currentTimeMillis();
        int index = 0;
        if (!TextUtils.isEmpty(raw)) {
            for (String key : raw.split("\\n")) {
                if (TextUtils.isEmpty(key) || !byKey.containsKey(key)) {
                    continue;
                }
                openTimes.put(key, now - (++index) * 60000L);
            }
        }
        sp.edit().remove(KEY_RECENT).apply();
        saveOpenTimes();
    }

    private void saveOpenTimes() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : openTimes.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('|').append(e.getValue());
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_OPEN_TIMES, sb.toString()).apply();
    }

    /** 打开应用：记录这次打开的时间，底部栏按时间重排 */
    private void rememberLaunch(AppEntry entry) {
        openTimes.put(entry.key, System.currentTimeMillis());
        saveOpenTimes();
        refreshDock();
    }

    private void refreshDock() {
        if (widgets != null) {
            widgets.refreshDock();
        }
    }

    /**
     * 底部栏排序：把"打开过的应用"和"新安装的应用"放在同一条时间线上。
     * 单个应用的时间 = max(最后打开时间, 首次安装时间)，按时间倒序取前 7 个。
     * 新装的 App 会立刻排到前面；打开过的 App 按最近一次打开时间前移。
     */
    List<AppEntry> buildDockSource() {
        List<AppEntry> out = new ArrayList<>(allApps);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                long ta = dockTime(a);
                long tb = dockTime(b);
                if (ta != tb) {
                    return Long.compare(tb, ta);
                }
                return 0; // 时间相同保持字母序（Collections.sort 是稳定排序）
            }
        });
        return out;
    }

    private long dockTime(AppEntry entry) {
        Long opened = openTimes.get(entry.key);
        long openTime = opened == null ? 0L : opened;
        return Math.max(openTime, entry.firstInstallTime);
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

        Bitmap dockIconBitmap = squareIcon(entry.icon, iconSize);
        Bitmap iconShadow = dockIconShadow(entry, dockIconBitmap);
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

    /**
     * 图标归一化：先裁掉透明边距，再等比缩放居中放进正方形画布。
     * 这样方形图标、宽 banner、纯 logo 在视觉上大小一致。
     */
    private Bitmap squareIcon(Drawable src, int size) {
        int raw = Math.max(size * 2, 192);
        Bitmap canvasBitmap = Bitmap.createBitmap(raw, raw, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);

        int iw = Math.max(1, src.getIntrinsicWidth());
        int ih = Math.max(1, src.getIntrinsicHeight());
        float preScale = Math.min((float) raw / iw, (float) raw / ih);
        int w = Math.round(iw * preScale);
        int h = Math.round(ih * preScale);
        int left = (raw - w) / 2;
        int top = (raw - h) / 2;
        src.setBounds(left, top, left + w, top + h);
        src.draw(canvas);

        int[] pixels = new int[raw * raw];
        canvasBitmap.getPixels(pixels, 0, raw, 0, 0, raw, raw);
        int minX = raw, minY = raw, maxX = -1, maxY = -1;
        for (int y = 0; y < raw; y++) {
            int row = y * raw;
            for (int x = 0; x < raw; x++) {
                if ((pixels[row + x] >>> 24) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            minX = 0; minY = 0; maxX = raw - 1; maxY = raw - 1;
        }

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas outCanvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int cw = maxX - minX + 1;
        int ch = maxY - minY + 1;
        float scale = Math.min((float) size / cw, (float) size / ch) * 0.94f;
        int dw = Math.max(1, Math.round(cw * scale));
        int dh = Math.max(1, Math.round(ch * scale));
        int dx = (size - dw) / 2;
        int dy = (size - dh) / 2;

        outCanvas.drawBitmap(canvasBitmap,
                new Rect(minX, minY, maxX + 1, maxY + 1),
                new Rect(dx, dy, dx + dw, dy + dh),
                paint);

        // 统一图标外形：所有 app 图标裁成圆角方形（半径约 22%）
        Bitmap masked = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas maskedCanvas = new Canvas(masked);
        Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path round = new Path();
        float radius = size * 0.22f;
        round.addRoundRect(new RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW);
        maskedCanvas.clipPath(round);
        maskedCanvas.drawBitmap(out, 0f, 0f, maskPaint);
        out.recycle();
        return masked;
    }

    private final Map<String, Bitmap> gridIconCache = new HashMap<>();

    /** Dock 图标阴影缓存：应用 key + 图标边长 → 带柔和阴影的位图（比图标大一圈） */
    private final Map<String, Bitmap> dockShadowCache = new HashMap<>();

    /**
     * Dock 图标阴影：把图标 alpha 染成黑色、四周留出一圈空白后做一次模糊，
     * 得到一张比图标略大的柔和投影，垫在图标下面用。
     */
    private Bitmap dockIconShadow(AppEntry entry, Bitmap icon) {
        if (icon == null || icon.isRecycled()) {
            return null;
        }
        int size = icon.getWidth();
        String key = entry.key + "@" + size;
        Bitmap cached = dockShadowCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        int pad = Math.max(4, Math.round(size * 0.12f));
        int total = size + pad * 2;
        Bitmap silhouette = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(silhouette);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        // 往下偏一点点，看起来像被上方光源打出的投影
        canvas.drawBitmap(icon, pad, pad + Math.round(size * 0.04f), paint);

        Bitmap blurred = blurBitmap(silhouette, Math.max(3f, size * 0.09f));
        silhouette.recycle();
        if (blurred == null) {
            return null;
        }

        // 整体压一点透明度：亮壁纸上不会糊成一块黑，暗壁纸上仍能托住图标
        Bitmap shadow = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
        Canvas shadowCanvas = new Canvas(shadow);
        Paint alphaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        alphaPaint.setAlpha(150);
        shadowCanvas.drawBitmap(blurred, 0f, 0f, alphaPaint);
        blurred.recycle();
        dockShadowCache.put(key, shadow);
        return shadow;
    }

    private Bitmap gridIcon(AppEntry entry) {
        Bitmap cached = gridIconCache.get(entry.key);
        if (cached == null) {
            cached = squareIcon(entry.icon, dp(iconSizeDp()));
            gridIconCache.put(entry.key, cached);
        }
        return cached;
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

    private void launch(AppEntry entry) {
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
        List<AppEntry> scanned = scanApps();
        allApps.clear();
        allApps.addAll(scanned);
        byKey.clear();
        for (AppEntry e : allApps) {
            byKey.put(e.key, e);
        }
    }

    /** 扫描可启动应用（纯计算，可在后台线程调用） */
    private List<AppEntry> scanApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();

        Intent leanback = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            byPackage.put(ri.activityInfo.packageName, ri);
        }
        Intent normal = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(normal, 0)) {
            if (!byPackage.containsKey(ri.activityInfo.packageName)) {
                byPackage.put(ri.activityInfo.packageName, ri);
            }
        }

        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : byPackage.values()) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            CharSequence label = ri.loadLabel(pm);
            String name = TextUtils.isEmpty(label) ? ri.activityInfo.packageName : label.toString();
            long installTime = 0L;
            try {
                installTime = pm.getPackageInfo(ri.activityInfo.packageName, 0).firstInstallTime;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 取不到就当作最旧，不影响其它应用排序
            }
            AppEntry e = new AppEntry(name,
                    new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadIcon(pm), installTime);
            out.add(e);
        }

        final java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return out;
    }

    // ---------- 抽屉网格适配器 ----------

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return allApps.size();
        }

        @Override
        public Object getItem(int position) {
            return allApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell;
            LinearLayout tile;
            FrameLayout mask;
            if (convertView instanceof FrameLayout
                    && ((FrameLayout) convertView).getChildCount() > 0) {
                cell = (FrameLayout) convertView;
                tile = (LinearLayout) cell.getChildAt(0);
                mask = (FrameLayout) tile.getChildAt(0);
            } else {
                // 外层 cell 负责占满整行格子，内层 tile 固定宽度并居中
                cell = new FrameLayout(LauncherActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                tile = new LinearLayout(LauncherActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);

                // 正方形遮罩层：只包住图标，名称放在遮罩下方
                mask = new FrameLayout(LauncherActivity.this);
                mask.setId(0x2003);
                int maskSize = dp(iconMaskDp());
                tile.addView(mask, new LinearLayout.LayoutParams(maskSize, maskSize));

                ImageView icon = new ImageView(LauncherActivity.this);
                icon.setId(0x2001);
                int size = dp(iconSizeDp());
                mask.addView(icon, new FrameLayout.LayoutParams(
                        size, size, Gravity.CENTER));

                TextView label = new TextView(LauncherActivity.this);
                label.setId(0x2002);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconLabelSp());
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                tile.addView(label, lp);

                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        dp(iconMaskDp() + 16), ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.gravity = Gravity.CENTER_HORIZONTAL;
                cell.addView(tile, tlp);
            }

            mask.setBackgroundResource(position == drawerSelectedPosition
                    ? R.drawable.dock_focus : R.drawable.tile_bg);

            AppEntry entry = allApps.get(position);
            ((ImageView) tile.findViewById(0x2001)).setImageBitmap(gridIcon(entry));
            ((TextView) tile.findViewById(0x2002)).setText(entry.label);
            return cell;
        }
    }
}
