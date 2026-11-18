package tv.dsh.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import android.os.SystemClock;
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
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;import java.util.Collections;
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

import org.json.JSONObject;

/**
 * 极简 TV 桌面
 * - 左上角时钟
 * - 底部一排"最近使用"
 * - 按 ↓ 滑出应用抽屉（7 列网格），返回键或第一行再按 ↑ 收起
 */
public class LauncherActivity extends Activity {

    private static final String PREFS = "launcher_prefs";
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
    private static final int COLUMNS = 7;
    private static final int DOCK_SHOW = 7;
    /** 4K 壁纸过渡演示：两张素材每 10 秒切一次，切换瞬间用 1080p 快照交叉淡化 */
    /** UI 壁纸显示/隐藏的快速淡化时长 */
    private static final long REVEAL_FADE_MS = 250L;
    /** 转场结束、首帧上屏后，1080p UI 层交接给 4K 视频的渐隐时长 */
    private static final long TRANSITION_REVEAL_MS = 500L;
    /** 新视频启动后给 4K 视频 plane 的稳定时间，再开始 UI 层渐隐 */
    private static final long FRAME_SETTLE_MS = 1000L;

    private static final String TAG = "DSH_LAUNCHER";
    /** 厂商天气 App 导出的实时天气（只读，不需要网络权限） */
    private static final Uri WEATHER_URI =
            Uri.parse("content://com.hisense.tv.weather/real_time_info");
    private static final long WEATHER_REFRESH_MS = 30 * 60 * 1000L;

    /** 背景轮播：构建时把 背景图/ 转成 assets/backgrounds；运行时可同步到外部目录 */
    private static final String BG_DIR = "backgrounds";
    private static final long BACKGROUND_INTERVAL_MS = 30 * 1000L;
    private static final long BG_FADE_MS = 1200L;
    private static final String KEY_SLIDESHOW = "slideshow_enabled";
    private static final String KEY_SLIDESHOW_SEC = "slideshow_interval_sec";
    private static final String KEY_SLIDESHOW_FADE = "slideshow_fade";
    private static final String KEY_VIDEO_MUTED = "video_muted";
    private static final int[] SLIDESHOW_STEPS = {10, 30, 60, 300, 600, 1800};
    /** UI 壁纸相对视频 plane 的 1px 对齐微调 */
    private static final float WALLPAPER_NUDGE_X = 1f;
    private static final float WALLPAPER_NUDGE_Y = 1f;

    /** 动态壁纸：外部视频目录 + 当前类型记忆 */
    private static final String VIDEO_BG_DIR = "backgrounds_video";
    private static final String KEY_VIDEO_BACKGROUND = "current_video_background";
    private static final String KEY_WALLPAPER_TYPE = "wallpaper_type";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";
    private static final int MANAGER_IMAGES = 0;
    private static final int MANAGER_VIDEOS = 1;
    /** 单个视频壁纸允许的最大体积 */
    private static final long VIDEO_MAX_BYTES = 600L * 1024L * 1024L;

    /** 4K 静态壁纸：外部图片目录 + 设备端编码缓存目录 */
    private static final String FOUR_K_BG_DIR = "backgrounds_4k";
    private static final String FOUR_K_CACHE_DIR = "4k_cache";
    private static final int FOUR_K_W = 3840;
    private static final int FOUR_K_H = 2160;
    private static final int FOUR_K_ENCODE_FRAMES = 2;
    /** 编码参数版本；改颜色/编码设置时递增，旧缓存下次启动自动清掉 */
    private static final String FOUR_K_CACHE_VERSION = "v4";
    /** 先切 UI 层壁纸，4K 视频就绪后再等 1 秒，无动画隐藏 UI 层 */
    /** 4K 模式下 UI 预览层的交叉淡化时长 */
    private static final long FOUR_K_UI_CROSSFADE_MS = 1000L;
    /** 首帧渲染回调后给合成器一点时间，避免揭盖瞬间黑一帧 */
    private static final long FOUR_K_FRAME_SETTLE_MS = 150L;
    /** 静态壁纸首帧上屏后，再过这么久主动暂停，把这一帧留在 surface 上 */
    private static final long FOUR_K_PAUSE_AFTER_MS = 600L;
    /** 硬切模式：UI 预览画出后 100ms 再切视频，避免点击瞬间黑 */
    private static final long FOUR_K_VIDEO_SWITCH_MS = 100L;
    /** UI 和 4K 视频使用同一个 overscan，避免一层放大一层不放大 */
    private static final float WALLPAPER_OVERSCAN = 1.002f;

    /** 打开/关闭动画时长（抽屉与桌面组件保持一致） */
    private static final long OPEN_MS = 400;
    private static final long CLOSE_MS = 320;
    /** 菜单按钮 60×60dp；横条高度 = 按钮高度 + 上下内边距 */
    private static final int MENU_ITEM_WIDTH_DP = 60;
    private static final int MENU_ITEM_HEIGHT_DP = 60;
    private static final int MENU_PADDING_V_DP = 6;
    /** 横条本体高度（按钮 + 上下等距留白） */
    private static final int MENU_BAR_HEIGHT_DP = MENU_ITEM_HEIGHT_DP + MENU_PADDING_V_DP * 2;
    /** 底边浅色描边高度，画在横条下方，不占按钮下边距 */
    private static final int MENU_BORDER_DP = 2;
    /** 菜单模块总高度 = 横条 + 底部描边 */
    private static final int MENU_HEIGHT_DP = MENU_BAR_HEIGHT_DP + MENU_BORDER_DP;
    /** 菜单左侧留白：上下边距的 2 倍（6dp → 12dp） */
    private static final int MENU_PADDING_H_DP = MENU_PADDING_V_DP * 2;
    private static final long MENU_ANIM_MS = 300;
    /** Material 标准缓动：进入减速、退出加速 */
    private static final PathInterpolator OPEN_EASE =
            new PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f);
    private static final PathInterpolator CLOSE_EASE =
            new PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f);
    /** 背景切换的淡入淡出缓动 */
    private static final PathInterpolator BG_EASE =
            new PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f);

    private static boolean x264Available;
    static {
        try {
            System.loadLibrary("x264jni");
            x264Available = true;
        } catch (Throwable t) {
            Log.w("DSH_LAUNCHER", "x264jni unavailable", t);
        }
    }

    private static native byte[] encodeI4204K(byte[] yuv, int width, int height, int frames);

    private static final class AppEntry {
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

    /** 一张背景图：要么来自 APK assets，要么来自设备外部目录 */
    private static final class BackgroundItem {
        final String name;
        final String assetName;
        final String filePath;
        /** 外部文件的 lastModified ^ length，用来发现同步后的内容变化 */
        final long stamp;

        BackgroundItem(String name, String assetName, String filePath, long stamp) {
            this.name = name;
            this.assetName = assetName;
            this.filePath = filePath;
            this.stamp = stamp;
        }
    }

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> byKey = new HashMap<>();
    /** 组件 → 最后一次打开时间；和新安装时间合并后决定底部栏顺序 */
    private final Map<String, Long> openTimes = new HashMap<>();

    private FrameLayout desktopModule;
    private FrameLayout homeLayer;
    private FrameLayout drawer;
    private GridView grid;
    private ImageView drawerFrostView;
    private Bitmap wallpaperBlurBitmap;
    private String wallpaperBlurKey;
    private LinearLayout dock;
    private LinearLayout wallpaperControls;
    private TextView wallpaperControlPrev;
    private TextView wallpaperControlNext;
    private TextView clockTime;
    private TextView clockDate;
    private TextView hintView;
    private TextView weatherIcon;
    private TextView weatherTemp;
    private TextView weatherDesc;
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
    private boolean fourKStaticMode;
    private boolean fourKEncoding;
    private int fourKEncodeToken;
    private int fourKPlayToken;
    private String current4KName;
    private final List<BackgroundItem> videoItems = new ArrayList<>();
    private int videoIndex = -1;
    private String currentVideoName;
    private boolean videoWallpaperMode;
    private boolean videoWallpaperReady;
    /** surface 还没建好时先把要播的文件记下来，surfaceCreated 之后再 setVideoPath。 */
    private File pendingVideoFile;
    private boolean videoSurfaceLost;
    private int videoRetryCount;
    /** 最近一次收到首帧渲染回调的时间，自检日志用 */
    private long lastVideoFrameAt;
    /** 视频模式下的周期自检，排查黑屏用 */
    private final Runnable videoHealthTick = new Runnable() {
        @Override
        public void run() {
            if (!videoWallpaperMode || wallpaperVideo == null) {
                return;
            }
            SurfaceHolder holder = wallpaperVideo.getHolder();
            String surface = holder.getSurface() == null
                    ? "null"
                    : (holder.getSurface().isValid() ? "valid" : "invalid");
            long sinceFrame = lastVideoFrameAt == 0L ? -1L
                    : (System.currentTimeMillis() - lastVideoFrameAt) / 1000L;
            Log.i(TAG, "video state: surface=" + surface
                    + " visible=" + (wallpaperVideo.getVisibility() == View.VISIBLE)
                    + " playing=" + wallpaperVideo.isPlaying()
                    + " pos=" + wallpaperVideo.getCurrentPosition()
                    + " dur=" + wallpaperVideo.getDuration()
                    + " ready=" + videoWallpaperReady
                    + " retries=" + videoRetryCount
                    + " sinceFirstFrame=" + sinceFrame + "s");
            handler.postDelayed(this, 5000);
        }
    };
    private String playingVideoPath;
    /** 播放器里已经 prepare 好的视频路径，用于从后台回来时原地续播 */
    private boolean slideshowEnabled = true;
    private int slideshowIntervalSec = 30;
    private boolean slideshowFade = true;
    private boolean videoMuted = true;
    private MediaPlayer videoPlayerRef;
    private int videoPlayToken;
    private int managerType = MANAGER_IMAGES;
    private boolean cacheCleanupStarted;
    private boolean fourKUiAnimationDone;
    private boolean fourKVideoReady;
    /** 4K 视频正在 prepare / 等首帧：避免 onResume 和 onNewIntent 各重播一次 */
    private boolean fourKPreparing;
    /** 当前 4K 壁纸的预览位图缓存：返回桌面时直接复用，不再重新解码大图 */
    private Bitmap fourKPreviewCache;
    private String fourKPreviewCacheKey;
    private View startupBlackout;
    private boolean startupRevealed;
    private boolean startupAppScanDone;
    /** 后台重扫应用列表是否进行中（避免重复起线程） */
    private boolean appScanRunning;
    private int drawerSelectedPosition = 0;
    private int backgroundIndex = -1;
    private String currentBackgroundName;
    private boolean bgSwitching;
    private LinearLayout menuModule;
    private LinearLayout menuBar;
    private boolean menuOpen;
    /** 从左右键打开菜单/抽屉时，关闭后把焦点还回去 */
    private boolean returnFocusToArrows;

    /** 壁纸管理页（全屏浮层） */
    private FrameLayout managerModule;
    private GridView managerGrid;
    private TextView managerStats;
    private TextView managerEmpty;
    private TextView managerImportButton;
    private TextView managerCacheButton;
    private TextView managerCloseButton;
    private LinearLayout managerTabRow;
    private LinearLayout managerTabGroup;
    private TextView managerTabImage;
    private TextView managerTabVideo;
    private WallpaperAdapter managerAdapter;
    private boolean managerOpen;
    /** 缩略图缓存：key = 文件名|stamp */
    private final Map<String, Bitmap> thumbnailCache = new HashMap<>();

    /** 远程导入页（局域网 HTTP 上传，全屏浮层，盖在管理页上面） */
    private FrameLayout remoteModule;
    private TextView remoteUrlView;
    private ImageView remoteQrView;
    private TextView remoteStatusView;
    private TextView remoteBackButton;
    private RemoteImportServer remoteServer;
    private boolean remoteOpen;

    private final Handler handler = new Handler();
    private boolean drawerOpen;
    private boolean firstResume = true;
    private int screenHeight;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        reveal4KVideo = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_REVEAL_4K, true);

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
                if (pendingVideoFile != null && !videoWallpaperMode) {
                    // 已经切回静态壁纸了，别再补播这个待播视频
                    Log.i(TAG, "video surface ready, drop pending " + pendingVideoFile.getName());
                    pendingVideoFile = null;
                }
                if (pendingVideoFile != null) {
                    File pending = pendingVideoFile;
                    pendingVideoFile = null;
                    Log.i(TAG, "video surface ready, prepare " + pending.getName());
                    wallpaperVideo.setVideoPath(pending.getAbsolutePath());
                } else if (videoSurfaceLost && videoWallpaperMode) {
                    // Surface 被销毁过（打开管理页、切走再回来等），旧 MediaPlayer 已经没有
                    // 输出目标了，直接重新 prepare 一次，否则画面会一直黑着。
                    videoSurfaceLost = false;
                    File file = currentVideoFile();
                    if (file != null) {
                        Log.i(TAG, "video surface recreated, re-prepare " + file.getName());
                        videoWallpaperReady = false;
                        wallpaperVideo.setVideoPath(file.getAbsolutePath());
                    }
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
                fourKVideoReady = false;
                fourKPreparing = false;
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

        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        cleanup4KCacheAsync();

        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        desktopModule.addView(scrim, matchParent());

        // ---- 桌面层：时钟 + 底部最近使用 ----
        homeLayer = new FrameLayout(this);
        desktopModule.addView(homeLayer, matchParent());

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        // 时钟不铺半透明底板，直接压在壁纸上；保留内边距以维持原来的文字位置
        clockBox.setPadding(dp(26), dp(16), dp(26), dp(18));

        clockTime = new TextView(this);
        clockTime.setTextColor(Color.WHITE);
        clockTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        clockTime.setShadowLayer(9f, 0f, 3f, 0xCC000000);
        clockTime.setSingleLine(true);
        clockBox.addView(clockTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clockDate = new TextView(this);
        clockDate.setTextColor(0xFFE8EEF6);
        clockDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        clockDate.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(2);
        clockBox.addView(clockDate, dlp);

        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clockLp.leftMargin = dp(56);
        clockLp.topMargin = dp(44);
        homeLayer.addView(clockBox, clockLp);

        // ---- 右上天气：与时钟同一行，左右两端对齐 ----
        LinearLayout weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.END);

        LinearLayout weatherRow = new LinearLayout(this);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.END | Gravity.BOTTOM);

        weatherIcon = new TextView(this);
        weatherIcon.setText("☁");
        weatherIcon.setTextColor(0xFFE8EEF6);
        weatherIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        weatherIcon.setShadowLayer(7f, 0f, 2f, 0xCC000000);
        LinearLayout.LayoutParams weatherIconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherIconLp.rightMargin = dp(8);
        weatherRow.addView(weatherIcon, weatherIconLp);

        weatherTemp = new TextView(this);
        weatherTemp.setText("--°");
        weatherTemp.setTextColor(Color.WHITE);
        weatherTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        weatherTemp.setShadowLayer(9f, 0f, 3f, 0xCC000000);
        weatherTemp.setSingleLine(true);
        weatherRow.addView(weatherTemp);

        LinearLayout.LayoutParams weatherRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherBox.addView(weatherRow, weatherRowLp);

        weatherDesc = new TextView(this);
        weatherDesc.setText("天气加载中…");
        weatherDesc.setTextColor(0xFFE8EEF6);
        weatherDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        weatherDesc.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        weatherDesc.setGravity(Gravity.END);
        weatherDesc.setSingleLine(true);
        LinearLayout.LayoutParams weatherDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weatherDescLp.topMargin = dp(2);
        weatherBox.addView(weatherDesc, weatherDescLp);

        FrameLayout.LayoutParams weatherLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        weatherLp.rightMargin = dp(56);
        weatherLp.topMargin = dp(60); // 与时钟时间文字顶部对齐
        homeLayer.addView(weatherBox, weatherLp);

        // 底栏上方：壁纸左右切换控制行
        wallpaperControls = new LinearLayout(this);
        wallpaperControls.setOrientation(LinearLayout.HORIZONTAL);
        wallpaperControls.setGravity(Gravity.START);
        wallpaperControlPrev = makeWallpaperControl("◀", -1);
        wallpaperControlNext = makeWallpaperControl("▶", 1);
        wallpaperControls.addView(wallpaperControlPrev);
        wallpaperControls.addView(wallpaperControlNext);

        // 底部栏：左侧"最近使用"占满剩余宽度，右侧提示按文字自适应；
        // 提示不能再按权重抢宽度，否则第 7 个图标会被挤窄。
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.BOTTOM);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.START | Gravity.BOTTOM);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(dock, dockLp);

        hintView = new TextView(this);
        hintView.setText("↓ 打开应用");
        hintView.setTextColor(0xCCFFFFFF);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hintView.setShadowLayer(6f, 0f, 2f, 0xCC000000);
        hintView.setGravity(Gravity.END | Gravity.BOTTOM);
        hintView.setPadding(0, 0, 0, dp(2));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // 壁纸左右键挪到「打开应用」上方、与图标行垂直居中
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.leftMargin = dp(16);
        controlsLp.gravity = Gravity.CENTER_VERTICAL;
        bottomBar.addView(wallpaperControls, controlsLp);

        LinearLayout bottomColumn = new LinearLayout(this);
        bottomColumn.setOrientation(LinearLayout.VERTICAL);
        bottomColumn.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottomColumn.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        columnLp.leftMargin = dp(56);
        columnLp.rightMargin = dp(30);
        columnLp.bottomMargin = dp(20);
        homeLayer.addView(bottomColumn, columnLp);

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
        grid.setNumColumns(COLUMNS);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
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
                    if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                        View tile = ((ViewGroup) child).getChildAt(0);
                        tile.setBackgroundResource(child == view
                                ? R.drawable.item_focus : R.drawable.tile_bg);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                        ((ViewGroup) child).getChildAt(0)
                                .setBackgroundResource(R.drawable.tile_bg);
                    }
                }
            }
        });
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && grid.getSelectedItemPosition() < COLUMNS) {
                    closeDrawer();
                    return true;
                }
                return false;
            }
        });
        drawer.addView(grid, matchParent());
        desktopModule.addView(drawer, matchParent());

        // ---- 顶部菜单模块：横条 + 底部浅色描边，整体从屏幕外挤入 ----
        menuModule = new LinearLayout(this);
        menuModule.setOrientation(LinearLayout.VERTICAL);
        menuModule.setVisibility(View.GONE);
        menuModule.setElevation(dp(8));

        menuBar = new LinearLayout(this);
        menuBar.setOrientation(LinearLayout.HORIZONTAL);
        menuBar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        menuBar.setBackgroundResource(R.drawable.menu_strip_bg);
        menuBar.setPadding(dp(MENU_PADDING_H_DP), dp(MENU_PADDING_V_DP), dp(MENU_PADDING_H_DP), dp(MENU_PADDING_V_DP));

        menuBar.addView(makeMenuItem(android.R.drawable.ic_menu_gallery, "壁纸管理"));

        menuModule.addView(menuBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(MENU_BAR_HEIGHT_DP)));

        View menuLine = new View(this);
        menuLine.setBackgroundColor(0xFFBDBDBD);
        menuModule.addView(menuLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(MENU_BORDER_DP)));

        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(MENU_HEIGHT_DP), Gravity.TOP);
        root.addView(menuModule, menuLp);
        menuModule.setTranslationY(-dp(MENU_HEIGHT_DP));

        // 壁纸管理页：全屏浮层，盖住菜单和桌面
        buildWallpaperManager();
        root.addView(managerModule, matchParent());

        // 远程导入页：局域网 HTTP 上传，盖在管理页上面
        buildRemoteImportPage();
        root.addView(remoteModule, matchParent());

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
        focusFirstDockItem();
        loadPlaybackSettings();
        restoreWallpaperMode();
        handleDebugWallpaperIntent(getIntent());
        if (getIntent() != null && getIntent().getBooleanExtra("wallpaper_manager", false)) {
            if ("video".equals(getIntent().getStringExtra("manager_tab"))) {
                setManagerType(MANAGER_VIDEOS);
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

    private File videoBgDir() {
        File base = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null) : getFilesDir();
        return new File(base, VIDEO_BG_DIR);
    }

    private static boolean isVideoName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".ts");
    }

    private List<BackgroundItem> scanVideoBackgrounds() {
        List<BackgroundItem> items = new ArrayList<>();
        File dir = videoBgDir();
        if (!dir.exists()) {
            dir.mkdirs();
            return items;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return items;
        }
        for (File file : files) {
            if (!file.isFile() || !isVideoName(file.getName())) {
                continue;
            }
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    file.lastModified() ^ file.length()));
        }
        Collections.sort(items, new Comparator<BackgroundItem>() {
            @Override
            public int compare(BackgroundItem a, BackgroundItem b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
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
        Log.i(TAG, "video background items: " + videoItems.size());
    }

    /** 启动时按上次记住的类型恢复壁纸：视频优先，否则走 4K 静态 / 1080p */
    private void restoreWallpaperMode() {
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

    private void refreshVideoWallpaperMode() {
        if (wallpaperVideo == null) {
            return;
        }
        if (!videoWallpaperMode || videoItems.isEmpty()) {
            stopVideoWallpaper();
            return;
        }
        if (videoIndex < 0 || videoIndex >= videoItems.size()) {
            videoIndex = 0;
        }
        BackgroundItem item = videoItems.get(videoIndex);
        currentVideoName = item.name;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_VIDEO_BACKGROUND, item.name)
                .putString(KEY_WALLPAPER_TYPE, TYPE_VIDEO).apply();
        playVideoWallpaper(new File(item.filePath));
    }

    private void playVideoWallpaper(final File file) {
        if (wallpaperVideo == null || file == null || !file.isFile()) {
            return;
        }
        final int token = ++videoPlayToken;
        videoWallpaperReady = false;
        handler.removeCallbacks(bgTick);
        final String prevPath = playingVideoPath;
        final boolean switchingVideo = videoPlayerRef != null && prevPath != null
                && !prevPath.equals(file.getAbsolutePath());
        if (switchingVideo) {
            // 动态壁纸之间切换：先用当前视频的一帧铺封面，不能铺静态壁纸（会闪一下静态图）
            final File prevFile = new File(prevPath);
            int position = 0;
            try {
                position = Math.max(0, wallpaperVideo.getCurrentPosition());
            } catch (Throwable ignored) {
            }
            final int captureMs = position;
            Log.i(TAG, "video switch, capture cover @" + captureMs + "ms " + prevFile.getName());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Bitmap frame = grabVideoFrame(prevFile, captureMs);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != videoPlayToken) {
                                return;
                            }
                            if (frame != null) {
                                showVideoFrameCover(frame);
                            }
                            startVideoWallpaper(file, token, true);
                        }
                    });
                }
            }, "video-switch-cover").start();
            return;
        }
        // 已经在动态壁纸模式里重播（例如从其他应用返回、surface 重建）：
        // 不铺静态壁纸封面，直接等视频首帧，中间黑就黑。
        final boolean rePrepare = videoWallpaperMode && playingVideoPath != null;
        startVideoWallpaper(file, token, rePrepare);
    }

    /** 真正开始播放下一个视频；videoFrameCover=true 表示封面已经用视频帧铺好，不再铺静态壁纸 */
    private void startVideoWallpaper(final File file, final int token,
                                     final boolean videoFrameCover) {
        fourKStaticMode = false;
        fourKEncoding = false;
        fourKEncodeToken++;
        stop4KStatic();
        wallpaperVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (token != videoPlayToken || !videoWallpaperMode) {
                    return;
                }
                mp.setLooping(true);
                videoPlayerRef = mp;
                float volume = videoMuted ? 0f : 1f;
                mp.setVolume(volume, volume);
                // 先保留 bgFront/bgBack（显示上一张壁纸），等首帧真正渲染出来再隐藏，
                // 避免 prepare 期间整屏黑掉。视频→视频切换时 bgFront 里是旧静态图，不能再露出来。
                if (!videoFrameCover && bgFront != null && bgFront.getDrawable() != null) {
                    bgFront.setVisibility(View.VISIBLE);
                }
                if (wallpaperTransitionLayer != null && !videoFrameCover) {
                    wallpaperTransitionLayer.setVisibility(View.GONE);
                }
                wallpaperVideo.setVisibility(View.VISIBLE);
                wallpaperVideo.start();
                // 看门狗：3 秒内没有收到首帧就重建一次，避免停在黑屏不再恢复
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (token != videoPlayToken || !videoWallpaperMode
                                || videoWallpaperReady) {
                            return;
                        }
                        if (videoRetryCount < 1) {
                            videoRetryCount++;
                            Log.w(TAG, "video wallpaper no frame in 3s, retry prepare #"
                                    + videoRetryCount);
                            refreshVideoWallpaperMode();
                        } else {
                            Log.w(TAG, "video wallpaper still black, fall back to image mode");
                            videoRetryCount = 0;
                            leaveVideoWallpaperMode();
                            refresh4KStaticMode();
                        }
                    }
                }, 3000);
        
                Log.i(TAG, "video wallpaper prepared " + mp.getVideoWidth()
                        + "x" + mp.getVideoHeight() + " " + file.getName());
            }
        });
        wallpaperVideo.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "video wallpaper rendering start");
                    lastVideoFrameAt = System.currentTimeMillis();
                    videoWallpaperReady = true;
                    videoRetryCount = 0;
                    videoSurfaceLost = false;
                    wallpaperVideo.setVisibility(View.VISIBLE);
                    hideVideoCover();
                    bgFront.setVisibility(View.GONE);
                    bgBack.setVisibility(View.GONE);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token == videoPlayToken && videoWallpaperMode) {
                                fourKVideoReady = true;
                                maybeHide4KUiCover();
                            }
                        }
                    }, FOUR_K_FRAME_SETTLE_MS);
                }
                return false;
            }
        });
        wallpaperVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (token != videoPlayToken) {
                    return true;
                }
                Log.w(TAG, "video wallpaper failed: " + what + "/" + extra);
                videoWallpaperMode = false;
                stopVideoWallpaper();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
                refresh4KStaticMode();
                return true;
            }
        });
        // 视频层必须可见，否则 surface 建不起来、prepare 完也拿不到帧；
        // 空 surface 是黑的，所以在它上面先盖一层当前壁纸，等首帧渲染再撤。
        // 视频→视频切换时封面已经用上一段视频的帧铺好，不能换成静态壁纸。
        if (!videoFrameCover) {
            showVideoCover();
        }
        wallpaperVideo.setVisibility(View.VISIBLE);
        playingVideoPath = file.getAbsolutePath();
        handler.removeCallbacks(videoHealthTick);
        handler.postDelayed(videoHealthTick, 5000);
        SurfaceHolder holder = wallpaperVideo.getHolder();
        if (holder.getSurface() == null || !holder.getSurface().isValid()) {
            // 没有输出目标时 setVideoPath 会 prepare 成功但永远不出帧（黑屏），
            // 记录待播文件，等 surfaceCreated 回调里再 prepare。
            pendingVideoFile = file;
            Log.i(TAG, "video surface not ready, defer prepare " + file.getName());
        } else {
            wallpaperVideo.setVideoPath(playingVideoPath);
        }
        Log.i(TAG, "video wallpaper play " + file.getName());
    }

    private void stopVideoWallpaper() {
        videoWallpaperReady = false;
        playingVideoPath = null;
        pendingVideoFile = null;
        videoPlayToken++;
        videoPlayerRef = null;
        handler.removeCallbacks(videoHealthTick);
        if (wallpaperVideo != null) {
            wallpaperVideo.setOnPreparedListener(null);
            wallpaperVideo.setOnInfoListener(null);
            wallpaperVideo.setOnErrorListener(null);
            if (wallpaperVideo.isPlaying()) {
                wallpaperVideo.stopPlayback();
            } else {
                wallpaperVideo.stopPlayback();
            }
            wallpaperVideo.setVisibility(View.GONE);
        }
    }

    /** 关闭循环时，播到结尾就停在最后一帧 */

    /** 抓视频某一时刻的帧（缩到 1280x720）当切换封面，解不出来返回 null */
    private Bitmap grabVideoFrame(File file, int positionMs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getScaledFrameAtTime(positionMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 720);
            if (frame == null) {
                frame = retriever.getFrameAtTime(positionMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame != null) {
                Log.i(TAG, "video cover frame " + frame.getWidth() + "x" + frame.getHeight()
                        + " @" + positionMs + "ms");
            }
            return frame;
        } catch (Throwable t) {
            Log.w(TAG, "video cover frame failed: " + t);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用正在播放的视频帧当过渡封面（视频→视频切换时用，避免闪静态壁纸） */
    private void showVideoFrameCover(Bitmap frame) {
        if (wallpaperTransitionLayer == null || transitionNew == null || frame == null) {
            return;
        }
        if (transitionOld != null) {
            transitionOld.setImageDrawable(null);
        }
        transitionNew.setImageBitmap(frame);
        transitionNew.setAlpha(1f);
        wallpaperTransitionLayer.setAlpha(1f);
        wallpaperTransitionLayer.setVisibility(View.VISIBLE);
        Log.i(TAG, "video frame cover shown");
    }

    /** 当前视频壁纸的真实文件（找不到返回 null） */
    private File currentVideoFile() {
        if (videoItems.isEmpty()) {
            return null;
        }
        if (videoIndex < 0 || videoIndex >= videoItems.size()) {
            videoIndex = 0;
        }
        return new File(videoItems.get(videoIndex).filePath);
    }

    /**
     * 回到前台：
     * 1) 播放器还停在同一个文件上、只是被 pause 了 → 直接 start() 续播，不出黑帧；
     * 2) 播放器已经不在了（进程内重建/曾被 stop）→ 重新 prepare，bgFront 垫着上一张壁纸直到首帧。
     * playVideoWallpaper 里还有 3 秒看门狗兜底，真卡黑屏会重试一次再退回图片模式。
     */
    private void restoreVideoWallpaperOnForeground() {
        File file = currentVideoFile();
        if (videoSurfaceLost) {
            // surface 被销毁过（切走应用时必然发生），旧播放器已经没有输出目标：
            // 直接重新 prepare，别等 3 秒看门狗才恢复画面。
            videoSurfaceLost = false;
            Log.i(TAG, "video surface lost, re-prepare on foreground");
            refreshVideoWallpaperMode();
            return;
        }
        if (videoPlayerRef != null && wallpaperVideo != null && file != null
                && playingVideoPath != null
                && playingVideoPath.equals(file.getAbsolutePath())) {
            try {
                if (!wallpaperVideo.isPlaying()) {
                    wallpaperVideo.start();
                }
                videoWallpaperReady = true;
                Log.i(TAG, "video wallpaper resumed " + file.getName());
                return;
            } catch (Throwable t) {
                Log.w(TAG, "video resume failed: " + t);
            }
        }
        refreshVideoWallpaperMode();
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
        if (video != null && isVideoName(video)) {
            File file = new File(videoBgDir(), video);
            if (file.exists()) {
                Log.i(TAG, "debug: apply video " + video);
                reloadVideoBackgrounds();
                applyVideoByName(video);
            } else {
                Log.w(TAG, "debug: video not found " + video);
            }
        }
        String image = intent.getStringExtra("apply_image");
        if (image != null && isImageName(image)) {
            Log.i(TAG, "debug: apply image " + image);
            reloadBackgrounds();
            reload4KBackgrounds();
            applyWallpaperByName(image);
        }
        if (intent.getBooleanExtra("video_state", false)) {
            handler.postDelayed(videoHealthTick, 500);
        }
    }

    private void applyVideoByName(String name) {
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
        playVideoWallpaper(new File(videoItems.get(next).filePath));
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
        // playVideoWallpaper 把 fourKStaticMode 关了；这里必须重新进入图片模式，
        // 否则 applyWallpaperByName/switch4KStatic 都会因为 fourKStaticMode=false 直接 return，
        // 表现为「换了壁纸但画面不变」。
        refresh4KStaticMode();
    }

    // ---------- 4K 静态壁纸（设备端 x264 单帧编码） ----------

    private boolean fourKStaticEnabled() {
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
        File dir = new File(getExternalFilesDir(null), FOUR_K_BG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            return items;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return items;
        }
        for (File file : files) {
            if (!file.isFile() || !isImageName(file.getName())) {
                continue;
            }
            items.add(new BackgroundItem(file.getName(), null, file.getAbsolutePath(),
                    file.lastModified() ^ file.length()));
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

    private void refresh4KStaticMode() {
        if (videoWallpaperMode) {
            return;
        }
        if (wallpaperVideo == null) {
            return;
        }
        if (!fourKStaticEnabled()) {
            fourKStaticMode = false;
            fourKEncoding = false;
            fourKEncodeToken++;
            stop4KStatic();
            if (bgFront != null) {
                bgFront.setVisibility(View.VISIBLE);
                bgBack.setVisibility(View.VISIBLE);
            }
            resetBgTimer();
            revealStartupFromBlack();
            return;
        }
        fourKStaticMode = true;
        handler.removeCallbacks(bgTick);
        show4KCurrent();
    }

    private void show4KCurrent() {
        if (!fourKStaticMode || fourKItems.isEmpty()) {
            return;
        }
        if (fourKIndex < 0 || fourKIndex >= fourKItems.size()) {
            fourKIndex = 0;
        }
        BackgroundItem item = fourKItems.get(fourKIndex);
        // 同一张图已经在播（或正在准备）就不重来：从其他应用返回时 onResume 和
        // onNewIntent 会各调一次，重复重播会让视频层多黑 1 秒多。
        if (item.name.equals(current4KName) && (fourKVideoReady || fourKPreparing)) {
            Log.i(TAG, "4K static already shown, skip replay");
            return;
        }
        Log.i(TAG, "show4KCurrent " + item.name + " ready=" + fourKVideoReady
                + " preparing=" + fourKPreparing + " surfaceLost=" + videoSurfaceLost);
        current4KName = item.name;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_4K_BACKGROUND, item.name).apply();
        // 标记"准备中"必须同步落下：真正 play 在 100ms 后才执行，
        // 这期间 onResume/onNewIntent 的第二次调用不能又排一遍。
        fourKPreparing = true;
        show4KPreview(item);
        final int token = fourKEncodeToken;
        final BackgroundItem target = item;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!fourKStaticMode || token != fourKEncodeToken) {
                    return;
                }
                File cache = fourKCacheFile(target);
                if (cache.exists() && cache.length() > 0) {
                    play4KStatic(cache);
                } else {
                    encode4KStatic(target);
                }
            }
        }, 100);
    }

    private void show4KPreview(BackgroundItem item) {
        fourKUiAnimationDone = false;
        fourKVideoReady = false;
        // 预览位图按「名字|stamp」缓存：返回桌面时复用同一张，不再解码几十 MB 的原图
        Bitmap bitmap;
        String cacheKey = item.name + "|" + item.stamp;
        if (fourKPreviewCache != null && !fourKPreviewCache.isRecycled()
                && cacheKey.equals(fourKPreviewCacheKey)) {
            bitmap = fourKPreviewCache;
        } else {
            bitmap = loadFile(item.filePath);
            if (bitmap != null) {
                fourKPreviewCache = bitmap;
                fourKPreviewCacheKey = cacheKey;
            }
        }
        if (bitmap != null) {
            bgFront.setImageBitmap(bitmap);
            bgFront.setAlpha(1f);
            bgBack.setAlpha(0f);
            bgSwitching = false;
            backgroundIndex = fourKIndex;
            currentBackgroundName = item.name;
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
        fourKUiAnimationDone = true;
        maybeHide4KUiCover();
        // 预览图已经就绪，先淡出启动黑幕，不等 4K 首帧
        revealStartupFromBlack();
        bgSwitching = false;
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
        if (menuOpen && desktopModule != null) {
            desktopModule.animate().cancel();
            float target = shouldFloatMenu() ? 0f : dp(MENU_HEIGHT_DP);
            desktopModule.animate().translationY(target)
                    .setDuration(MENU_ANIM_MS)
                    .setInterpolator(target == 0f ? CLOSE_EASE : OPEN_EASE).start();
        }
        refresh4KStaticMode();
    }

    private void switch4KStatic(int direction) {
        if (!fourKStaticEnabled() || fourKEncoding || fourKItems.isEmpty()) {
            return;
        }
        int size = fourKItems.size();
        if (size < 2) {
            return;
        }
        int next = (fourKIndex + direction + size) % size;
        if (next == fourKIndex) {
            return;
        }
        fourKIndex = next;
        fourKEncodeToken++;
        fourKEncoding = false;
        invalidate4KPlayback();
        show4KCurrent();
    }

    /** 主动换图/重新应用时必须清掉"已在播"标记，否则会被 show4KCurrent 的去重逻辑跳过 */
    private void invalidate4KPlayback() {
        fourKVideoReady = false;
        fourKPreparing = false;
    }

    private void encode4KStatic(final BackgroundItem item) {
        if (fourKEncoding || !fourKStaticMode) {
            return;
        }
        final File cache = fourKCacheFile(item);
        if (cache.exists() && cache.length() > 0) {
            play4KStatic(cache);
            return;
        }
        if (!x264Available) {
            fourKPreparing = false;
            Toast.makeText(this, "x264 编码库不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        fourKEncoding = true;
        final int token = ++fourKEncodeToken;
        Log.i(TAG, "encode 4K wallpaper: " + item.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap source = null;
                try {
                    int[] target = targetSizeFor(item.filePath);
                    if (target == null) {
                        throw new IOException("bad image bounds");
                    }
                    source = decode4KBitmap(item.filePath, target[0], target[1]);
                    if (source == null) {
                        throw new IOException("decode failed");
                    }
                    Log.i(TAG, "4K encode target " + target[0] + "x" + target[1]
                            + " for " + item.name);
                    byte[] yuv = bitmapToI420(source, target[0], target[1]);
                    source.recycle();
                    source = null;
                    byte[] data = encodeI4204K(yuv, target[0], target[1], FOUR_K_ENCODE_FRAMES);
                    if (data == null) {
                        throw new IOException("x264 returned null");
                    }
                    write4KMp4(cache, data, target[0], target[1]);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (token != fourKEncodeToken) {
                                return;
                            }
                            fourKEncoding = false;
                            if (fourKStaticMode) {
                                play4KStatic(cache);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "encode 4K wallpaper failed", t);
                    if (source != null) {
                        source.recycle();
                    }
                    cache.delete();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (token != fourKEncodeToken) {
                                return;
                            }
                            fourKEncoding = false;
                            fourKPreparing = false;
                            revealStartupFromBlack();
                            Toast.makeText(LauncherActivity.this,
                                    "4K 壁纸生成失败", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "4k-wallpaper-encode").start();
    }

    private File fourKCacheFile(BackgroundItem item) {
        File dir = new File(getExternalFilesDir(null), FOUR_K_CACHE_DIR);
        dir.mkdirs();
        String safe = item.name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, safe + "_" + item.stamp + "_"
                + FOUR_K_CACHE_VERSION + ".mp4");
    }

    private void play4KStatic(final File file) {
        if (!fourKStaticMode || wallpaperVideo == null) {
            return;
        }
        final int token = ++fourKPlayToken;
        fourKPreparing = true;
        wallpaperVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (token != fourKPlayToken || !fourKStaticMode) {
                    return;
                }
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
                Log.i(TAG, "4K static prepared " + mp.getVideoWidth()
                        + "x" + mp.getVideoHeight());
                bgFront.setVisibility(View.GONE);
                bgBack.setVisibility(View.GONE);
                wallpaperVideo.setVisibility(View.VISIBLE);
                wallpaperVideo.start();
            }
        });
        wallpaperVideo.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    Log.i(TAG, "4K static rendering start");
                    fourKPreparing = false;
                    videoSurfaceLost = false;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token == fourKPlayToken && fourKStaticMode) {
                                fourKVideoReady = true;
                                maybeHide4KUiCover();
                            }
                        }
                    }, FOUR_K_FRAME_SETTLE_MS);
                    // 静态壁纸只有 2 个相同的帧：首帧上屏后主动暂停，把这一帧留在 surface 上，
                    // 省掉解码器每 2 秒重复解一帧 4K。setLooping 仍是 true，所以永远走不到 EOS，
                    // 不会出现播放完成后的黑屏。
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (token != fourKPlayToken || !fourKStaticMode) {
                                return;
                            }
                            if (wallpaperVideo != null && wallpaperVideo.isPlaying()) {
                                wallpaperVideo.pause();
                                Log.i(TAG, "4K static paused on frame");
                            }
                        }
                    }, FOUR_K_PAUSE_AFTER_MS);
                }
                return false;
            }
        });
        wallpaperVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.w(TAG, "4K static playback error " + what + "/" + extra);
                if (token == fourKPlayToken) {
                    fourKPreparing = false;
                    wallpaperVideo.setVisibility(View.GONE);
                    if (wallpaperTransitionLayer != null) {
                        wallpaperTransitionLayer.setVisibility(View.GONE);
                    }
                    bgFront.setVisibility(View.VISIBLE);
                    bgBack.setVisibility(View.VISIBLE);
                    revealStartupFromBlack();
                }
                return true;
            }
        });
        Log.i(TAG, "4K static play " + file.getName());
        wallpaperVideo.setVisibility(View.VISIBLE);
        wallpaperVideo.setVideoPath(file.getAbsolutePath());
    }

    /** 视频层在准备期间是空的黑 surface，用当前静态壁纸铺一层封面挡住 */
    private void showVideoCover() {
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

    private void hideVideoCover() {
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
                    Bitmap bitmap = item.filePath != null ? loadFile(item.filePath) : null;
                    if (bitmap != null) {
                        return bitmap;
                    }
                }
            }
            if (!backgroundItems.isEmpty()) {
                int idx = Math.max(backgroundIndex, 0) % backgroundItems.size();
                return loadBackground(backgroundItems.get(idx));
            }
        } catch (Throwable t) {
            Log.w(TAG, "cover bitmap failed", t);
        }
        return null;
    }

    private void maybeHide4KUiCover() {
        if (fourKStaticMode && fourKUiAnimationDone && fourKVideoReady) {
            hide4KUiCover();
        }
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
    }

    private void revealStartupFromBlack() {
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

    private void stop4KStatic() {
        fourKPlayToken++;
        fourKUiAnimationDone = false;
        fourKVideoReady = false;
        fourKPreparing = false;
        if (wallpaperVideo != null) {
            wallpaperVideo.stopPlayback();
            wallpaperVideo.setVisibility(View.GONE);
        }
        if (wallpaperTransitionLayer != null) {
            wallpaperTransitionLayer.setVisibility(View.GONE);
            transitionOld.setImageDrawable(null);
            transitionNew.setImageDrawable(null);
        }
    }

    /** 按中心裁切后的 16:9 源尺寸编码，最高 4K；低清源不放大 */
    private static int[] targetSizeFor(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sw = bounds.outWidth;
        int sh = bounds.outHeight;
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        int cropW = sw;
        int cropH = sh;
        if ((long) sw * 9 > (long) sh * 16) {
            cropW = (int) ((long) sh * 16 / 9);
        } else {
            cropH = (int) ((long) sw * 9 / 16);
        }
        int w = Math.min(cropW, FOUR_K_W);
        int h = Math.min(cropH, FOUR_K_H);
        w &= ~1;
        h &= ~1;
        if (w < 2) w = 2;
        if (h < 2) h = 2;
        return new int[]{w, h};
    }

    private Bitmap decode4KBitmap(String path, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetW
                && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = BitmapFactory.decodeFile(path, options);
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

    private static byte[] bitmapToI420(Bitmap bitmap, int width, int height) {
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

    private static void write4KMp4(File file, byte[] data, int width, int height) throws IOException {
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
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 1);
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
                info.set(0, annexB.length, i * 1_000_000L,
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
        slideshowEnabled = sp.getBoolean(KEY_SLIDESHOW, true);
        slideshowIntervalSec = sp.getInt(KEY_SLIDESHOW_SEC, 30);
        slideshowFade = sp.getBoolean(KEY_SLIDESHOW_FADE, true);
        videoMuted = sp.getBoolean(KEY_VIDEO_MUTED, true);
    }

    private void savePlaybackSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SLIDESHOW, slideshowEnabled)
                .putInt(KEY_SLIDESHOW_SEC, slideshowIntervalSec)
                .putBoolean(KEY_SLIDESHOW_FADE, slideshowFade)
                .putBoolean(KEY_VIDEO_MUTED, videoMuted)
                .apply();
    }

    private String intervalLabel(int seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分钟";
    }

    private Switch makeSettingsSwitch(String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        sw.setChecked(checked);
        sw.setPadding(0, dp(6), 0, dp(6));
        return sw;
    }

    private void showBackgroundSettings() {
        loadPlaybackSettings();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(30), dp(18), dp(30), dp(6));

        Switch sw4k = makeSettingsSwitch("4K 静态壁纸", reveal4KVideo);
        sw4k.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                set4KStaticEnabled(isChecked);
            }
        });
        box.addView(sw4k);

        Switch swSlide = makeSettingsSwitch("幻灯片播放", slideshowEnabled);
        swSlide.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                slideshowEnabled = isChecked;
                savePlaybackSettings();
                resetBgTimer();
                Log.i(TAG, "slideshow = " + isChecked);
            }
        });
        box.addView(swSlide);

        final TextView interval = new TextView(this);
        interval.setText("切换间隔：" + intervalLabel(slideshowIntervalSec) + "（左右键调整）");
        interval.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        interval.setTextColor(Color.WHITE);
        interval.setFocusable(true);
        interval.setFocusableInTouchMode(true);
        interval.setPadding(dp(14), dp(12), dp(14), dp(12));
        interval.setBackgroundResource(R.drawable.tile_bg);
        interval.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.item_focus : R.drawable.tile_bg);
            }
        });
        View.OnKeyListener stepKey = new View.OnKeyListener() {
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
                interval.setText("切换间隔：" + intervalLabel(slideshowIntervalSec) + "（左右键调整）");
                return true;
            }
        };
        interval.setOnKeyListener(stepKey);
        interval.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSlideshowInterval(1);
                interval.setText("切换间隔：" + intervalLabel(slideshowIntervalSec) + "（左右键调整）");
            }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(6);
        box.addView(interval, ilp);

        Switch swFade = makeSettingsSwitch("切换特效（淡入淡出）", slideshowFade);
        swFade.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                slideshowFade = isChecked;
                savePlaybackSettings();
                Log.i(TAG, "slideshow fade = " + isChecked);
            }
        });
        box.addView(swFade);

        Switch swMute = makeSettingsSwitch("动态壁纸静音", videoMuted);
        swMute.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                videoMuted = isChecked;
                savePlaybackSettings();
                if (videoPlayerRef != null) {
                    float volume = isChecked ? 0f : 1f;
                    try {
                        videoPlayerRef.setVolume(volume, volume);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        box.addView(swMute);


        TextView desc = new TextView(this);
        desc.setText("幻灯片按上面设定的间隔自动换壁纸；4K 模式下切换一律硬切，"
                + "「切换特效」只对 1080p 静态壁纸生效。\n"
                + "动态壁纸播放时默认静音，关掉本项后使用电视当前音量。\n"
                + "动态壁纸固定循环播放。\n\n"
                + "4K 图片目录：\n"
                + new File(getExternalFilesDir(null), FOUR_K_BG_DIR).getAbsolutePath());
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(0xFF9FB0C2);
        desc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        box.addView(desc, dlp);

        new AlertDialog.Builder(this)
                .setTitle("背景设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    private void stepSlideshowInterval(int delta) {
        int index = 0;
        for (int i = 0; i < SLIDESHOW_STEPS.length; i++) {
            if (SLIDESHOW_STEPS[i] == slideshowIntervalSec) {
                index = i;
                break;
            }
        }
        index = (index + delta + SLIDESHOW_STEPS.length) % SLIDESHOW_STEPS.length;
        slideshowIntervalSec = SLIDESHOW_STEPS[index];
        savePlaybackSettings();
        resetBgTimer();
        Log.i(TAG, "slideshow interval = " + slideshowIntervalSec + "s");
    }

    private TextView makeWallpaperControl(String glyph, final int direction) {
        TextView button = new TextView(this);
        button.setText(glyph);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.tile_bg);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.item_focus : R.drawable.tile_bg);
            }
        });
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchWallpaperBy(direction);
            }
        });
        return button;
    }

    private void switchWallpaperBy(int direction) {
        if (videoWallpaperMode) {
            switchVideoBy(direction);
        } else if (fourKStaticEnabled()) {
            switch4KStatic(direction);
        } else {
            advanceBackground(direction);
            resetBgTimer();
        }
    }

    private View makeMenuItem(int iconRes, final String label) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setBackgroundResource(R.drawable.tile_bg);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.item_focus : R.drawable.tile_bg);
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("壁纸管理".equals(label)) {
                    // 菜单直达壁纸管理页（播放设置在管理页里）
                    openWallpaperManager();
                } else {
                    // 其它菜单项功能待实现
                    Log.i(TAG, "menu item clicked: " + label + " (todo)");
                }
            }
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFF2F2F2);
        item.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(0xFFF2F2F2);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setSingleLine(true);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = dp(2);
        item.addView(text, textLp);

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                dp(MENU_ITEM_WIDTH_DP), dp(MENU_ITEM_HEIGHT_DP));
        itemLp.rightMargin = dp(8);
        item.setLayoutParams(itemLp);
        return item;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ---------- 壁纸管理页 ----------

    private void buildWallpaperManager() {
        managerModule = new FrameLayout(this);
        managerModule.setBackgroundColor(0xFF0B1017);
        managerModule.setVisibility(View.GONE);
        managerModule.setClickable(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(40), dp(28), dp(40), dp(22));
        managerModule.addView(column, matchParent());

        TextView title = new TextView(this);
        title.setText("壁纸管理");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        // tab 行：左边「静态壁纸 / 动态壁纸」，右边「导入 / 清空缓存 / 远程导入 / 播放设置 / 关闭」
        managerTabRow = new LinearLayout(this);
        managerTabRow.setOrientation(LinearLayout.HORIZONTAL);
        managerTabRow.setGravity(Gravity.CENTER_VERTICAL);
        managerTabRow.setPadding(0, dp(10), 0, 0);
        // 两个 tab 拼成一个整块（无间隙的分段控件）
        managerTabGroup = new LinearLayout(this);
        managerTabGroup.setOrientation(LinearLayout.HORIZONTAL);
        managerTabGroup.setBackgroundColor(0xFF141C27);
        managerTabImage = makeManagerTab("静态壁纸", MANAGER_IMAGES);
        managerTabVideo = makeManagerTab("动态壁纸", MANAGER_VIDEOS);
        managerTabGroup.addView(managerTabImage);
        managerTabGroup.addView(managerTabVideo);
        LinearLayout.LayoutParams tabGroupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        tabGroupLp.rightMargin = dp(16);
        managerTabRow.addView(managerTabGroup, tabGroupLp);

        column.addView(managerTabRow);

        managerEmpty = new TextView(this);
        managerEmpty.setText("还没有图片壁纸\n点下面「导入图片」，从 U 盘或本地存储添加");
        managerEmpty.setTextColor(0xFF8FA0B3);
        managerEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        managerEmpty.setGravity(Gravity.CENTER);
        managerEmpty.setVisibility(View.GONE);
        column.addView(managerEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        managerGrid = new GridView(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // 同一行内左右移动；到行首再按左留在原地，避免跨行走位
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                        && getSelectedItemPosition() % getNumColumns() == 0) {
                    return false;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        managerGrid.setNumColumns(5);
        managerGrid.setHorizontalSpacing(dp(12));
        managerGrid.setVerticalSpacing(dp(12));
        managerGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        managerGrid.setSelector(android.R.color.transparent);
        managerGrid.setClipToPadding(false);
        managerGrid.setPadding(0, dp(6), 0, dp(6));
        managerAdapter = new WallpaperAdapter();
        managerGrid.setAdapter(managerAdapter);
        managerGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showWallpaperActions(position);
            }
        });
        managerGrid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateManagerGridSelection(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateManagerGridSelection(false);
            }
        });
        managerGrid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateManagerGridSelection(hasFocus);
            }
        });
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(10);
        column.addView(managerGrid, gridLp);

        managerImportButton = makeManagerButton("导入图片", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showImportChooser();
            }
        });
        managerTabRow.addView(managerImportButton);
        managerCacheButton = makeManagerButton("清空缓存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearAllCache();
            }
        });
        managerTabRow.addView(managerCacheButton);
        managerTabRow.addView(makeManagerButton("背景设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBackgroundSettings();
            }
        }));

        // 状态栏：壁纸浏览模块（网格）下面
        managerStats = new TextView(this);
        managerStats.setTextColor(0xFFB8C4D2);
        managerStats.setFocusable(false);
        managerStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        managerStats.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        managerStats.setEllipsize(TextUtils.TruncateAt.END);
        managerStats.setSingleLine(true);
        managerStats.setPadding(0, dp(10), 0, 0);
        column.addView(managerStats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // tab 行 / 功能栏按 ↓ 一律把焦点交给壁纸网格
        View.OnKeyListener downToGrid = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN
                        || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return false;
                }
                if (managerGrid == null || activeManagerItems().isEmpty()) {
                    return false;
                }
                int index = Math.max(activeManagerIndex(), 0);
                managerGrid.setSelection(index);
                managerGrid.requestFocus();
                return true;
            }
        };
        for (int i = 0; i < managerTabRow.getChildCount(); i++) {
            managerTabRow.getChildAt(i).setOnKeyListener(downToGrid);
        }
        if (managerTabGroup != null) {
            for (int i = 0; i < managerTabGroup.getChildCount(); i++) {
                managerTabGroup.getChildAt(i).setOnKeyListener(downToGrid);
            }
        }
    }

    private TextView makeManagerButton(String label, final View.OnClickListener listener) {
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
                        ? R.drawable.item_focus : R.drawable.tile_bg);
            }
        });
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.rightMargin = dp(10);
        button.setLayoutParams(lp);
        return button;
    }

    private void openWallpaperManager() {
        if (managerModule == null || managerOpen) {
            return;
        }
        hideMenuImmediately();
        managerOpen = true;
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        managerGrid.setAdapter(managerAdapter);
        managerModule.setVisibility(View.VISIBLE);
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (menuModule != null) {
            menuModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        refreshManager();
        // 默认焦点落在当前分栏的 tab，不直接进网格
        if (managerType == MANAGER_VIDEOS && managerTabVideo != null) {
            managerTabVideo.requestFocus();
        } else if (managerTabImage != null) {
            managerTabImage.requestFocus();
        }
        pruneThumbCacheAsync();
        Log.i(TAG, "wallpaper manager opened, images=" + fourKItems.size()
                + " videos=" + videoItems.size());
    }

    private void closeWallpaperManager() {
        if (!managerOpen) {
            return;
        }
        managerOpen = false;
        if (managerModule != null) {
            managerModule.setVisibility(View.GONE);
        }
        if (managerGrid != null) {
            managerGrid.setAdapter(null);
        }
        // 内存缓存留着，下次进来直接命中；只有条目太多时才整体释放
        if (thumbnailCache.size() > 200) {
            thumbnailCache.clear();
        }
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        if (menuModule != null) {
            menuModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
        focusFirstDockItem();
        // 管理页关闭时，如果当前是动态壁纸就重新起一遍播放器：
        // 管理页期间视频层的 surface 可能被销毁重建，旧播放器会往废弃的 surface 送帧，
        // 回到桌面只剩一块黑 surface（用户反馈的「返回桌面黑屏」）。
        if (videoWallpaperMode) {
            Log.i(TAG, "manager closed, re-prepare video wallpaper");
            refreshVideoWallpaperMode();
        }
    }

    // ---------- 远程导入（局域网 HTTP 上传） ----------

    private void buildRemoteImportPage() {
        remoteModule = new FrameLayout(this);
        remoteModule.setBackgroundColor(0xFF0B1017);
        remoteModule.setVisibility(View.GONE);
        remoteModule.setClickable(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(64), dp(34), dp(64), dp(24));
        remoteModule.addView(column, matchParent());

        TextView title = new TextView(this);
        title.setText("远程导入");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("手机扫码，或在同一 WiFi 下用电脑/手机浏览器打开下面的地址，即可上传、切换、删除壁纸。\n"
                + "上传后需要点「设为当前」才会换画面；离开本页会自动关闭服务。");
        hint.setTextColor(0xFFB8C4D2);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(6), 1f);
        hint.setPadding(0, dp(10), 0, dp(18));
        column.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        remoteUrlView = new TextView(this);
        remoteUrlView.setText("正在启动…");
        remoteUrlView.setTextColor(0xFF4C8DFF);
        remoteUrlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        remoteUrlView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        remoteUrlView.setGravity(Gravity.CENTER);
        remoteUrlView.setPadding(dp(24), dp(10), dp(24), dp(10));
        remoteUrlView.setBackgroundColor(0xFF131B26);
        column.addView(remoteUrlView);

        remoteQrView = new ImageView(this);
        remoteQrView.setVisibility(View.GONE);
        remoteQrView.setBackgroundColor(Color.WHITE);
        remoteQrView.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qrParams.topMargin = dp(14);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(remoteQrView, qrParams);

        remoteStatusView = new TextView(this);
        remoteStatusView.setText("等待连接…");
        remoteStatusView.setTextColor(0xFF7F8FA3);
        remoteStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        remoteStatusView.setGravity(Gravity.CENTER);
        remoteStatusView.setPadding(0, dp(12), 0, dp(10));
        column.addView(remoteStatusView);

        View spacer = new View(this);
        column.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        remoteBackButton = makeManagerButton("返回", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeRemoteImport();
            }
        });
        actions.addView(remoteBackButton);
        column.addView(actions);
    }

    /** 把当前地址编码成二维码，遥控器扫不到也没关系，地址仍然在下面。 */
    private void updateRemoteQr(String url) {
        if (remoteQrView == null) {
            return;
        }
        try {
            QrCode.Symbol symbol = QrCode.encode(url, QrCode.EC_M);
            // 静区只留 2 个模块（标准建议 4），把省下的空间给内容区；外侧白卡片再补一点边
            final int quiet = 2;
            int scale = QrCode.scaleFor(symbol, dp(174), quiet);
            int[] pixels = QrCode.renderPixels(symbol, quiet, scale, 0xFF000000, 0xFFFFFFFF);
            int px = symbol.size + quiet * 2;
            px *= scale;
            Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, px, 0, 0, px, px);
            remoteQrView.setImageBitmap(bitmap);
            remoteQrView.setVisibility(View.VISIBLE);
            Log.i(TAG, "remote import qr: v" + symbol.version + " " + px + "px mask=" + symbol.mask);
        } catch (RuntimeException e) {
            remoteQrView.setVisibility(View.GONE);
            Log.w(TAG, "remote import qr failed: " + e.getMessage());
        }
    }

    private void openRemoteImport() {
        if (remoteModule == null || remoteOpen) {
            return;
        }
        remoteOpen = true;
        remoteModule.setVisibility(View.VISIBLE);
        remoteModule.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // 远程页在最上层，必须挡住管理页/桌面的焦点，否则遥控器会点到下面的网格
        if (managerModule != null) {
            managerModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (desktopModule != null) {
            desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        if (menuModule != null) {
            menuModule.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (remoteServer == null) {
            remoteServer = new RemoteImportServer(new RemoteHost());
            remoteServer.setAssetOpener(new RemoteImportServer.AssetOpener() {
                @Override
                public InputStream open(String assetName) throws IOException {
                    return getAssets().open(assetName);
                }
            });
        }
        if (remoteServer.start(8080)) {
            String ip = localIpv4();
            String url = "http://" + (ip == null ? "127.0.0.1" : ip)
                    + ":" + remoteServer.getPort() + "/";
            remoteUrlView.setText(url);
            if (ip == null) {
                // 没有局域网地址时，二维码指向 127.0.0.1，手机扫了也没用
                remoteQrView.setVisibility(View.GONE);
            } else {
                updateRemoteQr(url);
            }
            remoteStatusView.setText(ip == null
                    ? "没有检测到局域网地址，请确认电视已连接网络"
                    : "等待浏览器连接…");
            Log.i(TAG, "remote import opened: " + url);
        } else {
            remoteUrlView.setText("启动失败");
            remoteQrView.setVisibility(View.GONE);
            remoteStatusView.setText("端口 8080-8089 都被占用，请重试");
            Log.w(TAG, "remote import server start failed");
        }
        if (remoteBackButton != null) {
            remoteBackButton.post(new Runnable() {
                @Override
                public void run() {
                    remoteBackButton.requestFocus();
                }
            });
        }
    }

    private void closeRemoteImport() {
        if (!remoteOpen) {
            return;
        }
        remoteOpen = false;
        if (remoteServer != null) {
            remoteServer.stop();
        }
        if (remoteModule != null) {
            remoteModule.setVisibility(View.GONE);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Log.i(TAG, "remote import closed");
        if (managerOpen) {
            if (managerModule != null) {
                managerModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            keepManagerTabFocus(activeManagerIndex());
        } else {
            if (desktopModule != null) {
                desktopModule.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            focusFirstDockItem();
        }
    }

    /** 取第一个非回环 IPv4，优先 wlan/eth 网卡 */
    private String localIpv4() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
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

    private final class RemoteHost implements RemoteImportServer.Host {
        @Override
        public List<String> wallpaperNames() {
            List<String> names = callOnUi(new Callable<List<String>>() {
                @Override
                public List<String> call() {
                    List<String> out = new ArrayList<>(fourKItems.size());
                    for (BackgroundItem item : fourKItems) {
                        out.add(item.name);
                    }
                    return out;
                }
            }, 3000);
            return names == null ? new ArrayList<String>() : names;
        }

        @Override
        public String currentWallpaper() {
            return current4KName;
        }

        @Override
        public File wallpaperDir() {
            File base = getExternalFilesDir(null) != null
                    ? getExternalFilesDir(null) : getFilesDir();
            return new File(base, FOUR_K_BG_DIR);
        }

        @Override
        public synchronized String saveWallpaper(String fileName, InputStream in, long length)
                throws IOException {
            File dir = wallpaperDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File temp = new File(dir, ".upload-" + System.currentTimeMillis() + ".tmp");
            long written = 0;
            OutputStream out = null;
            try {
                out = new FileOutputStream(temp);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    written += read;
                }
                out.flush();
            } finally {
                if (out != null) {
                    out.close();
                }
            }
            if (written != length) {
                temp.delete();
                Log.w(TAG, "remote upload incomplete: " + written + "/" + length);
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                temp.delete();
                Log.w(TAG, "remote upload not an image: " + fileName);
                return null;
            }
            String base = fileName;
            String ext = ".jpg";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                base = fileName.substring(0, dot);
                ext = fileName.substring(dot);
            }
            File dest = new File(dir, base + ext);
            int suffix = 1;
            while (dest.exists()) {
                suffix++;
                dest = new File(dir, base + "_" + suffix + ext);
            }
            if (!temp.renameTo(dest)) {
                temp.delete();
                return null;
            }
            final String saved = dest.getName();
            final int width = bounds.outWidth;
            final int height = bounds.outHeight;
            Log.i(TAG, "remote upload stored: " + saved + " (" + width + "x" + height + ")");
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
                    if (!remoteOpen) {
                        Toast.makeText(LauncherActivity.this,
                                "已收到壁纸 " + saved, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return saved;
        }

        @Override
        public boolean applyWallpaper(final String name) {
            Boolean ok = callOnUi(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    for (BackgroundItem item : fourKItems) {
                        if (item.name.equals(name)) {
                            applyWallpaperByName(name);
                            return true;
                        }
                    }
                    return false;
                }
            }, 5000);
            return ok != null && ok;
        }

        @Override
        public boolean deleteWallpaper(final String name) {
            Boolean ok = callOnUi(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    for (BackgroundItem item : fourKItems) {
                        if (item.name.equals(name)) {
                            LauncherActivity.this.deleteWallpaper(item);
                            return !new File(item.filePath).exists();
                        }
                    }
                    return false;
                }
            }, 5000);
            return ok != null && ok;
        }

        @Override
        public List<String> videoNames() {
            List<String> names = callOnUi(new Callable<List<String>>() {
                @Override
                public List<String> call() {
                    List<String> out = new ArrayList<>(videoItems.size());
                    for (BackgroundItem item : videoItems) {
                        out.add(item.name);
                    }
                    return out;
                }
            }, 3000);
            return names == null ? new ArrayList<String>() : names;
        }

        @Override
        public String currentVideo() {
            return currentVideoName;
        }

        @Override
        public File videoDir() {
            return videoBgDir();
        }

        @Override
        public synchronized String saveVideo(String fileName, InputStream in, long length)
                throws IOException {
            if (!isVideoName(fileName) || length <= 0 || length > VIDEO_MAX_BYTES) {
                return null;
            }
            File dir = videoBgDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String ext = ".mp4";
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                ext = fileName.substring(dot).toLowerCase(Locale.US);
            }
            File temp = new File(dir, ".upload-" + System.currentTimeMillis() + ext);
            long written = 0;
            OutputStream out = null;
            try {
                out = new FileOutputStream(temp);
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    written += read;
                }
                out.flush();
            } finally {
                if (out != null) {
                    out.close();
                }
            }
            if (written != length) {
                temp.delete();
                Log.w(TAG, "remote video incomplete: " + written + "/" + length);
                return null;
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(temp.getAbsolutePath());
                String duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (TextUtils.isEmpty(duration) || "0".equals(duration)) {
                    temp.delete();
                    Log.w(TAG, "remote video not playable: " + fileName);
                    return null;
                }
            } catch (Throwable t) {
                temp.delete();
                Log.w(TAG, "remote video rejected: " + fileName, t);
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
            String base = fileName;
            if (dot > 0) {
                base = fileName.substring(0, dot);
            }
            File dest = new File(dir, base + ext);
            int suffix = 1;
            while (dest.exists()) {
                suffix++;
                dest = new File(dir, base + "_" + suffix + ext);
            }
            if (!temp.renameTo(dest)) {
                temp.delete();
                return null;
            }
            final String saved = dest.getName();
            Log.i(TAG, "remote video stored: " + saved + " (" + length + " bytes)");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    reloadVideoBackgrounds();
                    refreshManager();
                    if (!remoteOpen) {
                        Toast.makeText(LauncherActivity.this,
                                "已收到动态壁纸 " + saved, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return saved;
        }

        @Override
        public boolean applyVideo(final String name) {
            Boolean ok = callOnUi(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    for (BackgroundItem item : videoItems) {
                        if (item.name.equals(name)) {
                            applyVideoByName(name);
                            return true;
                        }
                    }
                    return false;
                }
            }, 8000);
            return ok != null && ok;
        }

        @Override
        public boolean deleteVideo(final String name) {
            Boolean ok = callOnUi(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    for (BackgroundItem item : videoItems) {
                        if (item.name.equals(name)) {
                            deleteVideoWallpaper(item);
                            return !new File(item.filePath).exists();
                        }
                    }
                    return false;
                }
            }, 5000);
            return ok != null && ok;
        }

        @Override
        public void onRemoteEvent(final String message) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (remoteStatusView != null) {
                        remoteStatusView.setText(message);
                    }
                }
            });
        }
    }

    /** 把任务丢到主线程执行并等结果；已经在主线程则直接执行 */
    private <T> T callOnUi(final Callable<T> task, long timeoutMs) {
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

    /** 网格有焦点时才给选中项画白框，避免和底部按钮的焦点框同时出现 */
    private void updateManagerGridSelection(boolean focused) {
        if (managerGrid == null) {
            return;
        }
        int selected = managerGrid.getSelectedItemPosition();
        int first = managerGrid.getFirstVisiblePosition();
        for (int i = 0; i < managerGrid.getChildCount(); i++) {
            boolean ring = focused && (first + i) == selected;
            managerGrid.getChildAt(i).setBackgroundResource(
                    ring ? R.drawable.item_focus : R.drawable.tile_bg);
        }
    }

    private List<BackgroundItem> activeManagerItems() {
        return managerType == MANAGER_VIDEOS ? videoItems : fourKItems;
    }

    /**
     * 当前壁纸在「静态 / 动态」两个分栏里只有一个：
     * 视频模式时静态栏没有当前项，图片模式时动态栏没有当前项。
     */
    private String activeManagerCurrentName() {
        if (managerType == MANAGER_VIDEOS) {
            return videoWallpaperMode ? currentVideoName : null;
        }
        return videoWallpaperMode ? null : current4KName;
    }

    private int activeManagerIndex() {
        if (managerType == MANAGER_VIDEOS) {
            return videoIndex;
        }
        return fourKIndex;
    }

    /** 让网格按整行对齐，避免顶部只露出上一行的几像素 */
    /** 壁纸被应用/移动/删除后：网格滚到指定位置，焦点仍留在 tab 行 */
    private void keepManagerTabFocus(int selection) {
        if (!managerOpen || managerGrid == null) {
            return;
        }
        List<BackgroundItem> items = activeManagerItems();
        if (selection >= 0 && selection < items.size()) {
            managerGrid.setSelection(selection);
            snapManagerGridToRow();
        }
        // 操作完回到当前分栏的 tab，而不是某个功能按钮
        if (managerType == MANAGER_IMAGES && managerTabImage != null) {
            managerTabImage.requestFocus();
        } else if (managerType == MANAGER_VIDEOS && managerTabVideo != null) {
            managerTabVideo.requestFocus();
        }
    }

    private void snapManagerGridToRow() {
        if (managerGrid == null) {
            return;
        }
        managerGrid.post(new Runnable() {
            @Override
            public void run() {
                if (managerGrid == null) {
                    return;
                }
                View firstView = managerGrid.getChildAt(0);
                if (firstView == null) {
                    return;
                }
                int dy = firstView.getTop() - managerGrid.getPaddingTop();
                if (dy != 0) {
                    managerGrid.scrollListBy(dy);
                }
            }
        });
    }

    private void setManagerType(int type) {
        if (managerType == type) {
            return;
        }
        managerType = type;
        updateManagerTabs();
        refreshManager();
        // 切换分栏只换内容，焦点落在新选中的 tab（下键才进网格）
        if (managerOpen) {
            if (type == MANAGER_IMAGES && managerTabImage != null) {
                managerTabImage.requestFocus();
            } else if (type == MANAGER_VIDEOS && managerTabVideo != null) {
                managerTabVideo.requestFocus();
            }
        }
    }

    private void updateManagerTabs() {
        if (managerCacheButton != null) {
            managerCacheButton.setVisibility(View.VISIBLE);
        }
        if (managerTabImage != null) {
            managerTabImage.setText("静态壁纸");
            applyManagerTabVisual(managerTabImage, managerType == MANAGER_IMAGES);
        }
        if (managerTabVideo != null) {
            managerTabVideo.setText("动态壁纸");
            applyManagerTabVisual(managerTabVideo, managerType == MANAGER_VIDEOS);
        }
        if (managerImportButton != null) {
            managerImportButton.setText(managerType == MANAGER_VIDEOS ? "导入视频" : "导入图片");
        }
        if (managerEmpty != null) {
            managerEmpty.setText(managerType == MANAGER_VIDEOS
                    ? "还没有动态壁纸\n点下面「导入视频」，从 U 盘或本地存储添加"
                    : "还没有图片壁纸\n点下面「导入图片」，从 U 盘或本地存储添加");
        }
    }

    /** 选中态用底色，焦点态用焦点框；两者同时存在时焦点框优先，避免切栏后焦点样式被刷掉。 */
    private void applyManagerTabVisual(TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : 0xFF8FA0B3);
        if (tab.hasFocus()) {
            tab.setBackgroundResource(R.drawable.item_focus);
        } else {
            tab.setBackgroundColor(selected ? 0xFF1E2A38 : 0xFF141C27);
        }
    }

    private TextView makeManagerTab(final String label, final int type) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextColor(0xFF8FA0B3);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tab.setGravity(Gravity.CENTER);
        tab.setBackgroundColor(0xFF141C27);
        tab.setFocusable(true);
        tab.setFocusableInTouchMode(true);
        tab.setPadding(dp(18), 0, dp(18), 0);
        tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackgroundResource(R.drawable.item_focus);
                } else {
                    v.setBackgroundColor(managerType == type ? 0xFF1E2A38 : 0xFF141C27);
                }
                updateManagerTabs();
            }
        });
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setManagerType(type);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(40));
        tab.setLayoutParams(lp);
        return tab;
    }

    private void refreshManager() {
        updateManagerTabs();
        if (managerAdapter != null) {
            managerAdapter.notifyDataSetChanged();
        }
        if (managerStats == null) {
            return;
        }
        List<BackgroundItem> items = activeManagerItems();
        boolean empty = items.isEmpty();
        if (managerGrid != null) {
            managerGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (managerEmpty != null) {
            managerEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        // 删到没有壁纸时，焦点从网格移到 tab 行的「导入」按钮
        if (empty && managerOpen && managerImportButton != null
                && (managerGrid == null || isFocusIn(managerGrid))) {
            managerImportButton.requestFocus();
        }
        if (!empty && managerOpen && managerGrid != null && isFocusIn(managerGrid)) {
            managerGrid.setSelection(Math.max(activeManagerIndex(), 0));
            snapManagerGridToRow();
        }
        String current = activeManagerCurrentName();
        StringBuilder sb = new StringBuilder();
        if (managerType == MANAGER_VIDEOS) {
            sb.append(items.size()).append(" 个视频 · ").append(formatSize(totalVideoSize()));
        } else {
            sb.append(items.size()).append(" 张 · 缓存 ").append(formatSize(totalCacheSize()));
        }
        if (!TextUtils.isEmpty(current)) {
            sb.append("\n当前 ").append(current);
        }
        managerStats.setText(sb.toString());
    }

    private long totalVideoSize() {
        long total = 0;
        for (BackgroundItem item : videoItems) {
            File file = new File(item.filePath);
            total += file.length();
        }
        return total;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private final class WallpaperAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return activeManagerItems().size();
        }

        @Override
        public Object getItem(int position) {
            return activeManagerItems().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            List<BackgroundItem> items = activeManagerItems();
            if (position < 0 || position >= items.size()) {
                return new FrameLayout(LauncherActivity.this);
            }
            BackgroundItem item = items.get(position);
            boolean selected = managerGrid != null && managerGrid.hasFocus()
                    && managerGrid.getSelectedItemPosition() == position;

            FrameLayout tile = new FrameLayout(LauncherActivity.this);
            tile.setPadding(dp(3), dp(3), dp(3), dp(3));
            tile.setBackgroundResource(selected ? R.drawable.item_focus : R.drawable.tile_bg);

            ImageView image = new ImageView(LauncherActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF1A2430);
            tile.addView(image, matchParent());
            loadThumbnail(image, item);

            TextView label = new TextView(LauncherActivity.this);
            label.setText(item.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setBackgroundColor(0xB3000000);
            label.setPadding(dp(8), dp(3), dp(8), dp(3));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.BOTTOM);
            tile.addView(label, labelLp);

            // 视频条目叠一个播放标记：4K 视频取不到帧时也能看出这是视频，不是坏图
            if (isVideoName(item.name)) {
                TextView play = new TextView(LauncherActivity.this);
                play.setText("\u25B6");
                play.setTextColor(0xE6FFFFFF);
                play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                tile.addView(play, playLp);
            }

            if (item.name.equals(activeManagerCurrentName())) {
                TextView badge = new TextView(LauncherActivity.this);
                badge.setText("当前");
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                badge.setBackgroundColor(0xCC1976D2);
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END);
                badgeLp.topMargin = dp(6);
                badgeLp.rightMargin = dp(6);
                tile.addView(badge, badgeLp);
            }

            tile.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(138)));
            return tile;
        }
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
        return safe + "_" + item.stamp + (isVideoName(item.name) ? "_v.jpg" : "_i.jpg");
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

    private void loadThumbnail(final ImageView view, final BackgroundItem item) {
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
        String path = item.filePath;
        if (path == null) {
            return null;
        }
        if (isVideoName(item.name)) {
            return decodeVideoThumbnail(path, targetW);
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW
                    && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            Log.w(TAG, "decode thumbnail failed: " + path, t);
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
    private Bitmap decodeVideoFrameWithCodec(String path, int targetW) {
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
    private Bitmap yuvImageToBitmap(Image image, int targetW) {
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

    private void showWallpaperActions(final int position) {
        final List<BackgroundItem> items = activeManagerItems();
        if (position < 0 || position >= items.size()) {
            return;
        }
        final BackgroundItem item = items.get(position);
        final boolean video = managerType == MANAGER_VIDEOS;
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();

        boolean isCurrent = item.name.equals(activeManagerCurrentName());
        labels.add(isCurrent ? "当前壁纸（重新应用）" : "设为当前壁纸");
        actions.add(1);
        if (!video) {
            if (position > 0) {
                labels.add("向前移动");
                actions.add(2);
            }
            if (position < items.size() - 1) {
                labels.add("向后移动");
                actions.add(3);
            }
            long cacheSize = cacheSizeFor(item);
            labels.add(cacheSize > 0 ? "清除此图缓存（" + formatSize(cacheSize) + "）" : "清除此图缓存");
            actions.add(4);
        }
        labels.add(video ? "删除视频" : "删除壁纸");
        actions.add(5);

        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int action = actions.get(which);
                        if (action == 1) {
                            if (video) {
                                applyVideoByName(item.name);
                            } else {
                                applyWallpaperByName(item.name);
                            }
                        } else if (action == 2) {
                            moveWallpaper(position, -1);
                        } else if (action == 3) {
                            moveWallpaper(position, 1);
                        } else if (action == 4) {
                            int cleared = deleteCacheFor(item);
                            refreshManager();
                            Toast.makeText(LauncherActivity.this,
                                    cleared > 0 ? "缓存已清除" : "这张图没有缓存",
                                    Toast.LENGTH_SHORT).show();
                        } else if (action == 5) {
                            confirmDeleteWallpaper(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyWallpaperByName(String name) {
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
        leaveVideoWallpaperMode();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_4K_BACKGROUND, name)
                .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        if (fourKStaticEnabled()) {
            fourKEncodeToken++;
            fourKEncoding = false;
            invalidate4KPlayback();
            if (!fourKStaticMode) {
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

    private void moveWallpaper(int position, int delta) {
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

    private void confirmDeleteWallpaper(final BackgroundItem item) {
        boolean video = isVideoName(item.name);
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

    private void deleteWallpaper(BackgroundItem item) {
        if (isVideoName(item.name)) {
            deleteVideoWallpaper(item);
            return;
        }
        File file = new File(item.filePath);
        boolean deleted = file.delete();
        if (!deleted) {
            Toast.makeText(this, "删除失败：" + item.name, Toast.LENGTH_LONG).show();
            return;
        }
        deleteCacheFor(item);
        deleteThumbFor(item);
        reloadBackgrounds();
        reload4KBackgrounds();
        refresh4KStaticMode();
        refreshManager();
        if (managerOpen && !fourKItems.isEmpty()) {
            keepManagerTabFocus(Math.max(fourKIndex, 0));
        }
        Toast.makeText(this, "已删除 " + item.name, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "wallpaper deleted: " + item.name);
    }

    private void deleteVideoWallpaper(BackgroundItem item) {
        boolean wasCurrent = item.name.equals(currentVideoName);
        File file = new File(item.filePath);
        if (!file.delete()) {
            Toast.makeText(this, "删除失败：" + item.name, Toast.LENGTH_LONG).show();
            return;
        }
        if (wasCurrent) {
            videoWallpaperMode = false;
            stopVideoWallpaper();
            bgFront.setVisibility(View.VISIBLE);
            bgBack.setVisibility(View.VISIBLE);
            currentVideoName = null;
            videoIndex = -1;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_VIDEO_BACKGROUND)
                    .putString(KEY_WALLPAPER_TYPE, TYPE_IMAGE).apply();
        }
        deleteThumbFor(item);
        reloadVideoBackgrounds();
        if (!videoWallpaperMode) {
            refresh4KStaticMode();
        }
        refreshManager();
        if (managerOpen && !videoItems.isEmpty()) {
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

    private long cacheSizeFor(BackgroundItem item) {
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

    private long totalCacheSize() {
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

    private int deleteCacheFor(BackgroundItem item) {
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

    private void confirmClearAllCache() {
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
    private void showImportChooser() {
        final boolean video = managerType == MANAGER_VIDEOS;
        final List<File> usbRoots = usbImportRoots();
        final List<String> labels = new ArrayList<>();
        labels.add(video ? "从本机导入视频" : "从本机导入图片");
        final int usbIndex = usbRoots.isEmpty() ? -1 : labels.size();
        if (usbIndex >= 0) {
            labels.add("从 USB 设备导入");
        }
        labels.add("远程导入（手机/电脑网页上传）");
        new AlertDialog.Builder(this)
                .setTitle(video ? "导入视频" : "导入图片")
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
        final boolean wantVideo = managerType == MANAGER_VIDEOS;
        File[] children = dir.listFiles();
        List<File> dirs = new ArrayList<>();
        final List<File> images = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && !child.getName().startsWith(".")) {
                    dirs.add(child);
                } else if (child.isFile()) {
                    boolean match = wantVideo
                            ? isVideoName(child.getName()) : isImageName(child.getName());
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
                                        : "导入失败：不是可播放的视频或文件过大",
                                Toast.LENGTH_LONG).show();
                        Log.i(TAG, "videos imported: " + okCount);
                    }
                });
            }
        }, "video-import").start();
    }

    /** 校验并复制视频到 files/backgrounds_video，返回新文件名；失败返回 null */
    private String copyVideoIntoDevice(File src) {
        if (src == null || !src.isFile() || !isVideoName(src.getName())) {
            return null;
        }
        if (src.length() <= 0 || src.length() > VIDEO_MAX_BYTES) {
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
        try {
            File dir = videoBgDir();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String name = src.getName();
            String base = name;
            String ext = ".mp4";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            File dest = new File(dir, base + ext);
            int suffix = 1;
            while (dest.exists()) {
                suffix++;
                dest = new File(dir, base + "_" + suffix + ext);
            }
            File tmp = new File(dir, ".upload-" + System.nanoTime() + ext);
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return null;
            }
            return dest.getName();
        } catch (Throwable t) {
            Log.w(TAG, "copy video failed: " + src.getName(), t);
            return null;
        }
    }

    /** 校验并复制到 files/backgrounds_4k，返回新文件名；失败返回 null */
    private String copyWallpaperIntoDevice(File src) {
        if (src == null || !src.isFile() || !isImageName(src.getName())) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            File baseDir = getExternalFilesDir(null) != null
                    ? getExternalFilesDir(null) : getFilesDir();
            File dir = new File(baseDir, FOUR_K_BG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String name = src.getName();
            String base = name;
            String ext = ".jpg";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
            File dest = new File(dir, base + ext);
            int suffix = 1;
            while (dest.exists()) {
                suffix++;
                dest = new File(dir, base + "_" + suffix + ext);
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dest);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                closeQuietly(in);
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            Log.i(TAG, "wallpaper imported: " + src.getAbsolutePath() + " -> " + dest.getName());
            return dest.getName();
        } catch (Throwable t) {
            Log.w(TAG, "import wallpaper failed: " + src.getAbsolutePath(), t);
            return null;
        }
    }

    // ---------- 背景轮播 ----------

    private final Runnable bgTick = new Runnable() {
        @Override
        public void run() {
            if (!slideshowEnabled || videoWallpaperMode) {
                return;
            }
            if (fourKStaticEnabled()) {
                if (fourKItems.size() > 1) {
                    switch4KStatic(1);
                }
            } else if (backgroundItems.size() > 1) {
                advanceBackground(1);
            }
            handler.postDelayed(this, slideshowIntervalSec * 1000L);
        }
    };

    /**
     * 优先读设备外部目录（/sdcard/Android/data/tv.dsh.launcher/files/backgrounds），
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

        String keep = currentBackgroundName;
        backgroundItems.clear();
        backgroundItems.addAll(found);

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

        File dir = getExternalFilesDir(BG_DIR);
        if (dir != null) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File[] files = dir.listFiles();
            if (files != null) {
                List<String> names = new ArrayList<>();
                for (File file : files) {
                    if (file.isFile() && isImageName(file.getName())) {
                        names.add(file.getName());
                    }
                }
                Collections.sort(names);
                for (String name : names) {
                    File file = new File(dir, name);
                    items.add(new BackgroundItem(name, null, file.getAbsolutePath(),
                            file.lastModified() ^ file.length()));
                }
            }
        }
        if (!items.isEmpty()) {
            return items;
        }

        try {
            String[] names = getAssets().list(BG_DIR);
            if (names != null) {
                List<String> assets = new ArrayList<>();
                for (String name : names) {
                    if (isImageName(name)) {
                        assets.add(name);
                    }
                }
                Collections.sort(assets);
                for (String name : assets) {
                    items.add(new BackgroundItem(name, name, null, 0L));
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "list background assets failed", e);
        }
        return items;
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    private void showBackgroundImmediately(int index) {
        bgSwitching = false;
        bgFront.animate().cancel();
        bgBack.animate().cancel();
        bgBack.setAlpha(0f);

        if (backgroundItems.isEmpty()) {
            bgFront.setImageResource(R.drawable.bg);
            bgFront.setAlpha(1f);
            backgroundIndex = -1;
            currentBackgroundName = null;
            wallpaperBlurKey = null;
            return;
        }
        if (index < 0 || index >= backgroundItems.size()) {
            index = 0;
        }
        BackgroundItem item = backgroundItems.get(index);
        Bitmap bitmap = loadBackground(item);
        if (bitmap == null) {
            bgFront.setImageResource(R.drawable.bg);
            bgFront.setAlpha(1f);
            backgroundIndex = -1;
            currentBackgroundName = null;
            wallpaperBlurKey = null;
            return;
        }
        bgFront.setImageBitmap(bitmap);
        bgFront.setAlpha(1f);
        backgroundIndex = index;
        currentBackgroundName = item.name;
        wallpaperBlurKey = null;
    }

    private Bitmap loadBackground(BackgroundItem item) {
        if (item.assetName != null) {
            return loadAsset(item.assetName);
        }
        return loadFile(item.filePath);
    }

    private Bitmap loadAsset(String name) {
        String path = BG_DIR + "/" + name;
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
    private void advanceBackground(int delta) {
        if (fourKStaticEnabled() || backgroundItems.size() < 2 || bgSwitching) {
            return;
        }
        int next;
        if (backgroundIndex < 0) {
            next = delta > 0 ? 0 : backgroundItems.size() - 1;
        } else {
            next = (backgroundIndex + delta + backgroundItems.size())
                    % backgroundItems.size();
        }
        if (next == backgroundIndex) {
            return;
        }

        BackgroundItem item = backgroundItems.get(next);
        Bitmap bitmap = loadBackground(item);
        if (bitmap == null) {
            return;
        }

        bgSwitching = true;
        final ImageView incoming = bgBack;
        final ImageView outgoing = bgFront;
        incoming.setImageBitmap(bitmap);
        if (slideshowFade) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(BG_FADE_MS)
                    .setInterpolator(BG_EASE).start();
            outgoing.animate().alpha(0f).setDuration(BG_FADE_MS)
                    .setInterpolator(BG_EASE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            bgSwitching = false;
                        }
                    })
                    .start();
        } else {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
            bgSwitching = false;
        }

        bgFront = incoming;
        bgBack = outgoing;
        backgroundIndex = next;
        currentBackgroundName = item.name;
        wallpaperBlurKey = null;
        Log.i(TAG, "background -> " + item.name);
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
        String key = currentBackgroundName + "#" + backgroundIndex;
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
            blur.setRadius(24f);
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

    private void resetBgTimer() {
        handler.removeCallbacks(bgTick);
        if (!slideshowEnabled || videoWallpaperMode) {
            return;
        }
        int count = fourKStaticEnabled() ? fourKItems.size() : backgroundItems.size();
        if (count > 1) {
            handler.postDelayed(bgTick, slideshowIntervalSec * 1000L);
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
        Date now = new Date();
        clockTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        clockDate.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
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
        if (weatherTemp == null) {
            return;
        }
        if (TextUtils.isEmpty(temp)) {
            weatherTemp.setText("--°");
            weatherIcon.setText("☁");
            weatherDesc.setText("天气暂不可用");
            return;
        }
        weatherTemp.setText(temp + "°");
        weatherIcon.setText(symbol);
        StringBuilder desc = new StringBuilder(condition);
        if (!TextUtils.isEmpty(place)) {
            if (desc.length() > 0) {
                desc.append(" · ");
            }
            desc.append(place);
        }
        weatherDesc.setText(desc.length() == 0 ? "—" : desc.toString());
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
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(weatherTick);
        handler.post(weatherTick);
        reloadBackgrounds();
        reload4KBackgrounds();
        resetBgTimer();
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
        if (managerOpen) {
            refreshManager();
            keepManagerTabFocus(-1);
        } else {
            // 兜底：从抽屉里开过应用后，桌面层可能还停在隐藏状态
            if (!drawerOpen && homeLayer != null
                    && homeLayer.getVisibility() != View.VISIBLE) {
                showHomeLayer(0);
            }
            focusFirstDockItem();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask 下按 HOME 可能只走 onNewIntent，不触发 onResume
        hideMenuImmediately();
        reloadBackgrounds();
        reload4KBackgrounds();
        reloadVideoBackgrounds();
        reloadAppsAsync();
        if (!managerOpen && homeLayer != null
                && homeLayer.getVisibility() != View.VISIBLE) {
            showHomeLayer(0);
        }
        focusFirstDockItem();
        if (videoWallpaperMode) {
            restoreVideoWallpaperOnForeground();
        } else {
            refresh4KStaticMode();
        }
        handleDebugWallpaperIntent(intent);
        if (intent != null && intent.getBooleanExtra("wallpaper_manager", false)) {
            if ("video".equals(intent.getStringExtra("manager_tab"))) {
                setManagerType(MANAGER_VIDEOS);
            }
            openWallpaperManager();
            if (intent.getBooleanExtra("remote_import", false)) {
                openRemoteImport();
            }
        }
    }

    /** 启动首屏显示后再扫描应用列表，避免首帧被 PackageManager 扫描拖慢 */
    private void loadAppsDeferred() {
        if (startupAppScanDone) {
            return;
        }
        startupAppScanDone = true;
        Log.i(TAG, "deferred app scan start");
        loadApps();
        if (grid != null && grid.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
        }
        refreshDock();
        if (getCurrentFocus() == null && wallpaperControls != null) {
            focusFirstDockItem();
        }
        Log.i(TAG, "deferred app scan done: " + allApps.size());
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
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(bgTick);
        if (wallpaperVideo != null && wallpaperVideo.isPlaying()) {
            wallpaperVideo.pause();
        }
        // 离开桌面后视频层的画面会被系统回收（回来是黑的），把 4K 状态清掉：
        // 下次 onResume 会重新贴一张缓存的预览图并重播一次，不再出现"黑屏再加载"。
        if (fourKStaticMode) {
            fourKVideoReady = false;
            fourKPreparing = false;
        }
        hideMenuImmediately();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (remoteServer != null) {
            remoteServer.stop();
            remoteServer = null;
        }
        stop4KStatic();
    }

    // ---------- 抽屉 ----------

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        returnFocusToArrows = wallpaperControls != null && isFocusIn(wallpaperControls);
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

    private void closeDrawer() {
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
        if (returnFocusToArrows && wallpaperControlPrev != null) {
            wallpaperControlPrev.requestFocus();
        } else {
            focusFirstDockItem();
        }
        returnFocusToArrows = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (managerOpen) {
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
        if (remoteOpen) {
            closeRemoteImport();
            return;
        }
        if (managerOpen) {
            closeWallpaperManager();
            return;
        }
        if (menuOpen) {
            closeMenu();
            return;
        }
        if (drawerOpen) {
            closeDrawer();
            return;
        }
        // 桌面上按返回不退出
    }

    // ---------- 顶部菜单条 ----------

    /** 4K 视频层真正露出来时菜单悬浮；否则（UI 壁纸/4K 关闭）沿用桌面下压 */
    private boolean shouldFloatMenu() {
        return videoWallpaperMode || fourKStaticEnabled();
    }

    private void openMenu() {
        if (menuModule == null || menuOpen || drawerOpen) {
            return;
        }
        menuOpen = true;
        returnFocusToArrows = wallpaperControls != null && isFocusIn(wallpaperControls);
        // 4K 视频壁纸显示时菜单悬浮；否则桌面整体下移
        if (desktopModule != null) {
            desktopModule.animate().cancel();
            if (shouldFloatMenu()) {
                desktopModule.setTranslationY(0f);
            } else {
                desktopModule.animate().translationY(dp(MENU_HEIGHT_DP))
                        .setDuration(MENU_ANIM_MS).setInterpolator(OPEN_EASE).start();
            }
        }
        menuModule.animate().cancel();
        menuModule.setVisibility(View.VISIBLE);
        menuModule.setTranslationY(-dp(MENU_HEIGHT_DP));
        menuModule.animate().translationY(0f).setDuration(MENU_ANIM_MS)
                .setInterpolator(OPEN_EASE).start();

        if (menuBar.getChildCount() > 0) {
            menuBar.getChildAt(0).requestFocus();
        }
    }

    private void closeMenu() {
        if (menuModule == null || !menuOpen) {
            return;
        }
        menuOpen = false;
        menuModule.animate().cancel();
        menuModule.animate().translationY(-dp(MENU_HEIGHT_DP))
                .setDuration(MENU_ANIM_MS).setInterpolator(CLOSE_EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (menuOpen) {
                            return;
                        }
                        menuModule.setVisibility(View.GONE);
                        if (returnFocusToArrows && wallpaperControlPrev != null) {
                            wallpaperControlPrev.requestFocus();
                        } else {
                            focusFirstDockItem();
                        }
                        returnFocusToArrows = false;
                    }
                }).start();

        if (desktopModule != null) {
            desktopModule.animate().cancel();
            if (shouldFloatMenu()) {
                desktopModule.setTranslationY(0f);
            } else {
                desktopModule.animate().translationY(0f)
                        .setDuration(MENU_ANIM_MS).setInterpolator(CLOSE_EASE).start();
            }
        }
    }

    private void hideMenuImmediately() {
        menuOpen = false;
        if (menuModule != null) {
            menuModule.animate().cancel();
            menuModule.setVisibility(View.GONE);
            menuModule.setTranslationY(-dp(MENU_HEIGHT_DP));
        }
        if (desktopModule != null) {
            desktopModule.animate().cancel();
            desktopModule.setTranslationY(0f);
        }
    }


    /**
     * 底部焦点环：◀ ▶ 应用1 … 应用N，首尾相接。
     * 最左应用按左 → ▶；▶ 按左 → ◀；◀ 按左 → 最右应用；反向同理。
     */
    private boolean handleDockArrowRing(int key) {
        if (wallpaperControls == null || dock == null || dock.getChildCount() == 0) {
            return false;
        }
        View focused = getCurrentFocus();
        if (focused == null) {
            return false;
        }
        if (isFocusIn(wallpaperControls)) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (focused == wallpaperControlNext && wallpaperControlPrev != null) {
                    wallpaperControlPrev.requestFocus();
                } else {
                    dock.getChildAt(dock.getChildCount() - 1).requestFocus();
                }
            } else {
                if (focused == wallpaperControlPrev && wallpaperControlNext != null) {
                    wallpaperControlNext.requestFocus();
                } else {
                    dock.getChildAt(0).requestFocus();
                }
            }
            return true;
        }
        if (isFocusIn(dock)) {
            int index = dockIndexOf(focused);
            if (index < 0) {
                return false;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && index == 0
                    && wallpaperControlNext != null) {
                wallpaperControlNext.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && index == dock.getChildCount() - 1
                    && wallpaperControlPrev != null) {
                wallpaperControlPrev.requestFocus();
                return true;
            }
        }
        return false;
    }


    /** 焦点可能落在 dock 条目的子 View 上，这里沿父链找回顶层条目下标 */
    private int dockIndexOf(View focused) {
        View v = focused;
        while (v != null) {
            if (v.getParent() == dock) {
                return dock.indexOfChild(v);
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return -1;
    }

    private boolean isFocusIn(View parent) {
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
        // 管理页打开时只处理页面内的焦点导航，不再触发桌面菜单/抽屉
        if (managerOpen) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            if (menuOpen) {
                // 菜单打开时，上/下都收起菜单
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    closeMenu();
                    return true;
                }
            } else if (!drawerOpen && key == KeyEvent.KEYCODE_DPAD_UP) {
                // dock 和左右键现在同一行，上键一律直接呼出菜单
                openMenu();
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
        // 重建前记住焦点所在的应用：removeAllViews 会清掉焦点，
        // 不恢复的话 Android 会把焦点甩给同一行的 ◀ ▶（点应用启动瞬间闪一下左右键）。
        String keepKey = null;
        if (dock != null && isFocusIn(dock)) {
            int idx = dockIndexOf(getCurrentFocus());
            if (idx >= 0 && idx < dock.getChildCount()) {
                Object tag = dock.getChildAt(idx).getTag();
                if (tag instanceof String) {
                    keepKey = (String) tag;
                }
            }
        }
        dock.removeAllViews();
        List<AppEntry> source = buildDockSource();
        int count = Math.min(source.size(), DOCK_SHOW);
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("　");
            empty.setFocusable(true);
            empty.setFocusableInTouchMode(true);
            dock.addView(empty);
            return;
        }
        for (int i = 0; i < count; i++) {
            dock.addView(makeDockItem(source.get(i)));
        }
        if (keepKey != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                if (keepKey.equals(dock.getChildAt(i).getTag())) {
                    dock.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
    }

    /**
     * 底部栏排序：把"打开过的应用"和"新安装的应用"放在同一条时间线上。
     * 单个应用的时间 = max(最后打开时间, 首次安装时间)，按时间倒序取前 7 个。
     * 新装的 App 会立刻排到前面；打开过的 App 按最近一次打开时间前移。
     */
    private List<AppEntry> buildDockSource() {
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

    private View makeDockItem(final AppEntry entry) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(10), dp(8), dp(10), dp(6));
        item.setBackgroundResource(R.drawable.tile_bg);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        // 重建 dock 时靠这个 key 把焦点还给同一个应用
        item.setTag(entry.key);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        item.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        int size = dp(46);
        icon.setImageBitmap(squareIcon(entry.icon, size));
        item.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(3);
        item.addView(label, tlp);

        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackgroundResource(hasFocus
                        ? R.drawable.item_focus : R.drawable.tile_bg);
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

    private Bitmap gridIcon(AppEntry entry) {
        Bitmap cached = gridIconCache.get(entry.key);
        if (cached == null) {
            cached = squareIcon(entry.icon, dp(48));
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

    private void focusFirstDockItem() {
        if (dock != null && dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
        }
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
            if (convertView instanceof FrameLayout
                    && ((FrameLayout) convertView).getChildCount() > 0) {
                cell = (FrameLayout) convertView;
                tile = (LinearLayout) cell.getChildAt(0);
            } else {
                // 外层 cell 负责占满 7 列格子，内层 tile 固定 84dp 宽并居中
                cell = new FrameLayout(LauncherActivity.this);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                tile = new LinearLayout(LauncherActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(2), dp(6), dp(2), dp(4));

                ImageView icon = new ImageView(LauncherActivity.this);
                icon.setId(0x2001);
                int size = dp(48);
                tile.addView(icon, new LinearLayout.LayoutParams(size, size));

                TextView label = new TextView(LauncherActivity.this);
                label.setId(0x2002);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(3);
                tile.addView(label, lp);

                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.gravity = Gravity.CENTER_HORIZONTAL;
                cell.addView(tile, tlp);
            }

            tile.setBackgroundResource(position == drawerSelectedPosition
                    ? R.drawable.item_focus : R.drawable.tile_bg);

            AppEntry entry = allApps.get(position);
            ((ImageView) tile.findViewById(0x2001)).setImageBitmap(gridIcon(entry));
            ((TextView) tile.findViewById(0x2002)).setText(entry.label);
            return cell;
        }
    }
}
