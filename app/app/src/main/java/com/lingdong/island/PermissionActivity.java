package com.lingdong.island;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v8.62: one-by-one permission authorization page.
 *
 * The master island switch refuses to turn on until the three required permissions are granted:
 * overlay/accessibility host, notification listener, and the Android 13 notification runtime
 * permission. Optional enhancements are listed separately.
 */
public class PermissionActivity extends Activity {
    private static final int REQ_NOTIF = 20;
    private LinearLayout content;
    private TextView summary;
    private Button doneButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefsTheme();
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        populate();
    }

    private void prefsTheme() {
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(30), pad, dp(36));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("权限授权");
        title.setTextSize(28);
        title.setTextColor(0xFF111114);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("请逐项授权。全部必需权限完成后，返回软件再点击「开启灵动岛」。");
        sub.setTextSize(13);
        sub.setTextColor(0xFF6B6B75);
        sub.setLineSpacing(dp(3), 1f);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        summary = new TextView(this);
        summary.setTextSize(14);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        summary.setPadding(0, dp(2), 0, dp(12));
        root.addView(summary);

        Button a11yShortcut = new Button(this);
        a11yShortcut.setText("打开无障碍设置（必需）");
        a11yShortcut.setAllCaps(false);
        a11yShortcut.setTextSize(15);
        a11yShortcut.setTextColor(0xFF2563EB);
        GradientDrawable a11yBg = new GradientDrawable();
        a11yBg.setColor(0xFFEFF4FF);
        a11yBg.setCornerRadius(dp(14));
        a11yBg.setStroke(dp(1), 0xFFCFE0FF);
        a11yShortcut.setBackground(a11yBg);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        alp.bottomMargin = dp(12);
        a11yShortcut.setLayoutParams(alp);
        a11yShortcut.setOnClickListener(v -> openAccessibility());
        root.addView(a11yShortcut);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        Button done = new Button(this);
        doneButton = done;
        done.setText("完成，返回软件");
        done.setAllCaps(false);
        done.setTextSize(15);
        done.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2563EB);
        bg.setCornerRadius(dp(14));
        done.setBackground(bg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        dlp.topMargin = dp(18);
        done.setLayoutParams(dlp);
        done.setOnClickListener(v -> {
            if (!hasAllRequired()) {
                android.widget.Toast.makeText(this, "请先完成所有必需权限",
                        android.widget.Toast.LENGTH_SHORT).show();
                populate();
                return;
            }
            finish();
        });
        root.addView(done);

        setContentView(scroll);
    }

    private void populate() {
        if (content == null) return;
        content.removeAllViews();

        boolean overlay = Settings.canDrawOverlays(this);
        boolean a11y = isAccessibilityAccessEnabled();
        boolean notificationAccess = isNotificationAccessEnabled();
        boolean runtimeNotif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean battery = isIgnoringBatteryOptimizations();

        int requiredDone = (overlay ? 1 : 0) + (a11y ? 1 : 0)
                + (notificationAccess ? 1 : 0) + (runtimeNotif ? 1 : 0)
                + (battery ? 1 : 0);
        summary.setText("必需权限：" + requiredDone + "/5"
                + (requiredDone >= 5 ? " · 已全部授权" : " · 还差 " + (5 - requiredDone) + " 项"));
        summary.setTextColor(requiredDone >= 5 ? 0xFF2E9E5B : 0xFFB45309);
        if (doneButton != null) {
            doneButton.setEnabled(requiredDone >= 5);
            doneButton.setAlpha(requiredDone >= 5 ? 1f : 0.45f);
            doneButton.setText(requiredDone >= 5 ? "完成，返回软件" : "请先完成必需权限");
        }

        addRow("悬浮窗权限（必需）",
                "让灵动岛显示在其他应用和状态栏上方",
                overlay, true, this::openOverlaySettings);
        addRow("通知使用权（必需）",
                "读取通知、计时器与音乐播放状态",
                notificationAccess, true, this::openNotificationAccess);
        addRow("通知权限（必需）",
                "Android 13+ 允许灵动岛服务发送前台通知",
                runtimeNotif, true, this::requestNotificationPermission);
        addRow("无障碍增强（必需）",
                "让灵动岛显示在状态栏上方",
                a11y, true, this::openAccessibility);
        addRow("忽略电池优化（必需）",
                "减少后台被杀，提升常驻稳定性",
                battery, true, this::openBatteryOptimization);
    }

    private void addRow(String title, String desc, boolean granted, boolean required,
                        Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(0xFFF7F7FA, 16, 0xFFE1E2E8));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xFF1F1F24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(0xFF6B6B75);
        d.setPadding(0, dp(3), 0, dp(8));
        card.addView(d);

        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setText(granted ? "已授权" : "去授权");
        b.setEnabled(!granted);
        b.setTextColor(granted ? 0xFF2E9E5B : 0xFF2563EB);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(granted ? 0xFFEAF7EF : 0xFFEFF4FF);
        bg.setCornerRadius(dp(12));
        if (!granted) bg.setStroke(dp(1), 0xFFCFE0FF);
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        b.setOnClickListener(v -> action.run());
        card.addView(b);

        if (!granted && required) {
            TextView hint = new TextView(this);
            hint.setText("此项为开启灵动岛的必需权限");
            hint.setTextSize(11);
            hint.setTextColor(0xFFB45309);
            hint.setPadding(0, dp(6), 0, 0);
            card.addView(hint);
        }
        content.addView(card);
    }

    private GradientDrawable roundedBg(int fill, int radiusDp, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }

    private boolean isNotificationAccessEnabled() {
        if (Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.isNotificationListenerAccessGranted(
                    new ComponentName(this, IslandNotificationListener.class));
        }
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return !TextUtils.isEmpty(flat) && flat.contains(getPackageName());
    }

    private boolean isAccessibilityAccessEnabled() {
        if (IslandAccessibilityService.isConnected()) return true;
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_accessibility_services");
        return !TextUtils.isEmpty(flat) && flat.contains(getPackageName());
    }

    private boolean hasAllRequired() {
        return Settings.canDrawOverlays(this)
                && isAccessibilityAccessEnabled()
                && isNotificationAccessEnabled()
                && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED)
                && isIgnoringBatteryOptimizations();
    }

    private boolean isIgnoringBatteryOptimizations() {        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        populate();
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        }
    }

    private void openNotificationAccess() {
        openSettings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
    }

    private void openAccessibility() {
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    private void openBatteryOptimization() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                TextView tv = new TextView(this);
                tv.setText("无法打开系统设置，请手动到系统设置中授权。");
                android.widget.Toast.makeText(this, tv.getText(),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
