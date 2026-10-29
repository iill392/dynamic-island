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
            IslandService.start(this, IslandService.ACTION_REFRESH);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(36), pad, dp(36));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("游龙灵动岛");
        title.setTextSize(30);
        title.setTextColor(0xFF111114);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("bata【严禁外传】 · 充电 / 音乐 / 通知");
        sub.setTextSize(13);
        sub.setTextColor(0xFF6B6B75);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(13);
        statusView.setTextColor(0xFF33333A);
        statusView.setLineSpacing(dp(4), 1f);
        root.addView(statusView);

        root.addView(sectionLabel("权限"));
        addButton(root, "授予悬浮窗权限", v -> openOverlaySettings());
        addButton(root, "授予通知使用权（通知 / 计时器 / 音乐）", v ->
                openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        addButton(root, "增强模式：让灵动岛显示在状态栏上方（无障碍）", v ->
                openSettings("android.settings.ACCESSIBILITY_SETTINGS"));

        root.addView(sectionLabel("位置微调（如果岛没有压在状态栏上，拖动这里）"));
        TextView offsetLabel = new TextView(this);
        offsetLabel.setTextSize(12);
        offsetLabel.setTextColor(0xFF6B6B75);
        root.addView(offsetLabel);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(80);
        int saved = Math.round(prefs.getFloat("offset_dp", 0f));
        seek.setProgress(Math.max(0, Math.min(80, saved + 40)));
        offsetLabel.setText("垂直偏移：" + saved + " dp（负数=更靠上，0=压在状态栏正中）");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                int v = progress - 40;
                offsetLabel.setText("垂直偏移：" + v + " dp（负数=更靠上，0=压在状态栏正中）");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                int v = sb.getProgress() - 40;
                prefs.edit().putFloat("offset_dp", v).apply();
                // Only nudge a running service; starting a foreground service just to apply an
                // offset would leave an idle service behind.
                if (IslandService.isRunning()) {
                    IslandService.start(MainActivity.this, IslandService.ACTION_REFRESH);
                }
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        root.addView(sectionLabel("灵动岛尺寸（自定义）"));
        addPercentSlider(root, "整体缩放", "geom_scale", 100, 70, 130);
        addPercentSlider(root, "紧凑胶囊宽", "geom_compact_w", 100, 80, 130);
        addPercentSlider(root, "紧凑胶囊高", "geom_compact_h", 100, 80, 130);
        addPercentSlider(root, "展开卡宽", "geom_expanded_w", 100, 85, 115);
        addPercentSlider(root, "展开卡高", "geom_expanded_h", 100, 80, 130);
        addPercentSlider(root, "展开卡圆角", "geom_radius", 100, 70, 130);
        addPercentSlider(root, "常驻胶囊宽", "geom_idle_w", 100, 60, 200);
        addPercentSlider(root, "常驻胶囊高", "geom_idle_h", 100, 60, 160);
        Button resetGeom = new Button(this);
        resetGeom.setText("恢复默认尺寸");
        resetGeom.setAllCaps(false);
        resetGeom.setTextSize(14);
        resetGeom.setTextColor(0xFF33333A);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xFFF4F5F8);
        rbg.setCornerRadius(dp(12));
        rbg.setStroke(dp(1), 0xFFDADBE3);
        resetGeom.setBackground(rbg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rlp.bottomMargin = dp(4);
        resetGeom.setLayoutParams(rlp);
        resetGeom.setOnClickListener(v -> {
            prefs.edit()
                    .putInt("geom_scale", 100)
                    .putInt("geom_compact_w", 100)
                    .putInt("geom_compact_h", 100)
                    .putInt("geom_expanded_w", 100)
                    .putInt("geom_expanded_h", 100)
                    .putInt("geom_radius", 100)
                    .putInt("geom_idle_w", 100)
                    .putInt("geom_idle_h", 100)
                    .apply();
            toastText("已恢复默认尺寸，重新打开页面查看滑块");
            refreshGeometryService();
        });
        root.addView(resetGeom);

        root.addView(sectionLabel("灵动岛常驻"));
        android.widget.CheckBox alwaysOn = new android.widget.CheckBox(this);
        alwaysOn.setText("常驻显示：没有计时/音乐/通知时也保留小胶囊");
        alwaysOn.setTextSize(14);
        alwaysOn.setTextColor(0xFF1F1F24);
        alwaysOn.setChecked(prefs.getBoolean("always_on", false));
        alwaysOn.setPadding(0, dp(4), 0, dp(8));
        alwaysOn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("always_on", isChecked).apply();
            refreshGeometryService();
        });
        root.addView(alwaysOn);

        // v8.56: independent per-scenario switches.
        root.addView(sectionLabel("灵动岛显示开关（单独控制）"));
        addVisibilityToggle(root, "来消息时显示灵动岛", "show_notif", true);
        addVisibilityToggle(root, "充电时显示灵动岛", "show_charge", true);
        addVisibilityToggle(root, "调音量时显示灵动岛", "show_volume", true);
        addVisibilityToggle(root, "播放音乐时显示灵动岛", "show_music", true);
        addVisibilityToggle(root, "响铃/静音时显示灵动岛", "show_ringer", true);
        addVisibilityToggle(root, "解锁时显示解锁动画", "show_unlock", true);
        addVisibilityToggle(root, "锁屏时显示锁头胶囊", "show_lock", true);

        root.addView(sectionLabel("通知灵动岛应用"));
        addButton(root, "选择显示通知的应用（只显示勾选的）",
                v -> showNotificationAppPicker());
        addButton(root, "恢复显示全部应用通知", v -> {
            prefs.edit().remove("notif_apps").apply();
            refreshGeometryService();
            toastText("已恢复：所有应用的通知都会显示");
        });

        root.addView(sectionLabel("服务"));
        addButton(root, "开启灵动岛", v -> {
            if (!canShowIsland()) {
                toastText("请先授予悬浮窗权限，或开启「增强模式（无障碍）」");
                return;
            }
            prefs.edit().putBoolean("enabled", true).apply();
            IslandService.start(this, null);
            refresh();
        });
        addButton(root, "关闭灵动岛", v -> {
            prefs.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, IslandService.class));
            try { stopService(new Intent(this, LiquidIslandService.class)); } catch (Throwable ignored) {}
            refresh();
        });
        // v8.45: independent Liquid-Glass island module (separate Activity/library module).
        addButton(root, "使用液态玻璃灵动岛", v -> {
            Intent liquid = new Intent();
            liquid.setClassName(this, "com.lingdong.island.liquidglass.LiquidGlassActivity");
            try {
                startActivity(liquid);
            } catch (Throwable t) {
                toastText("液态玻璃模块未安装：" + t.getMessage());
            }
        });

        root.addView(sectionLabel("预览效果"));
        addButton(root, "充电动效", v -> {
            if (ensureService()) IslandService.start(this, IslandService.ACTION_DEMO_CHARGE);
        });
        addButton(root, "音乐播放", v -> {
            if (ensureService()) IslandService.start(this, IslandService.ACTION_DEMO_MUSIC);
        });
        addButton(root, "结束预览", v -> {
            if (IslandService.isRunning()) IslandService.start(this, IslandService.ACTION_DEMO_HIDE);
        });

        TextView tip = new TextView(this);
        tip.setText("提示：灵动岛会显示在屏幕顶部状态栏区域。任意 App 来通知时会弹出通知胶囊（应用图标 + 标题），"
                + "点击可展开详情与操作按钮，点岛外或上滑收起；可点击计时器/音乐胶囊展开控制卡片。"
                + "真实场景下，插上充电器会触发充电动效（显示真实电量）；系统时钟的秒表/计时器运行时会出现计时胶囊；"
                + "任意音乐 App 播放时会出现专辑封面 + 波形。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF777780);
        tip.setLineSpacing(dp(4), 1f);
        tip.setPadding(0, dp(22), 0, 0);
        root.addView(tip);

        return scroll;
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
        statusView.setText("悬浮窗权限：" + (overlay ? "已授予" : "未授予")
                + "\n通知使用权：" + (notif ? "已授予" : "未授予（计时器/音乐需要）")
                + "\n无障碍增强：" + (a11y ? "已开启（岛显示在状态栏上方）" : "未开启（可选）")
                + "\n灵动岛服务：" + (running ? "运行中" : "未运行"));
        statusView.setTextColor((overlay || a11y) && running ? 0xFF2E9E5B : 0xFF33333A);
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

