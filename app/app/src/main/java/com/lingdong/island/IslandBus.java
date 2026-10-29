package com.lingdong.island;

import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * In-process bridge between the NotificationListenerService and the IslandService.
 * Every entry point is called on the main thread (NotificationListenerService callbacks), so the
 * sink may touch views directly.
 */
public final class IslandBus {

    private static final String TAG = "IslandBus";

    private IslandBus() {}

    public interface NotificationSink {
        void onNotificationPosted(StatusBarNotification sbn);
        void onNotificationRemoved(StatusBarNotification sbn);
        void onListenerInvalidated();
    }

    private static volatile NotificationSink sink;

    public static synchronized void register(NotificationSink s) {
        sink = s;
        // Replay the latest known notifications so an already-running stopwatch/timer is not missed.
        // Replaying the same notification twice is idempotent for the sink (it just re-derives the
        // timer model), and a single bad notification must not abort the service start.
        for (StatusBarNotification sbn : IslandNotificationListener.knownNotifications()) {
            if (sbn == null) continue;
            try {
                s.onNotificationPosted(sbn);
            } catch (Exception e) {
                Log.w(TAG, "notification replay failed", e);
            }
        }
    }

    public static synchronized void unregister(NotificationSink s) { if (sink == s) sink = null; }

    static void post(StatusBarNotification sbn) {
        NotificationSink s = sink;
        if (s != null && sbn != null) s.onNotificationPosted(sbn);
    }

    static void removed(StatusBarNotification sbn) {
        NotificationSink s = sink;
        if (s != null && sbn != null) s.onNotificationRemoved(sbn);
    }

    static void invalidate() {
        NotificationSink s = sink;
        if (s != null) s.onListenerInvalidated();
    }
}
