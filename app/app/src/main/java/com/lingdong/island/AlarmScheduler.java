package com.lingdong.island;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * v8.82: automatic keep-alive.
 *
 * Two independent system wake-ups are installed whenever the island is enabled:
 *   1. AlarmManager one-shot exact (or Doze-allowed inexact) alarm every 15 minutes;
 *   2. JobScheduler periodic persisted job every 15 minutes.
 *
 * Both are re-armed by their receivers/services and restart the foreground island service if the
 * LMK/OEM killer removed it. There is deliberately no user-facing toggle anymore.
 */
public final class AlarmScheduler {
    public static final String ACTION_KEEPALIVE = "com.lingdong.island.KEEPALIVE";
    static final int REQUEST_CODE = 1401;
    static final int JOB_ID = 1402;
    static final long INTERVAL_MS = 15L * 60L * 1000L;

    private AlarmScheduler() {}

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("island", Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    public static void schedule(Context context) {
        if (!isEnabled(context)) {
            cancel(context);
            return;
        }
        scheduleAlarm(context);
        scheduleJob(context);
    }

    private static void scheduleAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
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

    private static void scheduleJob(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            if (js.getPendingJob(JOB_ID) != null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, KeepAliveJobService.class))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {}
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try { am.cancel(pending(context)); } catch (Throwable ignored) {}
        }
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) {
            try { js.cancel(JOB_ID); } catch (Throwable ignored) {}
        }
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, KeepAliveReceiver.class)
                .setAction(ACTION_KEEPALIVE);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
