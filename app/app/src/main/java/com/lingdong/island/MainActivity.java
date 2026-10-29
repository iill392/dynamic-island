package com.lingdong.island;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 10;
    private TextView statusView;
    private SharedPreferences prefs;
    private android.widget.Switch masterSwitch;
    private android.widget.Switch liquidSwitch;
    private TextView topPermissionText;
    private TextView permissionRowSub;
    private TextView headerStatus;
    private TextView headerPlay;
    private boolean updatingTopSwitches;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("island", MODE_PRIVATE);
        setContentView(buildUi());
        // v6.7: white settings page -> dark status/navigation bar icons.
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        // Ask only while the permission is still missing (asking again on every launch is noise).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        if (IslandService.isRunning()) {
            refreshGeometryService();
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F8FA);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- Blue header like dynamicSpot Pro ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF0B7FA8);
        header.setPadding(dp(22), dp(26), dp(22), dp(24));
        root.addView(header, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headerTop = new LinearLayout(this);
        headerTop.setOrientation(LinearLayout.HORIZONTAL);
        headerTop.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(headerTop);

        LinearLayout headerTexts = new LinearLayout(this);
        headerTexts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerTop.addView(headerTexts, htlp);

        TextView title = new TextView(this);
        title.setText("游龙灵动岛");
        title.setTextSize(32);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD));
        headerTexts.addView(title);

        headerStatus = new TextView(this);
        headerStatus.setTextSize(13);
        headerStatus.setTextColor(0xCCFFFFFF);
        headerStatus.setPadding(0, dp(6), 0, 0);
        headerTexts.addView(headerStatus);

        headerPlay = new TextView(this);
        headerPlay.setText("▶");
        headerPlay.setTextSize(24);
        headerPlay.setTextColor(0xFFFFFFFF);
        headerPlay.setGravity(android.view.Gravity.CENTER);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(0x33FFFFFF);
        playBg.setShape(GradientDrawable.OVAL);
        headerPlay.setBackground(playBg);
        headerPlay.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        headerPlay.setOnClickListener(v -> toggleMasterFromHeader());
        headerTop.addView(headerPlay);

        // ---- Menu rows ----
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(18), dp(6), dp(18), dp(24));
        root.addView(menu);

        LinearLayout masterRow = addMenuRow(menu, "灵动岛", "通知 / 充电 / 音量 / 解锁",
                0xFF26A69A, R.drawable.ic_menu_notif, true, null);
        masterSwitch = (android.widget.Switch) masterRow.getChildAt(2);
        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingTopSwitches) return;
            setMasterEnabled(isChecked);
        });

        addMenuRow(menu, "通用", "显示设置和互动",
                0xFFC0A020, R.drawable.ic_menu_general, false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_GENERAL));
        addMenuRow(menu, "外观", "尺寸、圆角和样式",
                0xFF43A047, R.drawable.ic_menu_appearance, false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_APPEARANCE));
        addMenuRow(menu, "位置", "更改大小和位置",
                0xFF4CAF50, R.drawable.ic_menu_position, false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_POSITION));

        LinearLayout permRow = addMenuRow(menu, "权限", "点击逐项授权",
                0xFF3F51B5, R.drawable.ic_menu_permission, false,
                () -> startActivity(new Intent(this, PermissionActivity.class)));
        // subtitle TextView is child 1 of the text column which is child 1 of the row
        permissionRowSub = (TextView) ((LinearLayout) permRow.getChildAt(1)).getChildAt(1);

        LinearLayout liquidRow = addMenuRow(menu, "液态玻璃", "独立液态玻璃灵动岛",
                0xFF7E57C2, R.drawable.ic_menu_liquid, true, null);
        liquidSwitch = (android.widget.Switch) liquidRow.getChildAt(2);
        liquidSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingTopSwitches) return;
            prefs.edit().putBoolean("use_liquid", isChecked).apply();
            if (prefs.getBoolean("enabled", false) && IslandService.isRunning()) {
                stopIslandServices();
                startSelectedService();
            }
            refresh();
        });

        TextView footer = new TextView(this);
        footer.setText("bata【严禁外传】\n开启前请先在「权限」中逐项授权。");
        footer.setTextSize(12);
        footer.setTextColor(0xFF8A8A93);
        footer.setPadding(dp(18), dp(2), dp(18), dp(26));
        root.addView(footer);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void openCategory(String category) {
        Intent i = new Intent(this, SettingsCategoryActivity.class);
        i.putExtra(SettingsCategoryActivity.EXTRA_CATEGORY, category);
        startActivity(i);
    }

    /**
     * v8.63: one dynamicSpot-style menu row: round coloured icon, title/subtitle and either a
     * Switch or a chevron. Returns the row so callers can grab the switch.
     */
    private LinearLayout addMenuRow(LinearLayout parent, String titleText, String subText,
                                    int color, int iconRes, boolean withSwitch, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), dp(4), dp(18));
        row.setBackgroundColor(0xFFFFFFFF);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        icon.setBackground(circle);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFFFFFFF);
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int iconBox = dp(64);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconBox, iconBox);
        ilp.rightMargin = dp(20);
        icon.setPadding(dp(15), dp(15), dp(15), dp(15));
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, tlp);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(22);
        title.setTextColor(0xFF1A1A1F);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.NORMAL));
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subText);
        sub.setTextSize(15);
        sub.setTextColor(0xFF7A7A84);
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);

        if (withSwitch) {
            android.widget.Switch sw = new android.widget.Switch(this);
            sw.setShowText(false);
            row.addView(sw);
        } else {
            TextView chevron = new TextView(this);
            chevron.setText("›");
            chevron.setTextSize(30);
            chevron.setTextColor(0xFFB0B0B8);
            chevron.setGravity(android.view.Gravity.CENTER);
            chevron.setPadding(dp(10), 0, dp(8), 0);
            row.addView(chevron);
        }

        if (click != null) row.setOnClickListener(v -> click.run());
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0xFFEDEEF2);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.leftMargin = dp(84);
        parent.addView(divider, dlp);
        return row;
    }

    private void toggleMasterFromHeader() {
        boolean on = prefs.getBoolean("enabled", false) || IslandService.isLiquidRunning();
        if (on) {
            setMasterEnabled(false);
        } else if (!hasRequiredPermissions()) {
            toastText("请先在权限页逐项授权，返回后再开启灵动岛");
            startActivity(new Intent(this, PermissionActivity.class));
        } else {
            setMasterEnabled(true);
        }
    }

    private void setMasterEnabled(boolean on) {
        if (!on) {
            prefs.edit().putBoolean("enabled", false).apply();
            stopIslandServices();
            refresh();
            return;
        }
        if (!hasRequiredPermissions()) {
            updatingTopSwitches = true;
            if (masterSwitch != null) masterSwitch.setChecked(false);
            updatingTopSwitches = false;
            toastText("请先在权限页逐项授权，返回后再开启灵动岛");
            startActivity(new Intent(this, PermissionActivity.class));
            return;
        }
        prefs.edit().putBoolean("enabled", true).apply();
        stopIslandServices();
        startSelectedService();
        refresh();
    }

    private boolean ensureService() {
        if (!canShowIsland()) {
            toastText("请先授予悬浮窗权限，或开启「增强模式（无障碍）」");
            return false;
        }
        if (!IslandService.isRunning()) {
            prefs.edit().putBoolean("enabled", true).apply();
            IslandService.start(this, null);
        }
        return true;
    }

    /**
     * The island can be hosted either by a regular application overlay (needs "display over other
     * apps") or by the accessibility service's trusted overlay (needs no such permission).
     */
    private boolean canShowIsland() {
        return Settings.canDrawOverlays(this) || isAccessibilityAccessEnabled();
    }

    /** v8.62: top category — three side-by-side cards (island / liquid glass / permissions). */
    private void buildTopSwitchRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(8));
        root.addView(row);

        masterSwitch = addTopSwitchCard(row, "开启灵动岛");
        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingTopSwitches) return;
            if (!isChecked) {
                prefs.edit().putBoolean("enabled", false).apply();
                stopIslandServices();
                refresh();
                return;
            }
            if (!hasRequiredPermissions()) {
                updatingTopSwitches = true;
                buttonView.setChecked(false);
                updatingTopSwitches = false;
                toastText("请先在权限页逐项授权，返回后再开启灵动岛");
                startActivity(new Intent(this, PermissionActivity.class));
                return;
            }
            prefs.edit().putBoolean("enabled", true).apply();
            stopIslandServices();
            startSelectedService();
            refresh();
        });

        liquidSwitch = addTopSwitchCard(row, "液态玻璃");
        liquidSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingTopSwitches) return;
            prefs.edit().putBoolean("use_liquid", isChecked).apply();
            if (prefs.getBoolean("enabled", false) && IslandService.isRunning()) {
                stopIslandServices();
                startSelectedService();
            }
            refresh();
        });

        // The third card is the permission entry; not a preference switch.
        LinearLayout permCard = new LinearLayout(this);
        permCard.setOrientation(LinearLayout.VERTICAL);
        permCard.setGravity(android.view.Gravity.CENTER);
        permCard.setBackground(roundedBg(0xFFF0F6FF, 16, 0xFFCFE0FF));
        permCard.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(96), 1f);
        plp.setMargins(dp(4), 0, dp(0), 0);
        permCard.setLayoutParams(plp);
        TextView permTitle = new TextView(this);
        permTitle.setText("权限");
        permTitle.setTextSize(14);
        permTitle.setTextColor(0xFF1F1F24);
        permTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        permTitle.setGravity(android.view.Gravity.CENTER);
        permCard.addView(permTitle);
        topPermissionText = new TextView(this);
        topPermissionText.setTextSize(11);
        topPermissionText.setTextColor(0xFF2563EB);
        topPermissionText.setGravity(android.view.Gravity.CENTER);
        topPermissionText.setPadding(0, dp(6), 0, 0);
        permCard.addView(topPermissionText);
        permCard.setOnClickListener(v -> startActivity(new Intent(this, PermissionActivity.class)));
        row.addView(permCard);
    }

    private android.widget.Switch addTopSwitchCard(LinearLayout row, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        card.setBackground(roundedBg(0xFFF7F7FA, 16, 0xFFE1E2E8));
        card.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(96), 1f);
        lp.setMargins(0, 0, dp(8), 0);
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(13);
        label.setTextColor(0xFF1F1F24);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(android.view.Gravity.CENTER);
        card.addView(label);
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setShowText(false);
        sw.setPadding(0, dp(6), 0, 0);
        card.addView(sw);
        row.addView(card);
        return sw;
    }

    private GradientDrawable roundedBg(int fill, int radiusDp, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }

    /** v8.62: required before the master switch may be turned on. */
    private boolean hasRequiredPermissions() {
        boolean base = Settings.canDrawOverlays(this) || isAccessibilityAccessEnabled();
        boolean notif = isNotificationAccessEnabled();
        boolean runtime = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
        return base && notif && runtime;
    }

    private void stopIslandServices() {
        stopService(new Intent(this, IslandService.class));
        try { stopService(new Intent(this, LiquidIslandService.class)); } catch (Throwable ignored) {}
    }

    private void startSelectedService() {
        if (prefs.getBoolean("use_liquid", false)) {
            LiquidIslandService.start(this);
        } else {
            IslandService.start(this, null);
        }
    }

    private void updateTopPermissionState() {
        if (topPermissionText == null) return;
        int done = 0;
        if (Settings.canDrawOverlays(this) || isAccessibilityAccessEnabled()) done++;
        if (isNotificationAccessEnabled()) done++;
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED) done++;
        topPermissionText.setText(done + "/3\n点击授权");
        topPermissionText.setTextColor(done >= 3 ? 0xFF2E9E5B : 0xFF2563EB);
    }

    private TextView categoryTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(19);
        tv.setTextColor(0xFF111114);
        tv.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD));
        tv.setPadding(0, dp(26), 0, dp(10));
        return tv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF6B6B75);
        tv.setPadding(0, dp(22), 0, dp(10));
        return tv;
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(0xFF1A1A1F);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F5F8);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFDADBE3);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.bottomMargin = dp(10);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    /**
     * v8.56: one independent visibility switch. Every scenario defaults to true; turning one off
     * only suppresses that island state.
     */
    private void addVisibilityToggle(LinearLayout parent, String title, String key, boolean def) {
        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText(title);
        cb.setTextSize(14);
        cb.setTextColor(0xFF1F1F24);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setPadding(0, dp(3), 0, dp(3));
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            refreshGeometryService();
        });
        parent.addView(cb);
    }

    /** v5.7: a labelled percent slider backed by SharedPreferences "island". */
    private void addPercentSlider(LinearLayout root, String title, String key,
                                  int def, int min, int max) {
        TextView label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(0xFF33333A);
        label.setPadding(0, dp(8), 0, 0);
        root.addView(label);

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        int saved = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        bar.setMax(max - min);
        bar.setProgress(saved - min);
        label.setText(title + "：" + saved + "%");
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                label.setText(title + "：" + (min + progress) + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                prefs.edit().putInt(key, min + sb.getProgress()).apply();
                refreshGeometryService();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.bottomMargin = dp(2);
        root.addView(bar, lp);
    }

    /** v8.2: choose the Clock/timer app explicitly, like dynamicSpot's per-app timer flag. */
    private void showTimerAppPicker() {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN);
                launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
                Set<String> seen = new HashSet<>();
                List<ResolveInfo> apps = new ArrayList<>();
                for (ResolveInfo ri : infos) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    if (seen.add(pkg)) apps.add(ri);
                }
                Collections.sort(apps, new Comparator<ResolveInfo>() {
                    @Override public int compare(ResolveInfo a, ResolveInfo b) {
                        return String.CASE_INSENSITIVE_ORDER.compare(
                                a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
                    }
                });
                int n = apps.size();
                final String[] labels = new String[n];
                final String[] packages = new String[n];
                for (int i = 0; i < n; i++) {
                    labels[i] = apps.get(i).loadLabel(pm).toString();
                    packages[i] = apps.get(i).activityInfo.packageName;
                }
                String savedPkg = prefs.getString("timer_app_pkg", "");
                int checked = -1;
                for (int i = 0; i < n; i++) {
                    if (packages[i].equals(savedPkg)) { checked = i; break; }
                }
                final int initialChecked = checked;
                final int[] selected = {checked};
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("选择计时器应用（秒表/计时器）")
                            .setSingleChoiceItems(labels, initialChecked,
                                    (d, which) -> selected[0] = which)
                            .setPositiveButton("保存", (d, which) -> {
                                if (selected[0] < 0) {
                                    prefs.edit().remove("timer_app_pkg")
                                            .remove("timer_app_label").apply();
                                    toastText("未选择，计时器将自动识别");
                                } else {
                                    prefs.edit()
                                            .putString("timer_app_pkg", packages[selected[0]])
                                            .putString("timer_app_label", labels[selected[0]])
                                            .apply();
                                    toastText("计时器应用：" + labels[selected[0]]);
                                }
                                refreshGeometryService();
                            })
                            .setNeutralButton("自动识别", (d, which) -> {
                                prefs.edit().remove("timer_app_pkg")
                                        .remove("timer_app_label").apply();
                                refreshGeometryService();
                                toastText("已恢复自动识别");
                            })
                            .setNegativeButton("取消", null)
                            .create().show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toastText("读取应用列表失败：" + t.getMessage()));
            }
        }, "island-timer-picker").start();
    }

    private void refreshGeometryService() {
        if (!IslandService.isRunning()) return;
        if (IslandService.isLiquidRunning()) {
            LiquidIslandService.start(this, IslandService.ACTION_REFRESH);
        } else {
            IslandService.start(this, IslandService.ACTION_REFRESH);
        }
    }

    /** v7.6: pick which apps are allowed to pop the notification island (multi-choice). */
    private void showNotificationAppPicker() {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN);
                launcher.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
                Set<String> seen = new HashSet<>();
                List<ResolveInfo> apps = new ArrayList<>();
                for (ResolveInfo ri : infos) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    if (seen.add(pkg)) apps.add(ri);
                }
                Collections.sort(apps, new Comparator<ResolveInfo>() {
                    @Override public int compare(ResolveInfo a, ResolveInfo b) {
                        return String.CASE_INSENSITIVE_ORDER.compare(
                                a.loadLabel(pm).toString(), b.loadLabel(pm).toString());
                    }
                });
                int n = apps.size();
                final String[] labels = new String[n];
                final String[] packages = new String[n];
                for (int i = 0; i < n; i++) {
                    labels[i] = apps.get(i).loadLabel(pm).toString();
                    packages[i] = apps.get(i).activityInfo.packageName;
                }
                final boolean[] checked = new boolean[n];
                Set<String> saved = prefs.getStringSet("notif_apps", null);
                boolean all = saved == null;
                for (int i = 0; i < n; i++) {
                    checked[i] = all || saved.contains(packages[i]);
                }
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("选择显示通知的应用")
                            .setMultiChoiceItems(labels, checked,
                                    (d, which, isChecked) -> checked[which] = isChecked)
                            .setPositiveButton("保存", (d, which) -> {
                                Set<String> selected = new HashSet<>();
                                for (int i = 0; i < checked.length; i++) {
                                    if (checked[i]) selected.add(packages[i]);
                                }
                                prefs.edit().putStringSet("notif_apps", selected).apply();
                                refreshGeometryService();
                                toastText("已保存：" + selected.size() + " 个应用会显示通知");
                            })
                            .setNeutralButton("全部不选", (d, which) -> {
                                prefs.edit().putStringSet("notif_apps", new HashSet<>()).apply();
                                refreshGeometryService();
                                toastText("已清空，所有应用通知都不显示");
                            })
                            .setNegativeButton("取消", null)
                            .create();
                    dialog.show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toastText("读取应用列表失败：" + t.getMessage()));
            }
        }, "island-app-picker").start();
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notif = isNotificationAccessEnabled();
        boolean running = IslandService.isRunning();
        boolean a11y = isAccessibilityAccessEnabled();
        if (statusView != null) {
            statusView.setText("悬浮窗权限：" + (overlay ? "已授予" : "未授予")
                    + "\n通知使用权：" + (notif ? "已授予" : "未授予（计时器/音乐需要）")
                    + "\n无障碍增强：" + (a11y ? "已开启（岛显示在状态栏上方）" : "未开启（可选）")
                    + "\n灵动岛服务：" + (running ? "运行中" : "未运行"));
            statusView.setTextColor((overlay || a11y) && running ? 0xFF2E9E5B : 0xFF33333A);
        }
        if (masterSwitch != null) {
            updatingTopSwitches = true;
            masterSwitch.setChecked(prefs.getBoolean("enabled", false)
                    || IslandService.isLiquidRunning());
            if (liquidSwitch != null) {
                liquidSwitch.setChecked(prefs.getBoolean("use_liquid", false)
                        || IslandService.isLiquidRunning());
            }
            updatingTopSwitches = false;
        }
        updateTopPermissionState();
        if (headerStatus != null) {
            headerStatus.setText("bata【严禁外传】 · " + (running ? "运行中" : "未运行"));
        }
        if (headerPlay != null) {
            headerPlay.setText(running ? "■" : "▶");
        }
        if (permissionRowSub != null) {
            int done = 0;
            if (overlay || a11y) done++;
            if (notif) done++;
            if (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            == PackageManager.PERMISSION_GRANTED) done++;
            permissionRowSub.setText(done + "/3 已授权"
                    + (done >= 3 ? " · 已完成" : " · 点击逐项授权"));
            permissionRowSub.setTextColor(done >= 3 ? 0xFF2E9E5B : 0xFF7A7A84);
        }
    }

    private boolean isAccessibilityAccessEnabled() {
        if (IslandAccessibilityService.isConnected()) return true;
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_accessibility_services");
        return !TextUtils.isEmpty(flat) && flat.contains(getPackageName());
    }

    private boolean isNotificationAccessEnabled() {
        if (Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationListenerAccessGranted(
                    new android.content.ComponentName(this, IslandNotificationListener.class));
        }
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return !TextUtils.isEmpty(flat) && flat.contains(getPackageName());
    }

    private void toastText(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Open a system settings page without crashing on ROMs that do not ship it. */
    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toastText("无法打开系统设置，请手动到设置中授权");
            }
        }
    }

    /** Overlay permission page: some ROMs only accept the bare action, some only the package URI. */
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                openSettings(Settings.ACTION_SETTINGS);
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

