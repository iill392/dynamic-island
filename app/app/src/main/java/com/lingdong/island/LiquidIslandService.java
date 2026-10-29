package com.lingdong.island;

import android.content.Context;
import android.content.Intent;

/**
 * v8.46: the functional Liquid-Glass island module.
 *
 * It reuses the complete notification/music/charging/unlock island behaviour, but switches the
 * card material to the translucent liquid-glass surface with a blurred backdrop window.
 */
public class LiquidIslandService extends IslandService {
    @Override protected boolean useLiquidGlass() {
        return true;
    }

    public static void start(Context context) {
        start(context, null);
    }

    public static void start(Context context, String action) {
        Intent i = new Intent(context, LiquidIslandService.class);
        if (action != null) i.setAction(action);
        try {
            context.startForegroundService(i);
        } catch (Throwable ignored) {
            context.startService(i);
        }
    }
}
