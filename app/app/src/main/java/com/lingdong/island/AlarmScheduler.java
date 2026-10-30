package com.lingdong.island;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * v8.67: alarm-based keep-alive.
 *
 * A one-shot exact (or Doze-allowed inexact) alarm wakes the app every 15 minutes and restarts the
 * foreground service if the LMK/system killed it. The alarm is re-armed by KeepAliveReceiver.
 */
public final class AlarmScheduler {
    public static final String ACTION_KEEPALIVE = "com.lingdong.island.KEEPALIVE";
    static final int REQUEST_CODE = 1401;
    static final long INTERVAL_MS = 15L * 60L * 1000L;

    private AlarmScheduler() {}

    public static boolean isEnabled(Context context) {
        android.content.SharedPreferences p =
                context.getSharedPreferences("island", Context.MODE_PRIVATE);
        return p.getBoolean("enabled", false) && p.getBoolean("alarm_keepalive", false);
    }

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!isEnabled(context)) {
            cancel(context);
            return;
        }
        PendingIntent pi = pending(context);
        long trigger = System.currentTimeMillis() + INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (Throwable t) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } catch (Throwable ignored) {}
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(pending(context)); } catch (Throwable ignored) {}
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, KeepAliveReceiver.class)
                .setAction(ACTION_KEEPALIVE);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
