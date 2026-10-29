package com.lingdong.island;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

/**
 * IslandSpec holds every geometric/visual constant of the island.
 *
 * All sizes are expressed as fractions of the screen width (W) or of a state's card height,
 * calibrated frame-by-frame against the three reference recordings:
 *   - charging  : 1920x342  @30fps
 *   - timer     : 3256x1080 @30fps
 *   - music     : 2118x1080 @30fps
 * The recordings are full-screen-width top-band crops of the same phone, so ratios of the frame
 * width are directly usable. This makes the island reproduce the reference on any screen.
 */
public final class IslandSpec {

    private IslandSpec() {}

    // ---------------------------------------------------------------- runtime
    /** Reference width (px): the SHORT side of the display, so the island keeps a constant physical
     *  size in portrait and landscape (using widthPixels directly made it huge when a game rotated
     *  the screen and widthPixels became e.g. 2376 instead of 1080). */
    public static float W;
    /** Long side of the display in px (kept for reference; layout uses {@link #W}). */
    public static float H;
    /** Current display height in px (for capping overlay windows to the actual screen). */
    public static float SCREEN_H;
    public static float DENSITY;    // display density
    public static float TOP;        // top margin of the card (px)
    public static float STATUS_BAR_H; // resolved status-bar height (px)
    /** Manual vertical nudge in px (used by the position slider in the app). */
    public static float USER_OFFSET_PX;
    public static float HC;         // compact card height (px)

    // ------------------------------------------------ v5.7 user size customisation
    /** All multipliers are 1.0 = reference. Saved as integer percent in SharedPreferences "island". */
    public static float GEOM_SCALE = 1f;        // overall
    public static float COMPACT_W_MUL = 1f;     // compact capsule width
    public static float COMPACT_H_MUL = 1f;     // compact capsule height
    public static float EXPANDED_W_MUL = 1f;    // expanded card width
    public static float EXPANDED_H_MUL = 1f;    // expanded card height
    public static float RADIUS_MUL = 1f;        // expanded corner radius
    public static float IDLE_W_MUL = 1f;        // always-on idle capsule width (independent)
    public static float IDLE_H_MUL = 1f;        // always-on idle capsule height (independent)

    public static void loadUserGeometry(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("island", Context.MODE_PRIVATE);
        GEOM_SCALE = pct(p, "geom_scale", 100, 60, 150);
        COMPACT_W_MUL = pct(p, "geom_compact_w", 100, 70, 140);
        COMPACT_H_MUL = pct(p, "geom_compact_h", 100, 70, 140);
        EXPANDED_W_MUL = pct(p, "geom_expanded_w", 100, 80, 120);
        EXPANDED_H_MUL = pct(p, "geom_expanded_h", 100, 70, 140);
        RADIUS_MUL = pct(p, "geom_radius", 100, 60, 140);
        IDLE_W_MUL = pct(p, "geom_idle_w", 100, 60, 200);
        IDLE_H_MUL = pct(p, "geom_idle_h", 100, 60, 160);
    }

    private static float pct(SharedPreferences p, String key, int def, int min, int max) {
        int v = p.getInt(key, def);
        if (v < min) v = min;
        if (v > max) v = max;
        return v / 100f;
    }

    /** "reference dp": 1 ref-dp = compact card height / 44.2 (the calibrated reference size). */
    public static float dp(float refDp) {
        return refDp * HC / 44.2f;
    }

    public static void init(Context ctx) {
        loadUserGeometry(ctx);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        // Geometry is calibrated against the 1080-px-wide portrait reference videos. Always use the
        // short side so rotating into landscape (games) does not double every island dimension.
        int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        W = shortSide;
        H = longSide;
        SCREEN_H = dm.heightPixels;
        DENSITY = dm.density;
        HC = 0.0892f * W * GEOM_SCALE * COMPACT_H_MUL;
        TOP = 0.0099f * W;
        STATUS_BAR_H = resolveStatusBarHeight(ctx);
    }

    /** Real status-bar height in px (used to centre the island on the status bar). */
    public static float resolveStatusBarHeight(Context ctx) {
        try {
            android.content.res.Resources res = ctx.getResources();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int px = res.getDimensionPixelSize(id);
                if (px > 0) return px;
            }
        } catch (Throwable ignored) {}
        return 24f * DENSITY;
    }

    /**
     * The island's vertical centre follows the status bar's centre, exactly like the reference
     * recordings (the pill is centred on the status-bar clock row).
     */
    public static float cardTop() {
        float sbh = STATUS_BAR_H > 0 ? STATUS_BAR_H : 24f * DENSITY;
        float top = sbh * 0.5f - HC * 0.5f + USER_OFFSET_PX;
        float maxTop = Math.max(0f, sbh + HC * 0.25f);    // or nudging down a little
        // Never let the card start above the window: on short status bars (HC > sbh) the
        // status-bar-centred top would be negative and the window/top of the screen would
        // clip the card. Keep "centre on the status bar" as the preferred position, but
        // clamp at 0 so the card is always fully visible.
        return Math.max(0f, Math.min(top, maxTop));
    }

    /** Extra window room on the sides for the spring overshoot / outer glow. */
    public static float windowPadH() { return W * 0.030f; }

    /** Extra window room below the card. */
    public static float windowPadB() { return W * 0.020f; }

    // --------------------------------------------------------------- geometry
    public static float compactW() {
        return Math.min(0.93f * W, 0.3675f * W * GEOM_SCALE * COMPACT_W_MUL);
    }
    public static float compactH() { return HC; }
    public static float minPillW() {
        return Math.min(compactW(), 0.244f * W * GEOM_SCALE * COMPACT_W_MUL);
    }

    /**
     * v5.2: the reference video's 0.980 W target leaves only ~20 px of horizontal headroom on a
     * 1080-wide portrait screen, so a +7% spring overshoot hit the screen edge and was clipped flat
     * (it looked like no bounce at all). Target 0.955 W leaves room for the overshoot to be visible
     * while keeping the expanded card within the display; all content uses this width.
     */
    public static float expandedW() {
        // Custom widths may not exceed the display: beyond that the ROM clips the window anyway.
        return Math.min(0.985f * W, 0.955f * W * GEOM_SCALE * EXPANDED_W_MUL);
    }
    public static float musicExpandedH() { return 0.4386f * W * GEOM_SCALE * EXPANDED_H_MUL; }
    public static float timerExpandedH() { return 0.247f * W * GEOM_SCALE * EXPANDED_H_MUL; }
    public static float expandedRadius() {
        return 0.0740f * W * GEOM_SCALE * RADIUS_MUL;
    }

    public static float chargingW() {
        return Math.min(0.93f * W, 0.526f * W * GEOM_SCALE);
    }

    /** v6.3: always-on idle capsule, independently sized from compact/expanded cards. */
    public static float idleW() {
        return Math.min(0.93f * W, 0.244f * W * GEOM_SCALE * IDLE_W_MUL);
    }

    public static float idleH() {
        return Math.min(0.30f * W, 0.0892f * W * GEOM_SCALE * IDLE_H_MUL);
    }

    /** v8.48: longer volume HUD with an inner level bar. */
    public static float volumeW() {
        return Math.min(0.93f * W, 0.620f * W * GEOM_SCALE);
    }

    public static float volumeH() {
        return Math.min(0.30f * W, 0.0980f * W * GEOM_SCALE);
    }

    public static final long VOLUME_HOLD_MS = 1500L;

    /** v8.31: ringer HUD (响铃/静音) measured from the 1284x1080 reference video. */
    public static float ringerW() {
        return Math.min(0.93f * W, 0.358f * W * GEOM_SCALE);
    }

    /** Reference silent pill is wider (505 px vs 457 px on 1284 px video). */
    public static float ringerSilentW() {
        return Math.min(0.93f * W, 0.393f * W * GEOM_SCALE);
    }

    public static float ringerH() {
        return Math.min(0.30f * W, 0.0935f * W * GEOM_SCALE);
    }

    /** v8.31: how long the ringer HUD stays after a sound-mode change. */
    public static final long RINGER_HOLD_MS = 2600L;
    public static final int RINGER_RED = 0xFFF83030;

    // ------------------------------------------------- lock / unlock animation
    // Reference: lock-screen island shows a padlock; unlocking expands to a black rounded
    // square with a green lock -> ring -> check animation.
    public static float lockW() {
        return Math.min(Math.min(0.93f * W, 0.400f * W * GEOM_SCALE), 0.45f * SCREEN_H);
    }
    public static float lockH() {
        return Math.min(Math.min(0.30f * W, 0.0780f * W * GEOM_SCALE), 0.070f * SCREEN_H);
    }
    public static float unlockSize() {
        // v8.55: on a very wide display (W much larger than the physical height) the 0.30W
        // square became almost the whole screen. Cap it by the current screen height too.
        return Math.min(Math.min(0.60f * W, 0.300f * W * GEOM_SCALE), 0.35f * SCREEN_H);
    }
    /** v8.38: the video is drawn inset inside the unchanged 0.300W square. */
    public static final float UNLOCK_VIDEO_SCALE = 0.80f;
    public static final long UNLOCK_VIDEO_MS = 1300L;
    public static final int UNLOCK_VIDEO_FRAMES = 77;
    public static final long UNLOCK_ANIM_MS = UNLOCK_VIDEO_MS;
    public static final long UNLOCK_REST_MS = 0L;
    public static final int UNLOCK_GREEN = 0xFF30E06A;

    // ----------------------------------------------------- notification island
    // Reference: dynamicSpot popup_first (compact pill) + popup_big (expanded details card).
    public static final float NOTIF_COMPACT_MIN_W = 0.400f;       // * screen width, before text
    public static final float NOTIF_COMPACT_MAX_W = 0.920f;       // * screen width, long titles clip
    public static float notifCompactMinW() { return NOTIF_COMPACT_MIN_W * W * GEOM_SCALE * COMPACT_W_MUL; }
    public static float notifCompactMaxW() { return NOTIF_COMPACT_MAX_W * W * GEOM_SCALE * COMPACT_W_MUL; }
    /** Expanded notification card is wrap_content (v3.6); these are the safety clamps. */
    public static float notifExpandedMinH() { return 0.18f * W * GEOM_SCALE * EXPANDED_H_MUL; }
    public static float notifExpandedMaxH() { return 0.55f * W * GEOM_SCALE * EXPANDED_H_MUL; }
    /** Compact notification auto-hide delay (reference pops briefly, then returns to idle/media). */
    public static final long NOTIF_HOLD_MS = 6000L;
    public static final int NOTIF_ACTION_BG = 0xFF3A3A3C;         // opaque action chip (no translucency)
    public static final int NOTIF_TIME = 0x95FFFFFF;

    // ---------------------------------------------------------------- colours
    public static final int CARD_BLACK = 0xFF000000;
    public static final int TEXT_WHITE = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0x95FFFFFF;
    public static final int TEXT_GREY = 0xFF8F8F8F;
    public static final int TIMER_ACCENT = 0xFFFAA427;
    public static final int TIMER_ICON_BG = 0xFF462C0E;          // amber squircle background
    public static final int TIMER_GLOW = 0xFFF79C00;
    public static final int MUSIC_WAVE_LEFT = 0xFFDE4F4A;        // waveform gradient (from video)
    public static final int MUSIC_WAVE_RIGHT = 0xFFD34C60;
    public static final int MUSIC_GLOW_DEFAULT = 0xFFA5363B;     // album-art ambient fallback
    public static final int CHARGE_GREEN = 0xFF28E080;           // normal charging bolt
    public static final int CHARGE_GOLD = 0xFFDEC640;            // fast / super fast charging bolt
    public static final int BTN_GREY = 0xFFCDCDD0;               // timer expanded "resume" circle
    public static final int BTN_GREY_ICON = 0xFF333333;
    public static final int BTN_ORANGE = 0xFFFE9043;             // timer expanded "pause" circle
    public static final int SEEK_TRACK = 0x50FFFFFF;
    /** Reference music progress fill is a warm white (#E0D6D4 measured from the recording). */
    public static final int MUSIC_SEEK_FILL = 0xFFE0D6D4;
    public static final int SUBTITLE_BLUE_GREY = 0xFF4A6475;
    public static final int APP_ICON_RED = 0xFFFB6767;

    // ------------------------------------------------------------- animation
    // measured frame-by-frame (see analysis/video and analysis/apk SPEC.json)
    public static final long CHARGE_APPEAR_MS = 560;   // dot -> pill, spring fit (zeta .65, omega 11)
    public static final long CHARGE_CONTENT_MS = 370;
    public static final long CHARGE_CONTENT_DELAY = 230;
    public static final long CHARGE_HOLD_MS = 6000;    // visible 0.83s -> 6.9s in the video
    public static final long CHARGE_HIDE_MS = 500;      // pill -> dot, fitted exponential decay
    public static final long CHARGE_DOT_HOLD_MS = 650;  // dot stays ~0.6s before vanishing

    public static final long TIMER_EXPAND_MS = 380;    // timer f4 -> f14, spring fit (zeta .80, omega 15)
    public static final long MUSIC_EXPAND_MS = 300;    // music f37 -> f45, spring fit (zeta .62, omega 10)
    public static final long COLLAPSE_PHASE1_MS = 220; // expanded -> empty pill (content instantly gone)
    public static final long COLLAPSE_PHASE2_MS = 530; // empty pill -> compact pill (content fades in)
    public static final long CONTENT_FADE_MS = 180;

    public static final long EXPANDED_TIMEOUT_MS = 5000; // auto collapse after inactivity
}
