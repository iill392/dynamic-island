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
    private SharedPreferences prefs;
    private android.widget.Switch masterSwitch;
    private TextView permissionRowSub;
    private boolean updatingTopSwitches;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("island", MODE_PRIVATE);
        if (!prefs.getBoolean("onboarded", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
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
        scroll.setBackgroundColor(0xFFFFFFFF);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(34), dp(20), dp(30));

        TextView title = new TextView(this);
        title.setText("游龙灵动岛");
        title.setTextSize(28);
        title.setTextColor(0xFF111114);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD));
        title.setPadding(0, 0, 0, dp(14));
        root.addView(title);

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        root.addView(menu);

        LinearLayout masterRow = addMenuRow(menu, "灵动岛", "点击开启灵动岛", true, null);
        masterSwitch = (android.widget.Switch) masterRow.getChildAt(1);
        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingTopSwitches) return;
            setMasterEnabled(isChecked);
        });

        addMenuRow(menu, "通用", "显示设置和互动", false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_GENERAL));
        addMenuRow(menu, "外观", "尺寸、圆角和样式", false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_APPEARANCE));
        addMenuRow(menu, "位置", "更改大小和位置", false,
                () -> openCategory(SettingsCategoryActivity.CATEGORY_POSITION));

        LinearLayout permRow = addMenuRow(menu, "权限", "点击逐项授权", false,
                () -> startActivity(new Intent(this, PermissionActivity.class)));
        permissionRowSub = (TextView) ((LinearLayout) permRow.getChildAt(0)).getChildAt(1);

        TextView footer = new TextView(this);
        footer.setText("开启前请先在「权限」中逐项授权。");
        footer.setTextSize(12);
        footer.setTextColor(0xFF8A8A93);
        footer.setPadding(0, dp(24), 0, dp(4));
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

    /** v8.66: plain list row — no icon, matching the requested icon-free settings layout. */
    private LinearLayout addMenuRow(LinearLayout parent, String titleText, String subText,
                                    boolean withSwitch, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(20), dp(6), dp(20));
        row.setBackgroundColor(0xFFFFFFFF);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, tlp);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(22);
        title.setTextColor(0xFF1A1A1F);
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
        parent.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return row;
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

    private GradientDrawable roundedBg(int fill, int radiusDp, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }

    /** v8.62: required before the master switch may be turned on. */
    private boolean hasRequiredPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean accessibility = isAccessibilityAccessEnabled();
        boolean notif = isNotificationAccessEnabled();
        boolean runtime = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == PackageManager.PERMISSION_GRANTED;
        boolean battery = isIgnoringBatteryOptimizations();
        return overlay && accessibility && notif && runtime && battery;
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void stopIslandServices() {
        stopService(new Intent(this, IslandService.class));
    }

    private void startSelectedService() {
        IslandService.start(this, null);
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
        IslandService.start(this, IslandService.ACTION_REFRESH);
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
        if (masterSwitch != null) {
            updatingTopSwitches = true;
            masterSwitch.setChecked(prefs.getBoolean("enabled", false)
                    || IslandService.isRunning());
            updatingTopSwitches = false;
        }
        if (permissionRowSub != null) {
            int done = 0;
            if (overlay) done++;
            if (a11y) done++;
            if (notif) done++;
            if (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            == PackageManager.PERMISSION_GRANTED) done++;
            if (isIgnoringBatteryOptimizations()) done++;
            permissionRowSub.setText(done + "/5 已授权"
                    + (done >= 5 ? " · 已完成" : " · 点击逐项授权"));
            permissionRowSub.setTextColor(done >= 5 ? 0xFF2E9E5B : 0xFF7A7A84);
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

