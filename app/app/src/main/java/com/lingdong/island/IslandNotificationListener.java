package com.lingdong.island;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Receives notifications. Used for (1) system clock timer / stopwatch detection and
 * (2) as the component that grants MediaSessionManager access to active sessions.
 */
public class IslandNotificationListener extends NotificationListenerService {

    private static final String TAG = "IslandNotifyListener";
    /** R-10: first rebind attempt immediately, one more after 1 s if the access is still granted. */
    private static final long REBIND_RETRY_DELAY_MS = 1000L;
    private static final int REBIND_MAX_ATTEMPTS = 2;
    /** Bounded cache: the replay in IslandBus.register() must not grow without limit. */
    private static final int CACHE_MAX = 64;
    private static final List<StatusBarNotification> CACHE =
            Collections.synchronizedList(new ArrayList<>());

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int rebindAttempts;
    private long lastTimerAutoStartMs;
    /** v8.10: lets the island check whether a notification is genuinely still posted. */
    private static volatile IslandNotificationListener sInstance;

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override public void onDestroy() {
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    static boolean isNotificationActive(String key) {
        IslandNotificationListener inst = sInstance;
        if (inst == null || key == null) return false;
        try {
            StatusBarNotification[] active = inst.getActiveNotifications();
            if (active == null) return false;
            for (StatusBarNotification n : active) {
                if (n != null && TextUtils.equals(key, n.getKey())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static List<StatusBarNotification> knownNotifications() {
        synchronized (CACHE) {
            return new ArrayList<>(CACHE);
        }
    }

    private static boolean sameKey(StatusBarNotification a, StatusBarNotification b) {
        if (a == null || b == null) return false;
        String keyA = a.getKey();
        String keyB = b.getKey();
        if (keyA == null || keyB == null) return false;
        return TextUtils.equals(keyA, keyB);
    }

    private static void remember(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) return;
        synchronized (CACHE) {
            CACHE.removeIf(n -> sameKey(n, sbn));
            CACHE.add(sbn);
            while (CACHE.size() > CACHE_MAX) CACHE.remove(0);
        }
    }

    private static void forget(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) return;
        synchronized (CACHE) {
            CACHE.removeIf(n -> sameKey(n, sbn));
        }
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        rebindAttempts = 0;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    remember(sbn);
                    IslandBus.post(sbn);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getActiveNotifications failed", e);
        }
        // F-06: this binding is what grants MediaSessionManager access. If IslandService started
        // before the listener connected (BOOT_COMPLETED race) its media setup failed -> retry now.
        IslandService.onNotificationListenerConnected();
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // S9: while disconnected we cannot observe removals, so replaying the old cache after the
        // rebind would resurrect a timer that the user already dismissed. Start from a clean slate;
        // onListenerConnected() re-reads the notifications that still exist.
        synchronized (CACHE) {
            CACHE.clear();
        }
        IslandBus.invalidate();
        rebindAttempts = 0;
        requestRebindIfAllowed();
    }

    /**
     * R-10: only ask the system to rebind while the notification access is still granted (a user
     * revoking the permission must not be fought with endless rebind attempts). One retry after 1 s.
     */
    private void requestRebindIfAllowed() {
        if (rebindAttempts >= REBIND_MAX_ATTEMPTS) return;
        rebindAttempts++;
        long delay = rebindAttempts == 1 ? 0L : REBIND_RETRY_DELAY_MS;
        mainHandler.postDelayed(() -> {
            if (!isAccessGranted()) return;
            try {
                requestRebind(componentName());
            } catch (Exception e) {
                Log.w(TAG, "requestRebind failed", e);
            }
        }, delay);
    }

    private boolean isAccessGranted() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.isNotificationListenerAccessGranted(componentName());
        } catch (Throwable t) {
            // Cannot determine -> keep the old (retrying) behaviour.
            return true;
        }
    }

    private ComponentName componentName() {
        return new ComponentName(this, IslandNotificationListener.class);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        remember(sbn);
        // v8.3: timer auto-start removed together with the timer island.
        IslandBus.post(sbn);
    }

    private void maybeStartIslandForTimer(StatusBarNotification sbn) {
        if (sbn == null || IslandService.isRunning()) return;
        if (sbn.getNotification() == null) return;
        if (!getSharedPreferences("island", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) return;
        if (!looksLikeClockTimer(sbn)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTimerAutoStartMs < 5000L) return;
        lastTimerAutoStartMs = now;
        IslandService.start(this, null);
    }

    private boolean looksLikeClockTimer(StatusBarNotification sbn) {
        android.app.Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        String pkg = sbn.getPackageName();
        if (pkg == null) return false;
        String title = String.valueOf(n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE));
        String text = String.valueOf(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
        String sub = String.valueOf(n.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT));
        String info = String.valueOf(n.extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT));
        String all = (title + " " + text + " " + sub + " " + info).toLowerCase(java.util.Locale.US);
        boolean alarm = all.contains("闹钟") || all.contains("alarm")
                || android.app.Notification.CATEGORY_ALARM.equals(n.category);
        if (alarm) return false;
        String selected = getSharedPreferences("island", Context.MODE_PRIVATE)
                .getString("timer_app_pkg", "");
        if (!selected.isEmpty() && selected.equals(pkg)) return true;
        boolean keyword = all.contains("秒表") || all.contains("计时") || all.contains("倒计时")
                || all.contains("stopwatch") || all.contains("timer");
        if (keyword) return true;
        // Extras can be stripped for third-party listeners on some ROMs; use the timer action
        // labels (计次/暂停/继续) from the Clock notification as a second signal.
        String p = pkg.toLowerCase(java.util.Locale.US);
        boolean clockPkg = p.contains("clock") || p.contains("alarm") || p.contains("timer");
        if (clockPkg && n.actions != null) {
            for (android.app.Notification.Action a : n.actions) {
                if (a == null || a.title == null) continue;
                String at = a.title.toString().toLowerCase(java.util.Locale.US);
                if (at.contains("计次") || at.contains("暂停") || at.contains("继续")
                        || at.contains("重置") || at.contains("lap") || at.contains("pause")
                        || at.contains("resume") || at.contains("start") || at.contains("开始")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        forget(sbn);
        IslandBus.removed(sbn);
    }
}
