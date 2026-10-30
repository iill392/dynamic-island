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
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v8.66: first-run guide.
 *
 * Step 1 chooses the language, step 2 walks through the required permissions, then the user enters
 * the main page. No icons / blue header — plain list pages.
 */
public class OnboardingActivity extends Activity {
    private static final int REQ_NOTIF = 30;
    private static final int STEP_LANGUAGE = 0;
    private static final int STEP_PERMISSIONS = 1;

    private android.content.SharedPreferences prefs;
    private int step = STEP_LANGUAGE;
    private String language = "zh";
    private LinearLayout content;
    private TextView permissionSummary;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("island", MODE_PRIVATE);
        language = prefs.getString("app_language", "zh");
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildShell();
        showStep();
    }

    @Override protected void onResume() {
        super.onResume();
        if (step == STEP_PERMISSIONS) showPermissions();
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(34));

        TextView title = new TextView(this);
        title.setText("游龙灵动岛");
        title.setTextSize(28);
        title.setTextColor(0xFF111114);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("bata【严禁外传】");
        sub.setTextSize(13);
        sub.setTextColor(0xFF8A8A93);
        sub.setPadding(0, dp(6), 0, dp(24));
        root.addView(sub);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void showStep() {
        if (step == STEP_LANGUAGE) showLanguage();
        else showPermissions();
    }

    private void showLanguage() {
        content.removeAllViews();
        TextView title = new TextView(this);
        title.setText("选择语言 / Choose language");
        title.setTextSize(22);
        title.setTextColor(0xFF1A1A1F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView hint = new TextView(this);
        hint.setText("选择后进入下一步。");
        hint.setTextSize(13);
        hint.setTextColor(0xFF7A7A84);
        hint.setPadding(0, dp(6), 0, dp(14));
        content.addView(hint);

        RadioGroup group = new RadioGroup(this);
        RadioButton zh = new RadioButton(this);
        zh.setId(201);
        zh.setText("简体中文");
        zh.setTextSize(16);
        zh.setTextColor(0xFF1F1F24);
        group.addView(zh);
        RadioButton en = new RadioButton(this);
        en.setId(202);
        en.setText("English");
        en.setTextSize(16);
        en.setTextColor(0xFF1F1F24);
        group.addView(en);
        group.check("en".equals(language) ? 202 : 201);
        group.setOnCheckedChangeListener((g, id) -> language = id == 202 ? "en" : "zh");
        content.addView(group);

        Button next = primaryButton("下一步 / Next", v -> {
            prefs.edit().putString("app_language", language).apply();
            step = STEP_PERMISSIONS;
            showStep();
        });
        content.addView(next);
    }

    private void showPermissions() {
        if (content == null) return;
        content.removeAllViews();
        boolean en = "en".equals(language);

        TextView title = new TextView(this);
        title.setText(en ? "Permissions" : "配置权限");
        title.setTextSize(22);
        title.setTextColor(0xFF1A1A1F);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        permissionSummary = new TextView(this);
        permissionSummary.setTextSize(13);
        permissionSummary.setPadding(0, dp(6), 0, dp(12));
        content.addView(permissionSummary);

        boolean overlay = Settings.canDrawOverlays(this);
        boolean a11y = isAccessibilityAccessEnabled();
        boolean notif = isNotificationAccessEnabled();
        boolean runtime = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
        int done = (overlay || a11y ? 1 : 0) + (notif ? 1 : 0) + (runtime ? 1 : 0);
        permissionSummary.setText(en
                ? "Required: " + done + "/3" + (done >= 3 ? " · complete" : " · grant all to continue")
                : "必需权限：" + done + "/3" + (done >= 3 ? " · 已完成" : " · 全部授权后才能继续"));
        permissionSummary.setTextColor(done >= 3 ? 0xFF2E9E5B : 0xFFB45309);

        addPermissionRow(en ? "Display over other apps" : "悬浮窗权限",
                en ? "Draw the island above other apps" : "让灵动岛显示在其他应用上方",
                overlay, this::openOverlaySettings);
        addPermissionRow(en ? "Notification access" : "通知使用权",
                en ? "Read notifications, timer and music state" : "读取通知、计时器与音乐状态",
                notif, this::openNotificationAccess);
        addPermissionRow(en ? "Notification permission" : "通知权限",
                en ? "Android 13+ foreground-service notification" : "Android 13+ 允许前台服务通知",
                runtime, this::requestNotifications);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(20);
        nav.setLayoutParams(nlp);

        Button back = secondaryButton(en ? "Back" : "上一步", v -> {
            step = STEP_LANGUAGE;
            showStep();
        });
        nav.addView(back, new LinearLayout.LayoutParams(0, dp(50), 1f));

        Button finish = primaryButton(en ? "Finish" : "完成并进入", v -> {
            boolean okOverlay = Settings.canDrawOverlays(OnboardingActivity.this)
                    || isAccessibilityAccessEnabled();
            boolean okNotif = isNotificationAccessEnabled();
            boolean okRuntime = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!okOverlay || !okNotif || !okRuntime) {
                android.widget.Toast.makeText(OnboardingActivity.this,
                        en ? "Grant all required permissions first"
                                : "请先完成所有必需权限",
                        android.widget.Toast.LENGTH_SHORT).show();
                showPermissions();
                return;
            }
            prefs.edit()
                    .putBoolean("onboarded", true)
                    .putString("app_language", language)
                    .apply();
            Intent i = new Intent(OnboardingActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
        finish.setEnabled(done >= 3);
        finish.setText(en ? (done >= 3 ? "Finish" : "Grant all permissions")
                : (done >= 3 ? "完成并进入" : "请先完成权限"));
        finish.setAlpha(done >= 3 ? 1f : 0.45f);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        flp.leftMargin = dp(10);
        nav.addView(finish, flp);
        content.addView(nav);
    }

    private void addPermissionRow(String title, String desc, boolean granted, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedBg(0xFFF7F8FA, 14));
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
        d.setTextColor(0xFF7A7A84);
        d.setPadding(0, dp(3), 0, dp(8));
        card.addView(d);

        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(14);
        boolean en = "en".equals(language);
        b.setText(granted ? (en ? "Granted" : "已授权") : (en ? "Grant" : "去授权"));
        b.setEnabled(!granted);
        b.setTextColor(granted ? 0xFF2E9E5B : 0xFF2563EB);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(granted ? 0xFFEAF7EF : 0xFFEFF4FF);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        b.setOnClickListener(v -> action.run());
        card.addView(b);
        content.addView(card);
    }

    private Button primaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2563EB);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button secondaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFF1A1A1F);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F3F6);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable roundedBg(int fill, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radiusDp));
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

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        showPermissions();
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

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                android.widget.Toast.makeText(this, "无法打开系统设置，请手动授权",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
