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
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 壁纸管理页（全屏浮层）：静态 / 动态分栏、网格分页、状态栏和单张壁纸的操作菜单。
 * 只负责界面与本页状态；壁纸列表、应用 / 删除 / 导入等仍由 LauncherActivity 执行。
 */
final class WallpaperManagerPage {

    private static final String TAG = "DSH_MANAGER";

    static final int IMAGES = 0;
    static final int VIDEOS = 1;
    /** 每页数量：6 列 × 3 行，正好一屏，翻页即可换一整屏 */
    static final int PAGE_SIZE = 18;

    private final LauncherActivity activity;
    private final FrameLayout module;

    private GridView grid;
    private TextView stats;
    private TextView empty;
    private TextView importButton;
    private TextView cacheButton;
    private LinearLayout tabRow;
    private LinearLayout tabGroup;
    private TextView tabImage;
    private TextView tabVideo;
    /** 分页选择条：上一页 / 第 X/Y 页（可打开页码选择）/ 下一页 */
    private LinearLayout pagerRow;
    private TextView prevPageButton;
    private TextView pageButton;
    private TextView nextPageButton;
    private WallpaperAdapter adapter;

    private boolean open;
    private int type = IMAGES;
    /** 壁纸网格当前页（从 0 开始） */
    private int page;

    WallpaperManagerPage(LauncherActivity activity) {
        this.activity = activity;

        module = new FrameLayout(activity);
        module.setBackgroundColor(0xFF0B1017);
        module.setVisibility(View.GONE);
        module.setClickable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        // 底部少留 8dp：状态栏文字垂直居中，文字底边到屏幕的距离才和左右留白一致
        column.setPadding(dp(28), dp(28), dp(28), dp(20));
        module.addView(column, matchParent());

        TextView title = new TextView(activity);
        title.setText("壁纸管理");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        column.addView(title);

        // tab 行：左边「静态壁纸 / 动态壁纸」，右边「导入 / 清空缓存 / 背景设置」
        tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(0, dp(10), 0, 0);
        // 两个 tab 拼成一个整块（无间隙的分段控件）
        tabGroup = new LinearLayout(activity);
        tabGroup.setOrientation(LinearLayout.HORIZONTAL);
        // 圆形只由两个 tab 自己负责，整组背景保持透明，避免圆角外露出直角底色
        tabGroup.setBackgroundColor(Color.TRANSPARENT);
        tabImage = makeTab("静态壁纸", IMAGES);
        tabVideo = makeTab("动态壁纸", VIDEOS);
        tabGroup.addView(tabImage);
        tabGroup.addView(tabVideo);
        LinearLayout.LayoutParams tabGroupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        tabGroupLp.rightMargin = dp(16);
        tabRow.addView(tabGroup, tabGroupLp);

        column.addView(tabRow);

        // 分页选择条：放在状态栏右侧，多页时显示，可快速翻页/跳到指定页
        pagerRow = new LinearLayout(activity);
        pagerRow.setOrientation(LinearLayout.HORIZONTAL);
        pagerRow.setGravity(Gravity.CENTER_VERTICAL);
        pagerRow.setVisibility(View.GONE);
        prevPageButton = activity.makeManagerButton("\u2039 上一页", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpPage(page - 1);
            }
        });
        pageButton = activity.makeManagerButton("第 1/1 页", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPagePicker();
            }
        });
        nextPageButton = activity.makeManagerButton("下一页 \u203A", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpPage(page + 1);
            }
        });
        pagerRow.addView(prevPageButton);
        pagerRow.addView(pageButton);
        pagerRow.addView(nextPageButton);
        // 状态栏里用更矮的分页按钮，避免把底部栏撑高
        TextView[] pagerButtons = {prevPageButton, pageButton, nextPageButton};
        for (int i = 0; i < pagerButtons.length; i++) {
            TextView button = pagerButtons[i];
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
            lp.height = dp(36);
            lp.rightMargin = i == pagerButtons.length - 1 ? 0 : dp(8);
            button.setLayoutParams(lp);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setPadding(dp(12), 0, dp(12), 0);
            // 未聚焦时背景透明，只保留文字
            button.setBackgroundColor(Color.TRANSPARENT);
            button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        v.setBackgroundResource(R.drawable.dock_focus);
                    } else {
                        v.setBackgroundColor(Color.TRANSPARENT);
                    }
                }
            });
        }

        // 分页条：↑ 回网格最后一行，↓ 没有可去的地方
        View.OnKeyListener pagerKeys = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (grid != null && adapter != null && adapter.getCount() > 0) {
                        grid.setSelection(adapter.getCount() - 1);
                        grid.requestFocus();
                    }
                    return true;
                }
                return false;
            }
        };
        for (int i = 0; i < pagerRow.getChildCount(); i++) {
            pagerRow.getChildAt(i).setOnKeyListener(pagerKeys);
        }

        empty = new TextView(activity);
        empty.setText("还没有图片壁纸\n点下面「导入壁纸」，从 U 盘或本地存储添加");
        empty.setTextColor(0xFF8FA0B3);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        column.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new GridView(activity) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int key = event.getKeyCode();
                // 同一行内左右移动；到行首再按左留在原地，避免跨行走位
                if (key == KeyEvent.KEYCODE_DPAD_LEFT
                        && getSelectedItemPosition() % getNumColumns() == 0) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int selected = getSelectedItemPosition();
                    int cols = getNumColumns();
                    // 第一行再按 ↑：回到顶部 tab（分页条在底部，不再走分页条）
                    if (key == KeyEvent.KEYCODE_DPAD_UP && selected >= 0 && selected < cols) {
                        if (type == IMAGES && tabImage != null) {
                            tabImage.requestFocus();
                        } else if (tabVideo != null) {
                            tabVideo.requestFocus();
                        }
                        return true;
                    }
                    // 最后一行再按 ↓：把焦点交给底部右侧的分页选择器，不自动换页
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN && selected >= 0) {
                        int rows = (getCount() + cols - 1) / cols;
                        if (rows > 0 && selected / cols == rows - 1) {
                            if (pagerRow != null
                                    && pagerRow.getVisibility() == View.VISIBLE) {
                                pageButton.requestFocus();
                                return true;
                            }
                        }
                    }
                }
                return super.dispatchKeyEvent(event);
            }
        };
        grid.setNumColumns(6);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(6), 0, dp(6));
        adapter = new WallpaperAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showWallpaperActions(page * PAGE_SIZE + position);
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateGridSelection(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateGridSelection(false);
            }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateGridSelection(hasFocus);
            }
        });
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(10);
        column.addView(grid, gridLp);

        importButton = activity.makeManagerButton("导入壁纸", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.showImportChooser();
            }
        });
        tabRow.addView(importButton);
        cacheButton = activity.makeManagerButton("清空缓存", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.confirmClearAllCache();
            }
        });
        tabRow.addView(cacheButton);
        tabRow.addView(activity.makeManagerButton("背景设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.showBackgroundSettings();
            }
        }));

        // 状态栏：左边数量/当前壁纸，右边分页选择器
        stats = new TextView(activity);
        stats.setTextColor(0xFFB8C4D2);
        stats.setFocusable(false);
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stats.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        stats.setEllipsize(TextUtils.TruncateAt.END);
        stats.setSingleLine(true);
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(10), 0, 0);
        statusRow.addView(stats, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(pagerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // tab 行 / 功能栏按 ↓ 一律把焦点交给壁纸网格
        View.OnKeyListener downToGrid = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN
                        || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return false;
                }
                if (grid == null || activePageItems().isEmpty()) {
                    return false;
                }
                // 从 tab 进入网格不自动定位当前壁纸，直接落在本页第一张
                focusGridTop();
                return true;
            }
        };
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            tabRow.getChildAt(i).setOnKeyListener(downToGrid);
        }
        for (int i = 0; i < tabGroup.getChildCount(); i++) {
            tabGroup.getChildAt(i).setOnKeyListener(downToGrid);
        }
    }

    FrameLayout getView() {
        return module;
    }

    boolean isOpen() {
        return open;
    }

    int getType() {
        return type;
    }

    void setType(int newType) {
        if (type == newType) {
            return;
        }
        type = newType;
        page = 0;
        updateTabs();
        refresh();
        // 切换分栏只换内容，焦点落在新选中的 tab（下键才进网格）
        if (open) {
            focusTab();
        }
    }

    /** 显示页面：重新挂适配器、刷新网格并把焦点放到当前分栏的 tab */
    void open() {
        if (open) {
            return;
        }
        open = true;
        page = 0;
        grid.setAdapter(adapter);
        module.setVisibility(View.VISIBLE);
        refresh();
        focusTab();
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        module.setVisibility(View.GONE);
        grid.setAdapter(null);
    }

    /** 当前分栏的 tab 重新拿焦点（远程页关闭等场景用） */
    void requestTabFocus() {
        focusTab();
    }

    private void focusTab() {
        if (type == VIDEOS && tabVideo != null) {
            tabVideo.requestFocus();
        } else if (tabImage != null) {
            tabImage.requestFocus();
        }
    }

    /** 壁纸列表变化后刷新网格、状态栏和空状态 */
    void refresh() {
        updateTabs();
        // 只把页码夹到合法范围，不因为「当前壁纸」而跳页
        ensurePageForIndex(-1);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (stats == null) {
            return;
        }
        List<BackgroundItem> items = activeItems();
        boolean isEmpty = items.isEmpty();
        if (grid != null) {
            grid.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        }
        if (empty != null) {
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        }
        // 删到没有壁纸时，焦点从网格移到 tab 行的「导入」按钮
        if (isEmpty && open && importButton != null
                && (grid == null || activity.isFocusIn(grid))) {
            importButton.requestFocus();
        }
        int index = activeIndex();
        if (!isEmpty && open && grid != null && activity.isFocusIn(grid)
                && index >= 0 && index / PAGE_SIZE == page) {
            grid.setSelection(localIndex(index));
            snapGridToRow();
        }
        updateStats();
    }

    /** 壁纸被应用/移动/删除后：网格滚到指定位置，焦点仍留在 tab 行 */
    void keepTabFocus(int selection) {
        if (!open || grid == null) {
            return;
        }
        List<BackgroundItem> items = activeItems();
        if (selection >= 0 && selection < items.size()) {
            ensurePageForIndex(selection);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            grid.setSelection(localIndex(selection));
            snapGridToRow();
        }
        // 操作完回到当前分栏的 tab，而不是某个功能按钮
        focusTab();
    }

    // ---------- 分栏数据（全部来自宿主） ----------

    private List<BackgroundItem> activeItems() {
        return type == VIDEOS ? activity.managerVideoItems() : activity.managerImageItems();
    }

    /**
     * 当前壁纸在「静态 / 动态」两个分栏里只有一个：
     * 视频模式时静态栏没有当前项，图片模式时动态栏没有当前项。
     */
    private String activeCurrentName() {
        return type == VIDEOS
                ? activity.managerVideoCurrentName() : activity.managerImageCurrentName();
    }

    private int activeIndex() {
        return type == VIDEOS ? activity.managerVideoIndex() : activity.managerImageIndex();
    }

    /** 当前分栏当前页的壁纸（最多 PAGE_SIZE 个） */
    private List<BackgroundItem> activePageItems() {
        List<BackgroundItem> all = activeItems();
        int start = page * PAGE_SIZE;
        if (start >= all.size()) {
            return Collections.emptyList();
        }
        return all.subList(start, Math.min(start + PAGE_SIZE, all.size()));
    }

    private int pageCount() {
        int total = activeItems().size();
        return total == 0 ? 1 : (total + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** 把页码夹到合法范围；index >= 0 时切到该壁纸所在的页 */
    private void ensurePageForIndex(int index) {
        if (index >= 0) {
            page = index / PAGE_SIZE;
        }
        int pages = pageCount();
        if (page < 0) {
            page = 0;
        } else if (page >= pages) {
            page = pages - 1;
        }
    }

    private int localIndex(int globalIndex) {
        if (globalIndex < 0) {
            return 0;
        }
        return globalIndex % PAGE_SIZE;
    }

    private void snapGridToRow() {
        if (grid == null) {
            return;
        }
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (grid == null) {
                    return;
                }
                View firstView = grid.getChildAt(0);
                if (firstView == null) {
                    return;
                }
                int dy = firstView.getTop() - grid.getPaddingTop();
                if (dy != 0) {
                    grid.scrollListBy(dy);
                }
            }
        });
    }

    /** 焦点进入网格并选中本页第一张（从 tab / 分页条按 ↓ 时用） */
    private void focusGridTop() {
        if (grid == null || activePageItems().isEmpty()) {
            return;
        }
        grid.setSelectionFromTop(0, 0);
        grid.requestFocus();
    }

    /** 切换页码并刷新网格；焦点留在底部右侧的分页条上 */
    private boolean switchPage(int newPage) {
        int pages = pageCount();
        if (pages <= 0) {
            return false;
        }
        if (newPage < 0) {
            newPage = 0;
        } else if (newPage >= pages) {
            newPage = pages - 1;
        }
        if (newPage == page) {
            return false;
        }
        page = newPage;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateStats();
        if (grid == null) {
            return true;
        }
        // 重新挂适配器强制 GridView 丢掉上一页的滚动偏移，否则新页会停在中间某一行
        grid.setAdapter(null);
        grid.setAdapter(adapter);
        grid.setSelectionFromTop(0, 0);
        // 不主动移动焦点：点击「下一页」后焦点仍留在该按钮上
        return true;
    }

    /** 分页条上的上一页 / 下一页 */
    private void jumpPage(int newPage) {
        if (switchPage(newPage)) {
            Log.i(TAG, "wallpaper page jumped: page=" + (page + 1));
        }
    }

    /** 弹出页码选择：壁纸页数多时直接跳到某一页 */
    private void showPagePicker() {
        final int pages = pageCount();
        if (pages <= 1) {
            return;
        }
        final String[] labels = new String[pages];
        for (int i = 0; i < pages; i++) {
            labels[i] = "第 " + (i + 1) + " 页" + (i == page ? "（当前）" : "");
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择页码")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (switchPage(which) && pageButton != null) {
                            pageButton.requestFocus();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 同步分页条的文案、可用状态和显隐 */
    private void updatePager() {
        if (pagerRow == null) {
            return;
        }
        int pages = pageCount();
        boolean show = pages > 1;
        if (pagerRow.getVisibility() != (show ? View.VISIBLE : View.GONE)) {
            boolean hadFocus = activity.isFocusIn(pagerRow);
            pagerRow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show && hadFocus) {
                focusTab();
            }
        }
        if (!show) {
            return;
        }
        if (pageButton != null) {
            pageButton.setText("第 " + (page + 1) + " / " + pages + " 页");
        }
        if (prevPageButton != null) {
            // 不设 setEnabled(false)：禁用会让焦点从按钮上掉走，只做置灰
            prevPageButton.setAlpha(page > 0 ? 1f : 0.4f);
        }
        if (nextPageButton != null) {
            nextPageButton.setAlpha(page < pages - 1 ? 1f : 0.4f);
        }
    }

    private void updateTabs() {
        if (cacheButton != null) {
            cacheButton.setVisibility(View.VISIBLE);
        }
        if (tabImage != null) {
            tabImage.setText("静态壁纸");
            applyTabVisual(tabImage, type == IMAGES);
        }
        if (tabVideo != null) {
            tabVideo.setText("动态壁纸");
            applyTabVisual(tabVideo, type == VIDEOS);
        }
        if (empty != null) {
            empty.setText(type == VIDEOS
                    ? "还没有动态壁纸\n点下面「导入壁纸」，从 U 盘或本地存储添加"
                    : "还没有图片壁纸\n点下面「导入壁纸」，从 U 盘或本地存储添加");
        }
    }

    /** 选中态用底色，焦点态用焦点框；两者同时存在时焦点框优先，避免切栏后焦点样式被刷掉。 */
    private void applyTabVisual(TextView tab, boolean selected) {
        tab.setTextColor(selected ? Color.WHITE : 0xFF8FA0B3);
        boolean left = tab == tabImage;
        if (tab.hasFocus()) {
            tab.setBackground(makeTabBackground(0x7AB8D8F2, left));
        } else {
            tab.setBackground(makeTabBackground(
                    selected ? 0xFF1E2A38 : 0xFF141C27, left));
        }
    }

    /**
     * 分段控件单个 tab 的背景：左侧 tab 只圆左上/左下，右侧 tab 只圆右上/右下，
     * 中间拼缝保持直角；选中和焦点态复用同一套圆角。
     */
    private GradientDrawable makeTabBackground(int fillColor, boolean left) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(fillColor);
        float r = dp(6);
        bg.setCornerRadii(left
                ? new float[]{r, r, 0, 0, 0, 0, r, r}
                : new float[]{0, 0, r, r, r, r, 0, 0});
        return bg;
    }

    private TextView makeTab(final String label, final int tabType) {
        TextView tab = new TextView(activity);
        tab.setText(label);
        tab.setTextColor(0xFF8FA0B3);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(makeTabBackground(0xFF141C27, tabType == IMAGES));
        tab.setFocusable(true);
        tab.setFocusableInTouchMode(true);
        tab.setPadding(dp(18), 0, dp(18), 0);
        tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateTabs();
            }
        });
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setType(tabType);
            }
        });
        tab.setLayoutParams(new LinearLayout.LayoutParams(dp(120), dp(40)));
        return tab;
    }

    /** 网格有焦点时才给选中项画白框，避免和底部按钮的焦点框同时出现 */
    private void updateGridSelection(boolean focused) {
        if (grid == null) {
            return;
        }
        int selected = grid.getSelectedItemPosition();
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            boolean ring = focused && (first + i) == selected;
            grid.getChildAt(i).setBackgroundResource(
                    ring ? R.drawable.manager_item_focus : R.drawable.tile_bg);
        }
    }

    private void updateStats() {
        if (stats == null) {
            updatePager();
            return;
        }
        List<BackgroundItem> items = activeItems();
        String current = activeCurrentName();
        StringBuilder sb = new StringBuilder();
        if (type == VIDEOS) {
            sb.append(items.size()).append(" 个视频 · ")
                    .append(WallpaperCaches.formatSize(totalVideoSize()));
        } else {
            sb.append(items.size()).append(" 张 · 缓存 ")
                    .append(WallpaperCaches.formatSize(activity.caches().totalFourKCacheSize()));
        }
        if (!TextUtils.isEmpty(current)) {
            sb.append("\n当前 ").append(current);
        }
        stats.setText(sb.toString());
        updatePager();
    }

    private long totalVideoSize() {
        long total = 0;
        for (BackgroundItem item : activity.managerVideoItems()) {
            File file = new File(item.filePath);
            total += file.length();
        }
        return total;
    }

    private void showWallpaperActions(final int position) {
        final List<BackgroundItem> items = activeItems();
        if (position < 0 || position >= items.size()) {
            return;
        }
        final BackgroundItem item = items.get(position);
        final boolean video = type == VIDEOS;
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();

        boolean isCurrent = item.name.equals(activeCurrentName());
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
            long cacheSize = activity.caches().fourKCacheSizeFor(item);
            labels.add(cacheSize > 0
                    ? "清除此图缓存（" + WallpaperCaches.formatSize(cacheSize) + "）" : "清除此图缓存");
            actions.add(4);
        }
        labels.add(video ? "删除视频" : "删除壁纸");
        actions.add(5);

        new AlertDialog.Builder(activity)
                .setTitle(item.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int action = actions.get(which);
                        if (action == 1) {
                            if (video) {
                                activity.applyVideoByName(item.name);
                            } else {
                                activity.applyWallpaperByName(item.name);
                            }
                        } else if (action == 2) {
                            activity.moveWallpaper(position, -1);
                        } else if (action == 3) {
                            activity.moveWallpaper(position, 1);
                        } else if (action == 4) {
                            int cleared = activity.caches().deleteFourKCacheFor(item);
                            refresh();
                            Toast.makeText(activity,
                                    cleared > 0 ? "缓存已清除" : "这张图没有缓存",
                                    Toast.LENGTH_SHORT).show();
                        } else if (action == 5) {
                            activity.confirmDeleteWallpaper(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(float v) {
        return activity.dp(v);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private final class WallpaperAdapter extends BaseAdapter {

        /** 壁纸卡片圆角：缩略图、底部文件名浮层和聚焦描边一起裁成圆角 */
        private final ViewOutlineProvider tileOutline = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        };

        @Override
        public int getCount() {
            return activePageItems().size();
        }

        @Override
        public Object getItem(int position) {
            return activePageItems().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            List<BackgroundItem> items = activePageItems();
            if (position < 0 || position >= items.size()) {
                return new FrameLayout(activity);
            }
            BackgroundItem item = items.get(position);
            boolean selected = grid != null && grid.hasFocus()
                    && grid.getSelectedItemPosition() == position;

            FrameLayout tile = new FrameLayout(activity);
            tile.setPadding(dp(3), dp(3), dp(3), dp(3));
            tile.setClipToOutline(true);
            tile.setOutlineProvider(tileOutline);
            tile.setBackgroundResource(selected
                    ? R.drawable.manager_item_focus : R.drawable.tile_bg);

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // 图片自身也按圆角裁切：父容器的 clipToOutline 只裁到了背景描边，
            // 图片内容仍是直角，这里用背景 Outline + clipToOutline 把位图裁圆。
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setColor(0xFF1A2430);
            thumbBg.setCornerRadius(dp(5));
            image.setBackground(thumbBg);
            image.setClipToOutline(true);
            tile.addView(image, matchParent());
            activity.loadThumbnail(image, item);

            TextView label = new TextView(activity);
            label.setText(item.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            // 底部文件名浮层只圆下面两个角，避免盖住卡片的圆角
            GradientDrawable labelBg = new GradientDrawable();
            labelBg.setColor(0xB3000000);
            float labelRadius = dp(5);
            labelBg.setCornerRadii(new float[]{
                    0, 0, 0, 0, labelRadius, labelRadius, labelRadius, labelRadius});
            label.setBackground(labelBg);
            label.setPadding(dp(8), dp(3), dp(8), dp(3));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.BOTTOM);
            tile.addView(label, labelLp);

            // 视频条目叠一个播放标记：4K 视频取不到帧时也能看出这是视频，不是坏图
            if (WallpaperStore.isVideoName(item.name)) {
                TextView play = new TextView(activity);
                play.setText("\u25B6");
                play.setTextColor(0xE6FFFFFF);
                play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                tile.addView(play, playLp);
            }

            // 内置壁纸单独标识：左上角灰色角标，与右上角蓝色「当前」区分开
            if (item.builtin) {
                TextView builtinBadge = new TextView(activity);
                builtinBadge.setText("内置");
                builtinBadge.setTextColor(Color.WHITE);
                builtinBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                builtinBadge.setBackgroundColor(0xCC37474F);
                builtinBadge.setPadding(dp(6), dp(1), dp(6), dp(1));
                FrameLayout.LayoutParams builtinLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START);
                builtinLp.topMargin = dp(6);
                builtinLp.leftMargin = dp(6);
                tile.addView(builtinBadge, builtinLp);
            }

            if (item.name.equals(activeCurrentName())) {
                TextView badge = new TextView(activity);
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
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));
            return tile;
        }
    }
}
