package com.lingdong.island;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v8.63: sub-pages behind the dynamicSpot-style main menu.
 *
 * GENERAL   — always-on, per-scenario visibility, notification-app whitelist, service/preview.
 * APPEARANCE — all island size/radius sliders.
 * POSITION  — vertical offset.
 */
public class SettingsCategoryActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    public static final String CATEGORY_GENERAL = "general";
    public static final String CATEGORY_APPEARANCE = "appearance";
    public static final String CATEGORY_POSITION = "position";

    private SharedPreferences prefs;
    private String category = CATEGORY_GENERAL;
    private boolean previewStarted;
    private boolean previewServiceWasRunning;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("island", MODE_PRIVATE);
        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) category = CATEGORY_GENERAL;
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmScheduler.schedule(this);
        if (CATEGORY_APPEARANCE.equals(category)) startAppearancePreview();
    }

    @Override protected void onStop() {
        stopAppearancePreview();
        super.onStop();
    }

    /** v8.66: entering 外观 immediately shows a live island preview for size tuning. */
    private void startAppearancePreview() {
        if (previewStarted) return;
        if (!Settings.canDrawOverlays(this) && !isAccessibilityEnabled()) return;
        previewServiceWasRunning = IslandService.isRunning();
        IslandService.start(this, IslandService.ACTION_DEMO_APPEARANCE);
        previewStarted = true;
    }

    private void stopAppearancePreview() {
        if (!previewStarted) return;
        previewStarted = false;
        if (previewServiceWasRunning) {
            IslandService.start(this, IslandService.ACTION_DEMO_HIDE);
        } else {
            stopService(new Intent(this, IslandService.class));
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F8FA);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        bar.setPadding(dp(10), dp(18), dp(18), dp(18));
        page.addView(bar);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(34);
        back.setTextColor(0xFF1A1A1F);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), 0, dp(10), dp(2));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText(titleForCategory());
        title.setTextSize(23);
        title.setTextColor(0xFF1A1A1F);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View barDivider = new View(this);
        barDivider.setBackgroundColor(0xFFEDEEF2);
        page.addView(barDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(8), dp(20), dp(32));
        page.addView(root);

        if (CATEGORY_GENERAL.equals(category)) buildGeneral(root);
        else if (CATEGORY_APPEARANCE.equals(category)) buildAppearance(root);
        else buildPosition(root);

        scroll.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private String titleForCategory() {
        if (CATEGORY_APPEARANCE.equals(category)) return "外观";
        if (CATEGORY_POSITION.equals(category)) return "位置";
        return "通用";
    }

    // ------------------------------------------------------------------ general
    private void buildGeneral(LinearLayout root) {
        root.addView(sectionLabel("灵动岛常驻"));
        CheckBox always = new CheckBox(this);
        always.setText("常驻显示：没有通知/音乐/充电时也保留小胶囊");
        always.setTextSize(14);
        always.setTextColor(0xFF1F1F24);
        always.setChecked(prefs.getBoolean("always_on", false));
        always.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("always_on", checked).apply();
            refreshService();
        });
        root.addView(always);

        root.addView(sectionLabel("显示哪些灵动岛"));
        addToggle(root, "来消息时显示", "show_notif", true);
        addToggle(root, "充电时显示", "show_charge", true);
        addToggle(root, "调音量时显示", "show_volume", true);
        addToggle(root, "播放音乐时显示", "show_music", true);
        addToggle(root, "响铃/静音时显示", "show_ringer", true);
        addToggle(root, "解锁时显示解锁动画", "show_unlock", true);
        addToggle(root, "锁屏时显示锁头胶囊", "show_lock", true);

        root.addView(sectionLabel("通知应用"));
        addButton(root, "选择显示通知的应用", v -> showNotificationAppPicker());
        addButton(root, "恢复显示全部应用通知", v -> {
            prefs.edit().remove("notif_apps").apply();
            refreshService();
            toast("已恢复：所有应用的通知都会显示");
        });

        root.addView(sectionLabel("服务与预览"));
        addButton(root, "开启灵动岛", v -> {
            prefs.edit().putBoolean("enabled", true).apply();
            stopServices();
            startSelected();
        });
        addButton(root, "关闭灵动岛", v -> {
            prefs.edit().putBoolean("enabled", false).apply();
            stopServices();
        });
        addButton(root, "充电效果预览", v -> {
            ensureService();
            if (IslandService.isRunning()) IslandService.start(this, IslandService.ACTION_DEMO_CHARGE);
        });
        addButton(root, "音乐效果预览", v -> {
            ensureService();
            if (IslandService.isRunning()) IslandService.start(this, IslandService.ACTION_DEMO_MUSIC);
        });
        addButton(root, "结束预览", v -> {
            if (IslandService.isRunning()) IslandService.start(this, IslandService.ACTION_DEMO_HIDE);
        });

        root.addView(sectionLabel("后台与保活"));
        CheckBox hideNotice = new CheckBox(this);
        hideNotice.setText("后台隐藏：尽量隐藏常驻通知");
        hideNotice.setTextSize(14);
        hideNotice.setTextColor(0xFF1F1F24);
        hideNotice.setChecked(prefs.getBoolean("hide_background_notice", false));
        hideNotice.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("hide_background_notice", checked).apply();
            if (IslandService.isRunning()) {
                boolean was = prefs.getBoolean("enabled", false);
                stopServices();
                if (was) startSelected();
            }
        });
        root.addView(hideNotice);

        TextView hideHint = new TextView(this);
        hideHint.setText("Android 要求前台服务必须保留通知，这里只能尽量降低显示层级。");
        hideHint.setTextSize(11);
        hideHint.setTextColor(0xFF8A8A93);
        hideHint.setPadding(dp(4), 0, 0, dp(6));
        root.addView(hideHint);

        CheckBox alarm = new CheckBox(this);
        alarm.setText("闹钟保活：每 15 分钟唤醒并重启服务（防 LMK）");
        alarm.setTextSize(14);
        alarm.setTextColor(0xFF1F1F24);
        alarm.setChecked(prefs.getBoolean("alarm_keepalive", false));
        alarm.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("alarm_keepalive", checked).apply();
            if (checked) {
                requestExactAlarmPermission();
                AlarmScheduler.schedule(this);
            } else {
                AlarmScheduler.cancel(this);
            }
        });
        root.addView(alarm);
    }

    // --------------------------------------------------------------- appearance
    private void buildAppearance(LinearLayout root) {
        root.addView(sectionLabel("灵动岛尺寸"));
        addPercentSlider(root, "整体缩放", "geom_scale", 100, 70, 130);
        addPercentSlider(root, "紧凑胶囊宽", "geom_compact_w", 100, 80, 130);
        addPercentSlider(root, "紧凑胶囊高", "geom_compact_h", 100, 80, 130);
        addPercentSlider(root, "展开卡宽", "geom_expanded_w", 100, 85, 115);
        addPercentSlider(root, "展开卡高", "geom_expanded_h", 100, 80, 130);
        addPercentSlider(root, "展开卡圆角", "geom_radius", 100, 70, 130);
        addPercentSlider(root, "常驻胶囊宽", "geom_idle_w", 100, 60, 200);
        addPercentSlider(root, "常驻胶囊高", "geom_idle_h", 100, 60, 160);

        addButton(root, "恢复默认尺寸", v -> {
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
            toast("已恢复默认尺寸，重新打开页面查看滑块");
            refreshService();
        });
    }

    // ----------------------------------------------------------------- position
    private void buildPosition(LinearLayout root) {
        root.addView(sectionLabel("横屏位置模式"));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        RadioButton centered = new RadioButton(this);
        centered.setText("居中（默认）· 灵动岛始终在屏幕顶部中间");
        centered.setTextSize(14);
        centered.setTextColor(0xFF1F1F24);
        centered.setId(101);
        group.addView(centered);
        RadioButton camera = new RadioButton(this);
        camera.setText("跟随摄像头 · 横屏时停在摄像头下方");
        camera.setTextSize(14);
        camera.setTextColor(0xFF1F1F24);
        camera.setId(102);
        group.addView(camera);
        boolean follow = prefs.getBoolean("landscape_camera_follow", false);
        group.check(follow ? 102 : 101);
        group.setOnCheckedChangeListener((g, checkedId) -> {
            prefs.edit().putBoolean("landscape_camera_follow", checkedId == 102).apply();
            refreshService();
        });
        root.addView(group);

        TextView hint = new TextView(this);
        hint.setText("「跟随摄像头」只在横屏且手机有刘海/挖孔时生效；竖屏仍保持居中。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF7A7A84);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(2), 0, dp(8));
        root.addView(hint);

        root.addView(sectionLabel("垂直位置"));
        final TextView label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(0xFF33333A);
        int saved = Math.round(prefs.getFloat("offset_dp", 0f));
        label.setText("垂直偏移：" + saved + " dp（负数=更靠上，0=压在状态栏正中）");
        root.addView(label);

        SeekBar seek = new SeekBar(this);
        seek.setMax(80);
        seek.setProgress(Math.max(0, Math.min(80, saved + 40)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = progress - 40;
                label.setText("垂直偏移：" + v + " dp（负数=更靠上，0=压在状态栏正中）");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                int v = sb.getProgress() - 40;
                prefs.edit().putFloat("offset_dp", v).apply();
                refreshService();
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        addButton(root, "恢复默认位置", v -> {
            prefs.edit().putFloat("offset_dp", 0f).apply();
            toast("已恢复默认位置");
            refreshService();
            recreate();
        });
    }

    // -------------------------------------------------------------------- utils
    private void refreshService() {
        if (!IslandService.isRunning()) return;
        IslandService.start(this, IslandService.ACTION_REFRESH);
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && am.canScheduleExactAlarms()) return;
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    private boolean ensureService() {
        if (!Settings.canDrawOverlays(this) && !isAccessibilityEnabled()) {
            toast("请先在权限页授予悬浮窗和无障碍权限");
            startActivity(new Intent(this, PermissionActivity.class));
            return false;
        }
        if (!IslandService.isRunning()) {
            prefs.edit().putBoolean("enabled", true).apply();
            startSelected();
        }
        return true;
    }

    private boolean isAccessibilityEnabled() {
        if (IslandAccessibilityService.isConnected()) return true;
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_accessibility_services");
        return flat != null && flat.contains(getPackageName());
    }

    private void stopServices() {
        stopService(new Intent(this, IslandService.class));
    }

    private void startSelected() {
        IslandService.start(this, null);
    }

    private void addToggle(LinearLayout root, String title, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(title);
        cb.setTextSize(14);
        cb.setTextColor(0xFF1F1F24);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            refreshService();
        });
        root.addView(cb);
    }

    private void addPercentSlider(LinearLayout root, String title, String key,
                                  int def, int min, int max) {
        TextView label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(0xFF33333A);
        label.setPadding(0, dp(8), 0, 0);
        root.addView(label);

        SeekBar bar = new SeekBar(this);
        int saved = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        bar.setMax(max - min);
        bar.setProgress(saved - min);
        label.setText(title + "：" + saved + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                label.setText(title + "：" + (min + progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt(key, min + sb.getProgress()).apply();
                refreshService();
            }
        });
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
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

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF6B6B75);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(22), 0, dp(8));
        return tv;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Notification-app whitelist picker (same behaviour as the main settings page). */
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
                for (int i = 0; i < n; i++) checked[i] = all || saved.contains(packages[i]);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(SettingsCategoryActivity.this)
                            .setTitle("选择显示通知的应用")
                            .setMultiChoiceItems(labels, checked,
                                    (d, which, isChecked) -> checked[which] = isChecked)
                            .setPositiveButton("保存", (d, which) -> {
                                Set<String> selected = new HashSet<>();
                                for (int i = 0; i < checked.length; i++) {
                                    if (checked[i]) selected.add(packages[i]);
                                }
                                prefs.edit().putStringSet("notif_apps", selected).apply();
                                refreshService();
                                toast("已保存：" + selected.size() + " 个应用会显示通知");
                            })
                            .setNeutralButton("全部不选", (d, which) -> {
                                prefs.edit().putStringSet("notif_apps", new HashSet<>()).apply();
                                refreshService();
                                toast("已清空，所有应用通知都不显示");
                            })
                            .setNegativeButton("取消", null)
                            .create().show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> toast("读取应用列表失败：" + t.getMessage()));
            }
        }, "island-app-picker").start();
    }
}
