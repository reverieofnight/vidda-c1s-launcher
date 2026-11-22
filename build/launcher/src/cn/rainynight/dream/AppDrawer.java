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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 应用抽屉：毛玻璃底层 + 应用网格，负责开合动画、网格选中态与图标复用。
 * 应用数据来自 AppRepository，启动应用仍由 LauncherActivity 执行。
 */
final class AppDrawer {

    private final LauncherActivity activity;
    private final AppRepository repository;
    private final FrameLayout container;
    private final ImageView frost;
    private final GridView grid;
    private final AppAdapter adapter = new AppAdapter();

    private boolean open;
    private int selectedPosition;

    AppDrawer(LauncherActivity activity, AppRepository repository) {
        this.activity = activity;
        this.repository = repository;

        container = new FrameLayout(activity);
        container.setBackgroundColor(Color.TRANSPARENT);
        container.setVisibility(View.GONE);

        frost = new ImageView(activity);
        frost.setScaleType(ImageView.ScaleType.CENTER_CROP);
        container.addView(frost, matchParent());

        View tint = new View(activity);
        tint.setBackgroundColor(0x8C0B1017);
        container.addView(tint, matchParent());

        grid = new GridView(activity);
        grid.setNumColumns(activity.iconsPerRow());
        grid.setHorizontalSpacing(activity.dp(8));
        grid.setVerticalSpacing(activity.dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(activity.dp(26), activity.dp(26), activity.dp(26), activity.dp(26));
        grid.setClipToPadding(false);
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                List<AppEntry> apps = repository.all();
                if (position >= 0 && position < apps.size()) {
                    activity.launch(apps.get(position));
                }
            }
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
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
                        && grid.getSelectedItemPosition() < activity.iconsPerRow()) {
                    activity.closeDrawer();
                    return true;
                }
                return false;
            }
        });
        container.addView(grid, matchParent());
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    View getView() {
        return container;
    }

    boolean isOpen() {
        return open;
    }

    void setColumns(int columns) {
        grid.setNumColumns(columns);
    }

    /** 图标大小变化后重建适配器，强制重绘所有格子 */
    void resetAdapter() {
        grid.setAdapter(adapter);
    }

    void notifyAppsChanged() {
        adapter.notifyDataSetChanged();
    }

    void resetSelection() {
        selectedPosition = 0;
    }

    void show() {
        open = true;
        container.setVisibility(View.VISIBLE);
        container.setTranslationY(activity.screenHeight);
        container.setAlpha(0f);
        container.animate().translationY(0f).alpha(1f)
                .setDuration(LauncherActivity.OPEN_MS)
                .setInterpolator(LauncherActivity.OPEN_EASE).start();
        grid.requestFocus();
        grid.setSelection(0);
    }

    void hide(final Runnable onHidden) {
        open = false;
        container.animate().translationY(activity.screenHeight).alpha(0f)
                .setDuration(LauncherActivity.CLOSE_MS)
                .setInterpolator(LauncherActivity.CLOSE_EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        container.setVisibility(View.GONE);
                        if (onHidden != null) {
                            onHidden.run();
                        }
                    }
                }).start();
    }

    void setFrost(Bitmap bitmap) {
        if (bitmap != null) {
            frost.setImageBitmap(bitmap);
        } else {
            frost.setImageDrawable(null);
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return repository.all().size();
        }

        @Override
        public Object getItem(int position) {
            return repository.all().get(position);
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
                cell = new FrameLayout(activity);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                tile = new LinearLayout(activity);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);

                // 正方形遮罩层：只包住图标，名称放在遮罩下方
                mask = new FrameLayout(activity);
                mask.setId(0x2003);
                int maskSize = activity.dp(activity.iconMaskDp());
                tile.addView(mask, new LinearLayout.LayoutParams(maskSize, maskSize));

                ImageView icon = new ImageView(activity);
                icon.setId(0x2001);
                int size = activity.dp(activity.iconSizeDp());
                mask.addView(icon, new FrameLayout.LayoutParams(
                        size, size, Gravity.CENTER));

                TextView label = new TextView(activity);
                label.setId(0x2002);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, activity.iconLabelSp());
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = activity.dp(4);
                tile.addView(label, lp);

                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                        activity.dp(activity.iconMaskDp() + 16),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.gravity = Gravity.CENTER_HORIZONTAL;
                cell.addView(tile, tlp);
            }

            mask.setBackgroundResource(position == selectedPosition
                    ? R.drawable.dock_focus : R.drawable.tile_bg);

            List<AppEntry> apps = repository.all();
            if (position < 0 || position >= apps.size()) {
                return cell;
            }
            AppEntry entry = apps.get(position);
            ((ImageView) tile.findViewById(0x2001)).setImageBitmap(activity.icons().gridIcon(entry));
            ((TextView) tile.findViewById(0x2002)).setText(entry.label);
            return cell;
        }
    }
}
