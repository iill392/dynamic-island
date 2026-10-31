package com.lingdong.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!context.getSharedPreferences("island", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) {
            return;
        }
        // The island can also be hosted by the accessibility service, which does not need the
        // "display over other apps" permission, so accept either host.
        boolean overlay = Settings.canDrawOverlays(context);
        boolean a11y = false;
        if (!overlay) {
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_accessibility_services");
            a11y = !TextUtils.isEmpty(flat) && flat.contains(context.getPackageName());
        }
        if (overlay || a11y) {
            IslandService.start(context, null);
        }
    }
}
