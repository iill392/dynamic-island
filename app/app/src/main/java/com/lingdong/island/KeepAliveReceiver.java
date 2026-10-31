package com.lingdong.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * v8.67: wakes the app on the keep-alive alarm and restarts the island service if it was killed.
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmScheduler.ACTION_KEEPALIVE.equals(intent.getAction())) return;
        SharedPreferences p = context.getSharedPreferences("island", Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", false)) {
            AlarmScheduler.cancel(context);
            return;
        }
        if (!IslandService.isRunning()) {
            Intent service = new Intent(context, IslandService.class);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } catch (Throwable ignored) {}
        }
        AlarmScheduler.schedule(context);
    }
}
