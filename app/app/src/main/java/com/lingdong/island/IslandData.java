package com.lingdong.island;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.graphics.Bitmap;

/** Data models for the island states. Field values are supplied by the monitors. */
public final class IslandData {

    private IslandData() {}

    public static final class Charging {
        public boolean charging;
        public int level;                // real integer percentage (0 until the battery reports one)
        public float preciseLevel;       // real percentage with decimals when the ROM exposes it
        public boolean fast;             // "fast" charging speed label
        public boolean superFast;        // "super fast" charging speed label

        // percentText() is called from onDraw; only re-format when an input value changed.
        private String percentTextCache;
        private int percentLevelCache;
        private float percentPreciseCache;
        private boolean percentFastCache;
        private boolean percentSuperFastCache;
        private boolean percentCacheValid;

        public String label() {
            // v8.26: fast/super-fast labels are intentionally never shown; always use the normal one.
            return "正在充电";
        }

        public String percentText() {
            if (percentCacheValid
                    && percentLevelCache == level
                    && percentPreciseCache == preciseLevel
                    && percentFastCache == fast
                    && percentSuperFastCache == superFast) {
                return percentTextCache;
            }
            // Always the real battery reading. Fast charging may expose honest decimals through the
            // charge counter; when it only matches the integer level we keep the plain "<level>%"
            // instead of printing a fake ".00" (v3.3: real percentage, no fabricated 65.52%).
            // v8.26: fast states are hidden, so always use the plain integer percentage.
            String text = level + "%";
            percentTextCache = text;
            percentLevelCache = level;
            percentPreciseCache = preciseLevel;
            percentFastCache = fast;
            percentSuperFastCache = superFast;
            percentCacheValid = true;
            return text;
        }

        public int accent() {
            // v8.26: no gold/double-bolt fast state; normal green is always used.
            return IslandSpec.CHARGE_GREEN;
        }
    }

    public static final class Timer {
        public String title = "秒表";
        public String digits = "00:01";
        public boolean running = true;
        public boolean countDown = false;
        public long when;        // Notification.when: start time (stopwatch) or end time (countdown)
        /** v5.1: source app package of the Clock/timer notification (tap card body opens it). */
        public String packageName = "";

        // Reference dynamicSpot wires the Clock notification's actions (pause / resume) to the two
        // round buttons of the expanded card. Keep up to two action PendingIntents for that.
        public int actionCount;
        public final String[] actionTitles = new String[2];
        public final PendingIntent[] actionIntents = new PendingIntent[2];

        // tick() is called every 300 ms (service) / every frame (view); reuse the last string
        // while the millisecond value maps to the same output.
        private long tickMs = Long.MIN_VALUE;
        private boolean tickValid;

        /** Recompute the digits from the notification timing whenever possible. */
        public void tick(long now) {
            if (when <= 0) return;
            long ms = countDown ? (when - now) : (now - when);
            if (ms < 0) ms = 0;
            if (tickValid && tickMs == ms) return;
            tickMs = ms;
            tickValid = true;
            String formatted = format(ms);
            if (!formatted.equals(digits)) digits = formatted;
        }

        public static String format(long ms) {
            long totalSec = ms / 1000;
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
            return String.format(java.util.Locale.US, "%02d:%02d", m, s);
        }
    }

    public static final class Music {
        public String title = "Unknown track";
        public String artist = "";
        public Bitmap artwork;
        /** v7.0: source app's launcher icon, used as the album-cover fallback. */
        public Bitmap appIcon;
        public int accent = IslandSpec.MUSIC_GLOW_DEFAULT;
        public boolean playing = true;
        public long positionMs;
        public long positionAtUptime;
        public long durationMs;
        /** v5.1: source media app package (tap non-button area of the expanded card opens it). */
        public String packageName = "";

        public long position(long now) {
            if (!playing) return positionMs;
            return positionMs + (now - positionAtUptime);
        }
    }

    /**
     * A notification rendered by the island, modelled after dynamicSpot's notification record
     * (popup_first compact + popup_big expanded). The service owns {@link #icon}; the view only
     * draws it.
     */
    public static final class NotificationItem {
        public String key = "";
        public String packageName = "";
        public String appName = "";
        public String title = "";
        public String text = "";
        public String timeText = "";
        public Bitmap icon;
        public long postedAt;
        public PendingIntent contentIntent;
        public int actionCount;
        public final String[] actionTitles = new String[3];
        public final PendingIntent[] actionIntents = new PendingIntent[3];
        /** v8.6: index of the notification action that carries RemoteInput (direct reply). */
        public int replyActionIndex = -1;
        public RemoteInput[] replyInputs;
        /** v8.14: synthetic "回复" button for messaging apps that don't expose RemoteInput. */
        public int forceReplyActionIndex = -1;

        /** One-line text shown by the compact pill: title, falling back to the body. */
        public String compactLabel() {
            if (title != null && !title.isEmpty()) return title;
            if (text != null && !text.isEmpty()) return text;
            return appName == null ? "" : appName;
        }

        public void reset() {
            key = "";
            packageName = "";
            appName = "";
            title = "";
            text = "";
            timeText = "";
            icon = null;
            postedAt = 0L;
            contentIntent = null;
            actionCount = 0;
            replyActionIndex = -1;
            replyInputs = null;
            forceReplyActionIndex = -1;
            for (int i = 0; i < actionTitles.length; i++) {
                actionTitles[i] = null;
                actionIntents[i] = null;
            }
        }
    }
}
