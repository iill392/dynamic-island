package com.lingdong.island;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The island itself. Everything is drawn by hand with Canvas so the geometry can reproduce the
 * reference recordings exactly (no layout inflation involved).
 */
public class IslandView extends View {

    /** Expanded music card: the 5 button centres as a fraction of the card width
     *  (app icon / previous / play-pause / next / heart). Shared with the service hit-testing. */
    public static final float[] MUSIC_BUTTON_X = {0.0968f, 0.2934f, 0.4926f, 0.6922f, 0.8897f};

    /** Expanded timer card: the 2 round button centres as a fraction of the card width
     *  (grey play button / orange pause button). Shared with the service hit-testing. */
    public static final float[] TIMER_BUTTON_X = {0.731f, 0.885f};

    public interface Callback {
        /** Tap position as fractions of the card (x: 0 = left edge, 1 = right; y: 0 = top). */
        void onIslandTap(float xFraction, float yFraction);

        /** Called once after every spring animation has settled (the current mode is authoritative). */
        void onIslandSettled();

        /** v3.2: the user tapped outside the island (ACTION_OUTSIDE or the transparent window margin). */
        void onIslandOutsideTap();

        /** v3.2: the user swiped up on the island - collapse/dismiss it. */
        void onIslandSwipeUp();
    }

    public static final int MODE_HIDDEN = 0;
    public static final int MODE_CHARGE = 1;
    public static final int MODE_TIMER_COMPACT = 2;
    public static final int MODE_TIMER_EXPANDED = 3;
    public static final int MODE_MUSIC_COMPACT = 4;
    public static final int MODE_MUSIC_EXPANDED = 5;
    // v3.3: notification island (reference dynamicSpot popup_first / popup_big).
    public static final int MODE_NOTIF_COMPACT = 6;
    public static final int MODE_NOTIF_EXPANDED = 7;
    /** v6.0: always-on idle capsule (visible even when no music/timer/notification is active). */
    public static final int MODE_IDLE = 8;
    /** v8.31: ringer HUD (响铃 / 静音), compact only. */
    public static final int MODE_RINGER = 9;
    /** v8.31: lock-screen padlock pill. */
    public static final int MODE_LOCK = 10;
    /** v8.31: unlock animation (black rounded square + green lock/ring/check). */
    public static final int MODE_UNLOCK = 11;
    /** v8.47: transient system volume HUD. */
    public static final int MODE_VOLUME = 12;

    // ------------------------------------------------------------------ state
    private int mode = MODE_HIDDEN;
    private int prevMode = MODE_HIDDEN;

    private IslandData.Charging charging = new IslandData.Charging();
    private IslandData.Timer timer = new IslandData.Timer();
    private IslandData.Music music = new IslandData.Music();
    private IslandData.NotificationItem notification = new IslandData.NotificationItem();
    private boolean ringerSilent;

    /** v8.64: current animated card size, used for camera-follow placement/clamping. */
    public float getCardWidth() { return cardW; }
    public float getCardHeight() { return cardH; }
    private boolean unlockAnimating;
    private long unlockElapsedMs;
    private int volumeStream;
    private int volumeLevel;
    private int volumeMax = 1;

    /** v8.47: current stream / level for the volume HUD. */
    public void setVolume(int stream, int level, int max) {
        volumeStream = stream;
        volumeLevel = level;
        volumeMax = Math.max(1, max);
        invalidate();
    }

    // v8.36: the unlock visual is now the supplied video itself, pre-packed into one sprite sheet
    // (72 frames, 9 x 8, 256 px cells). No hand-drawn green animation is used.
    private static final int UNLOCK_FRAME_COUNT = 77;
    private static final int UNLOCK_SHEET_COLS = 9;
    private static final int UNLOCK_SHEET_ROWS = 9;
    private static final int UNLOCK_CELL = 256;
    private Bitmap unlockSheet;
    private int unlockFrame;
    private final Rect unlockSrc = new Rect();

    /** v8.36: select one of the 72 pre-extracted video frames. */
    public void setUnlockVideoFrame(int frame) {
        int f = frame;
        if (f < 0) f = 0;
        if (f >= UNLOCK_FRAME_COUNT) f = UNLOCK_FRAME_COUNT - 1;
        if (unlockFrame != f) {
            unlockFrame = f;
            invalidate();
        }
    }

    /** v8.31: true = 静音 HUD, false = 响铃 HUD. */
    public void setRingerSilent(boolean silent) {
        ringerSilent = silent;
        invalidate();
    }

    /** v8.31: unlock animation clock, driven by the service at ~60 fps. */
    public void setUnlockAnim(boolean active, long elapsedMs) {
        unlockAnimating = active;
        unlockElapsedMs = Math.max(0L, elapsedMs);
        invalidate();
    }

    private float cardW, cardH, radius;   // animated card geometry (px)
    private float cardTop;                // card top offset inside the window, from screen top

    private Callback callback;
    private final long startUptime = SystemClock.uptimeMillis();

    // ===== geometry layer: dual-axis damped springs, calibrated from F:\ANIMATION.md §2 =====
    // release = expanding (compact -> expanded, pop-in)   press = collapsing (-> compact / dot)
    // v5.0: fitted to the new reference video frame-by-frame (video_20261025_142841.mp4,
    // 1776x1080@30fps, music card), using the same semi-implicit Euler + settle gate as Spring:
    // v5.4: v5.3's k=110 fixed the stiff/fast pop but was a touch too slow on the phone; nudge the
    // stiffness up to k=140 (ζ=0.60, same +7.4% rebound): peak ~317ms, settle ~550ms (v5.3 was
    // ~350/600ms). This is a small speed-up, not a return to the snappy v5.2 curve.
    private static final float RELEASE_W_ZETA = 0.60f;
    private static final float RELEASE_W_K = 140f;
    private static final float RELEASE_H_ZETA = 0.60f;
    private static final float RELEASE_H_K = 140f;
    private static final float PRESS_W_ZETA = 0.47f;
    private static final float PRESS_W_K = 165f;
    private static final float PRESS_H_ZETA = 0.55f;
    private static final float PRESS_H_K = 320f;
    private static final float COLLAPSE1_ZETA = 0.78f;
    private static final float COLLAPSE1_K = 740f;
    private static final float RELAYOUT_W_ZETA = 0.60f;
    private static final float RELAYOUT_W_K = 300f;

    /** window = (card + 2*SHADOW_PAD) * 1.18 so the spring overshoot is never clipped (§2.6) */
    private static final float SPRING_HEADROOM = 1.18f;
    private static final int SHADOW_PAD_DP = 6;
    private static final float DT_MAX = 0.032f;      // manual §2.1: clamp dt to 32 ms

    // ===== content layer: cubic-bezier alpha fades, F:\ANIMATION.md §3-5 ==================
    private static final long CONTENT_MS = 340L;    // content fade base duration
    private static final long POP_MS = 150L;        // pill fade-out on expand
    private static final long COLLAPSE_OUT_MS = 170L; // big card content 1 -> 0
    private static final long COLLAPSE_IN_MS = 90L;   // pill content 0 -> 1 after spring settles
    private static final long HIDE_OUT_MS = 170L;     // hide: target content 1 -> 0
    private static final long SHOW_DELAY_MS = 75L;    // show: delay before content fades in
    private static final long EXPAND_DELAY_MS = 60L;  // expand: delay before content fades in

    private static final float DOT = 0.62f;          // appear/shrink dot size relative to pill height

    /** Fast-charge glow lifetime: 900 ms ramp-up + 1100 ms decay (same envelope as before). */
    private static final long CHARGE_GLOW_MS = 2000L;

    // v3.7: motion blur while the island morphs. The radius follows the spring velocity, so the
    // island is softly blurred during expand/collapse and becomes perfectly sharp once it settles.
    // RenderEffect is available from API 31; older devices simply keep the crisp animation.
    private static final float MAX_BLUR_DP = 9f;
    private static final float BLUR_PER_VELOCITY = 0.0022f;
    private static final float BLUR_EPSILON_PX = 0.35f;
    private float appliedBlurRadiusPx = -1f;
    /** v8.5: skip the (expensive) whole-window RenderEffect during collapse/hide. */
    private boolean suppressMotionBlur;

    // ===== v3.3 notification layout (the same fractions are used for drawing and hit-testing) ==
    private static final float NOTIF_COMPACT_ICON = 0.62f;   // icon diameter, of compact height
    private static final float NOTIF_COMPACT_PAD_L = 0.19f;  // left padding, of compact height
    private static final float NOTIF_COMPACT_GAP = 0.16f;    // icon -> label, of compact height
    private static final float NOTIF_COMPACT_PAD_R = 0.30f;  // right padding, of compact height
    private static final int NOTIF_MAX_BODY_LINES = 6;   // visible body lines in the card
    private static final int NOTIF_MAX_WRAP_LINES = 24;   // wrap enough to know the overflow
    private static final int NOTIF_MAX_ACTIONS = 3;

    // v3.9: after the expand animation finishes, overflowing text slowly auto-scrolls.
    private static final long MARQUEE_DELAY_MS = 600L;
    private static final float MARQUEE_SPEED_DP = 22f;
    private static final long MARQUEE_END_PAUSE_MS = 700L;

    private final Spring wS = new Spring();
    private final Spring hS = new Spring();
    private final Spring rS = new Spring();
    /** v6.1: idle-capsule press feedback: 1 = fully pressed, 0 = released. */
    private final Spring pressS = new Spring(0f, 0.60f, 300f);
    /** v6.8: user-supplied artwork used as the expanded music card's app badge. */
    private Bitmap musicBadgeBitmap;
    private boolean pressActive;
    private final Fade prevFade = new Fade(0f);
    private final Fade targetFade = new Fade(0f);
    private boolean pendingCollapseContentIn;
    private boolean springsWereMoving;
    /** v5.0 two-stage collapse: phase 1 expanded -> empty pill, phase 2 empty pill -> compact. */
    private boolean collapsePhased;
    private int collapseStage;          // 0 none, 1 shrinking to empty pill, 2 relayout to compact
    private int collapseCompactMode;
    private boolean collapseHeightClamp;
    /** v5.9: watchdog for the empty-pill phase — content must never stay hidden after a collapse. */
    private long collapsePhaseStartMs;
    /**
     * True between the start of a spring transition and its settle callback. Unlike
     * springsWereMoving (which is reset when the window is detached mid-animation), this flag
     * survives a re-attach so onIslandSettled() is never lost (audit R-04).
     */
    private boolean settlePending;
    /** Increased for every started transition; lets a queued (re-attach) callback detect that a
     *  newer transition superseded it, so one transition can never deliver two callbacks. */
    private int settleGeneration;

    private Choreographer choreographer;
    private boolean frameScheduled;
    private long lastFrameNanos;

    /** Text size (px) used by notificationCompactWidth() to measure the 12sp compact label. */
    private final float notifLabelSizePx;

    // touch bookkeeping (tap vs. swipe)
    private final int touchSlop;
    /** v3.2: how far a card-internal drag has to go up before it counts as "swipe up to collapse". */
    private final float swipeDismissPx;
    private float touchDownX, touchDownY;
    private boolean touchTapCandidate;
    private boolean touchTracking;
    private boolean touchStartedInside;
    private boolean swipeDismiss;

    // one-shot delayed redraw used by the ticking texts (timer digits / playback clock)
    private boolean slowRefreshPosted;
    private final Runnable slowRefreshTask = new Runnable() {
        @Override public void run() {
            slowRefreshPosted = false;
            invalidate();
        }
    };

    // ----------------------------------------------------------------- paints
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path tmpPath = new Path();
    private final RectF tmpRect = new RectF();
    private final RectF cardRect = new RectF();
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

    // ---------------------------------- cached text/hashes (steady state: no allocation)
    private boolean timerDigitsValid;
    private long timerDigitsSec = Long.MIN_VALUE;
    private long timerDigitsWhen;
    private boolean timerDigitsCountDown;

    private String percentText;
    private int percentLevel = Integer.MIN_VALUE;
    private float percentPrecise = Float.NaN;
    private boolean percentFast, percentSuperFast;

    private String elapsedText = "00:00";
    private String totalText = "00:00";
    private long timeTextPosSec = Long.MIN_VALUE;
    private long timeTextDurSec = Long.MIN_VALUE;

    private final ClipTextCache titleClipCache = new ClipTextCache();
    private final ClipTextCache artistClipCache = new ClipTextCache();

    // ------------------------------------------------- v3.3 notification text caches
    private String notifWidthLabel;
    private float notifWidthHc = -1f;
    private float notifWidthValue;
    private boolean notifWidthValid;

    private String notifBodyText;
    private float notifBodyMaxW = -1f;
    private float notifBodyTextSize = -1f;
    private int notifBodyBudget = -1;
    private final String[] notifBodyLines = new String[NOTIF_MAX_WRAP_LINES];
    private int notifBodyLineCount;          // total wrapped lines (may exceed the visible viewport)
    private int notifBodyVisibleLineCount;   // number of lines the card is tall enough to show
    private float notifBodyOverflowPx;       // (total - visible) * lineH, for vertical auto-scroll
    private float notifTitleOverflowPx;      // title width beyond the text column, for marquee
    private float musicTitleOverflowPx;      // music title overflow, for marquee

    // v3.9: marquee is enabled only after the expand transition fully settles.
    private long expandedSettledAtMs;
    private boolean marqueeNeeded;

    // --------------------------------------- v3.6 notification expanded card metrics
    // Reference dynamicSpot popup_big: CardView wrap_content, 60dp round icon on the left, a column
    // to its right (11sp header, 15sp title, 13sp body max 6 lines, action buttons), 4dp card
    // padding + 16dp frame padding. The height is computed from the content instead of being fixed
    // at music-card height, so a one-line notification no longer leaves a huge black void.
    private String notifExpansionKey;
    private float notifExpCardH;
    private float notifExpPad;
    private float notifExpFramePad;
    private float notifExpIconD;
    private float notifExpContentW;
    private float notifExpContentH;
    private float notifExpHeaderSize;
    private float notifExpTitleSize;
    private float notifExpBodySize;
    private float notifExpLineH;
    private float notifExpHeaderGap;
    private float notifExpBodyGap;
    private float notifExpActionH;
    private float notifExpActionGapTop;
    private float notifExpActionTextSize;
    private int notifExpActionCount;

    private String notifLetterSource;
    private String notifLetter = "?";

    private final ClipTextCache notifCompactClipCache = new ClipTextCache();
    private final ClipTextCache notifTitleClipCache = new ClipTextCache();
    private final ClipTextCache notifAppClipCache = new ClipTextCache();
    private final ClipTextCache[] notifActionClipCaches = {
            new ClipTextCache(), new ClipTextCache(), new ClipTextCache()};
    /** v5.8: shift the whole notification content column to the right (compact + expanded). */
    private static final float NOTIF_COMPACT_SHIFT_OVER_H = 0.22f;
    private static final float NOTIF_EXPANDED_SHIFT_OVER_W = 0.020f;
    private float notifExpShift;

    // ------------------------------------------- cached shaders (rebuilt only when changed)
    private final ShaderCache ambientGlowShader = new ShaderCache();
    private final ShaderCache chargeGlowShader = new ShaderCache();
    private final ShaderCache chargeBoltGlowShader = new ShaderCache();
    private final ShaderCache timerCompactGlowShader = new ShaderCache();
    private final ShaderCache timerExpandedGlowShader = new ShaderCache();
    private final ShaderCache waveformShader = new ShaderCache();
    private final ShaderCache musicBandShader = new ShaderCache();
    private final ShaderCache artworkFallbackShader = new ShaderCache();

    private final Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);

    // reusable icon paths (24x24 viewport)
    private static final Path ICON_BOLT = new Path();
    private static final Path ICON_PLAY = new Path();
    private static final Path ICON_PAUSE = new Path();
    private static final Path ICON_PREV = new Path();
    private static final Path ICON_NEXT = new Path();
    private static final Path ICON_HEART = new Path();
    private static final Path ICON_NOTE = new Path();
    private static final Path ICON_BELL = makeBell();
    private static final Path ICON_LOCK = makeLock();
    private static final Path ICON_VOLUME = makeVolume();

    private static Path makeVolume() {
        Path p = new Path();
        // material "volume_up"
        p.moveTo(3f, 9f);
        p.lineTo(3f, 15f);
        p.lineTo(7f, 15f);
        p.lineTo(12f, 20f);
        p.lineTo(12f, 4f);
        p.lineTo(7f, 9f);
        p.close();
        p.moveTo(16.5f, 12f);
        p.cubicTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f);
        p.lineTo(14f, 16.02f);
        p.cubicTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f);
        p.close();
        p.moveTo(14f, 3.23f);
        p.lineTo(14f, 5.29f);
        p.cubicTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f);
        p.cubicTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f);
        p.lineTo(14f, 20.77f);
        p.cubicTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f);
        p.cubicTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f);
        p.close();
        return p;
    }

    private static Path makeLock() {
        Path p = new Path();
        // material "lock" (solid)
        p.moveTo(18f, 8f);
        p.lineTo(17f, 8f);
        p.lineTo(17f, 6f);
        p.cubicTo(17f, 3.24f, 14.76f, 1f, 12f, 1f);
        p.cubicTo(9.24f, 1f, 7f, 3.24f, 7f, 6f);
        p.lineTo(7f, 8f);
        p.lineTo(6f, 8f);
        p.cubicTo(4.9f, 8f, 4f, 8.9f, 4f, 10f);
        p.lineTo(4f, 20f);
        p.cubicTo(4f, 21.1f, 4.9f, 22f, 6f, 22f);
        p.lineTo(18f, 22f);
        p.cubicTo(19.1f, 22f, 20f, 21.1f, 20f, 20f);
        p.lineTo(20f, 10f);
        p.cubicTo(20f, 8.9f, 19.1f, 8f, 18f, 8f);
        p.close();
        p.moveTo(12f, 17f);
        p.cubicTo(10.9f, 17f, 10f, 16.1f, 10f, 15f);
        p.cubicTo(10f, 13.9f, 10.9f, 13f, 12f, 13f);
        p.cubicTo(13.1f, 13f, 14f, 13.9f, 14f, 15f);
        p.cubicTo(14f, 16.1f, 13.1f, 17f, 12f, 17f);
        p.close();
        p.moveTo(9f, 8f);
        p.lineTo(9f, 6f);
        p.cubicTo(9f, 4.34f, 10.34f, 3f, 12f, 3f);
        p.cubicTo(13.66f, 3f, 15f, 4.34f, 15f, 6f);
        p.lineTo(15f, 8f);
        p.close();
        p.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        return p;
    }

    private static Path makeBell() {
        Path p = new Path();
        // material "notifications" bell, solid
        p.moveTo(12f, 22f);
        p.cubicTo(13.1f, 22f, 13.99f, 21.1f, 13.99f, 20f);
        p.lineTo(10.01f, 20f);
        p.cubicTo(10.01f, 21.1f, 10.9f, 22f, 12f, 22f);
        p.close();
        p.moveTo(18f, 16f);
        p.lineTo(18f, 11f);
        p.cubicTo(18f, 7.93f, 16.37f, 5.36f, 13.5f, 4.68f);
        p.lineTo(13.5f, 4f);
        p.cubicTo(13.5f, 3.17f, 12.83f, 2.5f, 12f, 2.5f);
        p.cubicTo(11.17f, 2.5f, 10.5f, 3.17f, 10.5f, 4f);
        p.lineTo(10.5f, 4.68f);
        p.cubicTo(7.64f, 5.36f, 6f, 7.92f, 6f, 11f);
        p.lineTo(6f, 16f);
        p.lineTo(4f, 18f);
        p.lineTo(4f, 19f);
        p.lineTo(20f, 19f);
        p.lineTo(20f, 18f);
        p.close();
        return p;
    }

    static {
        // material "bolt"
        ICON_BOLT.moveTo(11f, 21f); ICON_BOLT.lineTo(10f, 21f); ICON_BOLT.lineTo(11f, 14f);
        ICON_BOLT.lineTo(7.5f, 14f); ICON_BOLT.cubicTo(6.62f, 14f, 7.17f, 13.25f, 7.19f, 13.22f);
        ICON_BOLT.cubicTo(8.48f, 10.94f, 10.42f, 7.54f, 13.01f, 3f);
        ICON_BOLT.lineTo(14.01f, 3f); ICON_BOLT.lineTo(13.01f, 10f);
        ICON_BOLT.lineTo(16.52f, 10f); ICON_BOLT.cubicTo(16.92f, 10f, 17.14f, 10.19f, 16.92f, 10.66f);
        ICON_BOLT.cubicTo(12.97f, 17.55f, 11f, 21f, 11f, 21f);
        ICON_BOLT.close();

        ICON_PLAY.moveTo(8f, 5f); ICON_PLAY.lineTo(19f, 12f); ICON_PLAY.lineTo(8f, 19f);
        ICON_PLAY.close();

        ICON_PAUSE.moveTo(6f, 5f); ICON_PAUSE.lineTo(10f, 5f); ICON_PAUSE.lineTo(10f, 19f);
        ICON_PAUSE.lineTo(6f, 19f); ICON_PAUSE.close();
        ICON_PAUSE.moveTo(14f, 5f); ICON_PAUSE.lineTo(18f, 5f); ICON_PAUSE.lineTo(18f, 19f);
        ICON_PAUSE.lineTo(14f, 19f); ICON_PAUSE.close();

        ICON_PREV.moveTo(6f, 5f); ICON_PREV.lineTo(8.5f, 5f); ICON_PREV.lineTo(8.5f, 19f);
        ICON_PREV.lineTo(6f, 19f); ICON_PREV.close();
        ICON_PREV.moveTo(18f, 5f); ICON_PREV.lineTo(9f, 12f); ICON_PREV.lineTo(18f, 19f);
        ICON_PREV.close();

        ICON_NEXT.moveTo(15.5f, 5f); ICON_NEXT.lineTo(18f, 5f); ICON_NEXT.lineTo(18f, 19f);
        ICON_NEXT.lineTo(15.5f, 19f); ICON_NEXT.close();
        ICON_NEXT.moveTo(6f, 5f); ICON_NEXT.lineTo(15f, 12f); ICON_NEXT.lineTo(6f, 19f);
        ICON_NEXT.close();

        // material "favorite_border"
        ICON_HEART.moveTo(16.5f, 3f);
        ICON_HEART.cubicTo(14.76f, 3f, 13.09f, 3.81f, 12f, 5.09f);
        ICON_HEART.cubicTo(10.91f, 3.81f, 9.24f, 3f, 7.5f, 3f);
        ICON_HEART.cubicTo(4.42f, 3f, 2f, 5.42f, 2f, 8.5f);
        ICON_HEART.cubicTo(2f, 12.28f, 5.4f, 15.36f, 10.55f, 20.04f);
        ICON_HEART.lineTo(12f, 21.35f);
        ICON_HEART.lineTo(13.45f, 20.04f);
        ICON_HEART.cubicTo(18.6f, 15.36f, 22f, 12.28f, 22f, 8.5f);
        ICON_HEART.cubicTo(22f, 5.42f, 19.58f, 3f, 16.5f, 3f);
        ICON_HEART.close();
        ICON_HEART.moveTo(12.1f, 18.55f);
        ICON_HEART.lineTo(12f, 18.65f);
        ICON_HEART.lineTo(11.9f, 18.55f);
        ICON_HEART.cubicTo(7.14f, 14.24f, 4f, 11.39f, 4f, 8.5f);
        ICON_HEART.cubicTo(4f, 6.5f, 5.5f, 5f, 7.5f, 5f);
        ICON_HEART.cubicTo(9.04f, 5f, 10.54f, 5.99f, 11.07f, 7.36f);
        ICON_HEART.lineTo(12.94f, 7.36f);
        ICON_HEART.cubicTo(13.46f, 5.99f, 14.96f, 5f, 16.5f, 5f);
        ICON_HEART.cubicTo(18.5f, 5f, 20f, 6.5f, 20f, 8.5f);
        ICON_HEART.cubicTo(20f, 11.39f, 16.86f, 14.24f, 12.1f, 18.55f);
        ICON_HEART.close();

        // eighth note
        ICON_NOTE.moveTo(12f, 3f); ICON_NOTE.lineTo(12f, 15.5f);
        ICON_NOTE.cubicTo(11.4f, 15.1f, 10.4f, 14.8f, 9.5f, 15.1f);
        ICON_NOTE.cubicTo(8.2f, 15.5f, 7.4f, 16.7f, 7.7f, 17.8f);
        ICON_NOTE.cubicTo(8f, 18.9f, 9.3f, 19.4f, 10.6f, 19f);
        ICON_NOTE.cubicTo(11.6f, 18.7f, 12.3f, 17.9f, 12.3f, 17f);
        ICON_NOTE.lineTo(12.3f, 7.5f); ICON_NOTE.lineTo(19f, 5.5f); ICON_NOTE.lineTo(19f, 15.5f);
        ICON_NOTE.cubicTo(18.4f, 15.1f, 17.4f, 14.8f, 16.5f, 15.1f);
        ICON_NOTE.cubicTo(15.2f, 15.5f, 14.4f, 16.7f, 14.7f, 17.8f);
        ICON_NOTE.cubicTo(15f, 18.9f, 16.3f, 19.4f, 17.6f, 19f);
        ICON_NOTE.cubicTo(18.6f, 18.7f, 19.3f, 17.9f, 19.3f, 17f);
        ICON_NOTE.lineTo(19.3f, 3f);
        ICON_NOTE.close();
    }

    public IslandView(Context context) {
        super(context);
        setWillNotDraw(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDismissPx = Math.max(touchSlop * 4f, 24f * IslandSpec.DENSITY);
        notifLabelSizePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 12f,
                context.getResources().getDisplayMetrics());
        cardPaint.setStyle(Paint.Style.FILL);
        cardPaint.setColor(IslandSpec.CARD_BLACK);
        choreographer = Choreographer.getInstance();
        cardTop = IslandSpec.cardTop();
        float d = IslandSpec.HC * DOT;
        cardW = cardH = d;
        radius = d / 2f;
        applyReleaseAxes();
        rS.posEps = 1f;
        rS.velEps = 30f;
        // The press spring animates a 0..1 value, so the normal 5px/120px·s⁻¹ gate would
        // immediately snap it; use a tiny gate for the scale feedback.
        pressS.posEps = 0.002f;
        pressS.velEps = 0.02f;
        snapSprings();
        prevFade.snap(0f);
        targetFade.snap(0f);
    }

    // -------------------------------------------------------------- animations
    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    // ------------------------------------------------------------------- data
    public IslandData.Charging charging() { return charging; }
    public IslandData.Timer timer() { return timer; }
    public IslandData.Music music() { return music; }
    /** v3.3: the notification currently shown by the island (service owns/updates it). */
    public IslandData.NotificationItem notification() { return notification; }

    public int getMode() { return mode; }

    public boolean isExpanded() {
        return mode == MODE_TIMER_EXPANDED || mode == MODE_MUSIC_EXPANDED
                || mode == MODE_NOTIF_EXPANDED;
    }

    public boolean isVisible() { return mode != MODE_HIDDEN; }

    /** Re-read the screen dependent numbers (after a rotation / display-size change). */
    public void refreshFrame() {
        cardTop = IslandSpec.cardTop();
        if (mode == MODE_HIDDEN && prevMode == MODE_HIDDEN) {
            float d = IslandSpec.HC * DOT;
            cardW = cardH = d;
            radius = d / 2f;
            snapSprings();
            return;
        }
        // R-06: the geometry targets are derived from the new screen metrics; re-derive them and
        // snap onto the target so the card immediately matches the new calibration (no replay of
        // the spring animation). If the metrics did not actually change, leave a running
        // animation untouched. Covers the "still fading out" case too (prevMode != HIDDEN).
        float[] t = targetGeometry(mode);
        if (wS.target != t[0] || hS.target != t[1] || rS.target != t[2]) {
            wS.snap(t[0]);
            hS.snap(t[1]);
            rS.snap(t[2]);
            cardW = Math.max(1f, t[0]);
            cardH = Math.max(1f, t[1]);
            radius = Math.max(0.5f, Math.min(t[2], Math.min(cardW, cardH) / 2f));
        }
        invalidate();
    }

    /**
     * v5.9 safety net: a settled compact state must never be left with its content still at alpha 0
     * (the "empty capsule" bug). Called from the service after the settle callback.
     */
    public void ensureCompactContentVisible() {
        if (mode == MODE_HIDDEN || isExpanded()) return;
        // Only a collapsed expanded card can legitimately be left at alpha 0; a normal compact
        // show/morph is already fading in on its own schedule.
        boolean cameFromExpanded = prevMode == MODE_MUSIC_EXPANDED
                || prevMode == MODE_TIMER_EXPANDED || prevMode == MODE_NOTIF_EXPANDED;
        if (!cameFromExpanded && !collapsePhased) return;
        if (targetFade.value() < 0.99f) {
            targetFade.start(targetFade.value(), 1f, COLLAPSE_IN_MS, 0L, IslandEase.CONTENT);
            invalidate();
            kick();
        }
    }

    /**
     * v8.27 safety net for the rare "pure black expanded island" bug: a window detach/rebind can
     * leave the view in an expanded mode while its content layer target was snapped to alpha 0.
     * Restarts the content fade (or the stopped render loop) without replaying geometry.
     * @return true if a repair was necessary.
     */
    public boolean ensureContentVisible() {
        if (mode == MODE_HIDDEN) return false;
        if (targetFade.target() >= 0.99f) {
            if (targetFade.value() < 0.99f) {
                invalidate();
                kick();
            }
            return false;
        }
        targetFade.start(Math.max(targetFade.value(), 0.01f), 1f, 180L, 0L, IslandEase.CONTENT);
        invalidate();
        kick();
        return true;
    }

    /**
     * v8.27: if an expanded card was left with its content target at 0, replay the expand from the
     * dot so the user sees the normal pop-in instead of a fully grown black card. The window can
     * stay at its expanded size; the view itself restarts from the dot geometry.
     */
    public boolean replayExpandedIfBlank(int newMode) {
        if (mode == MODE_HIDDEN || newMode == MODE_HIDDEN) return false;
        if (targetFade.target() >= 0.99f) {
            if (targetFade.value() < 0.99f) {
                invalidate();
                kick();
            }
            return false;
        }
        snapHidden();
        showFromHidden(newMode);
        return true;
    }

    /** Window size that can fully contain the given mode including spring overshoot. */
    public static int[] windowSizeFor(int targetMode) {        float w, h;
        switch (targetMode) {
            case MODE_CHARGE:
                w = IslandSpec.chargingW(); h = IslandSpec.compactH(); break;
            case MODE_TIMER_COMPACT:
            case MODE_MUSIC_COMPACT:
                w = IslandSpec.compactW(); h = IslandSpec.compactH(); break;
            case MODE_TIMER_EXPANDED:
                w = IslandSpec.expandedW(); h = IslandSpec.timerExpandedH(); break;
            case MODE_MUSIC_EXPANDED:
                w = IslandSpec.expandedW(); h = IslandSpec.musicExpandedH(); break;
            case MODE_IDLE:
                w = IslandSpec.idleW(); h = IslandSpec.idleH(); break;
            case MODE_RINGER:
                w = IslandSpec.ringerSilentW(); h = IslandSpec.ringerH(); break;
            case MODE_VOLUME:
                w = IslandSpec.volumeW(); h = IslandSpec.volumeH(); break;
            case MODE_LOCK:
                w = IslandSpec.lockW(); h = IslandSpec.lockH(); break;
            case MODE_UNLOCK:
                w = IslandSpec.unlockSize(); h = IslandSpec.unlockSize(); break;
            default:
                w = IslandSpec.HC * DOT; h = w; break;
        }
        float shadow = SHADOW_PAD_DP * IslandSpec.DENSITY;
        int winW = Math.round((w + 2f * shadow) * SPRING_HEADROOM);
        // v3.2: never create an overlay window wider than the screen (the expanded window would
        // be ~1.156W). The card itself is 0.98W, so it stays fully visible and the off-screen
        // spring overshoot would have been clipped by the screen edge anyway.
        if (IslandSpec.W > 0f) winW = Math.min(winW, Math.round(IslandSpec.W));
        int winH = Math.round(IslandSpec.cardTop() + (h + 2f * shadow) * SPRING_HEADROOM);
        if (IslandSpec.SCREEN_H > 0f) winH = Math.min(winH, Math.round(IslandSpec.SCREEN_H));
        return new int[]{Math.max(1, winW), Math.max(1, winH)};
    }

    /**
     * v3.3: same window sizing as {@link #windowSizeFor(int)}, but mode-aware for the
     * notification island (dynamic compact width + notification expanded height). The service
     * calls this instance version.
     */
    public int[] windowSizeForMode(int targetMode) {
        float w, h;
        switch (targetMode) {
            case MODE_CHARGE:
                w = IslandSpec.chargingW(); h = IslandSpec.compactH(); break;
            case MODE_TIMER_COMPACT:
            case MODE_MUSIC_COMPACT:
                w = IslandSpec.compactW(); h = IslandSpec.compactH(); break;
            case MODE_TIMER_EXPANDED:
                w = IslandSpec.expandedW(); h = IslandSpec.timerExpandedH(); break;
            case MODE_MUSIC_EXPANDED:
                w = IslandSpec.expandedW(); h = IslandSpec.musicExpandedH(); break;
            case MODE_NOTIF_COMPACT:
                w = notificationCompactWidth(); h = IslandSpec.compactH(); break;
            case MODE_NOTIF_EXPANDED:
                w = IslandSpec.expandedW(); h = notificationExpandedHeight(); break;
            case MODE_IDLE:
                w = IslandSpec.idleW(); h = IslandSpec.idleH(); break;
            case MODE_RINGER:
                w = IslandSpec.ringerSilentW(); h = IslandSpec.ringerH(); break;
            case MODE_VOLUME:
                w = IslandSpec.volumeW(); h = IslandSpec.volumeH(); break;
            case MODE_LOCK:
                w = IslandSpec.lockW(); h = IslandSpec.lockH(); break;
            case MODE_UNLOCK:
                w = IslandSpec.unlockSize(); h = IslandSpec.unlockSize(); break;
            default:
                w = IslandSpec.HC * DOT; h = w; break;
        }
        float shadow = SHADOW_PAD_DP * IslandSpec.DENSITY;
        int winW = Math.round((w + 2f * shadow) * SPRING_HEADROOM);
        if (IslandSpec.W > 0f) winW = Math.min(winW, Math.round(IslandSpec.W));
        int winH = Math.round(IslandSpec.cardTop() + (h + 2f * shadow) * SPRING_HEADROOM);
        if (IslandSpec.SCREEN_H > 0f) winH = Math.min(winH, Math.round(IslandSpec.SCREEN_H));
        return new int[]{Math.max(1, winW), Math.max(1, winH)};
    }

    private float[] targetGeometry(int targetMode) {
        switch (targetMode) {
            case MODE_CHARGE: {
                return new float[]{IslandSpec.chargingW(), IslandSpec.compactH(), IslandSpec.compactH() / 2f};
            }
            case MODE_TIMER_COMPACT:
            case MODE_MUSIC_COMPACT: {
                float h = IslandSpec.compactH();
                return new float[]{IslandSpec.compactW(), h, h / 2f};
            }
            case MODE_IDLE: {
                float h = IslandSpec.idleH();
                return new float[]{IslandSpec.idleW(), h, h / 2f};
            }
            case MODE_RINGER: {
                float h = IslandSpec.ringerH();
                float w = ringerSilent ? IslandSpec.ringerSilentW() : IslandSpec.ringerW();
                return new float[]{w, h, h / 2f};
            }
            case MODE_VOLUME: {
                float h = IslandSpec.volumeH();
                return new float[]{IslandSpec.volumeW(), h, h / 2f};
            }
            case MODE_LOCK: {
                float h = IslandSpec.lockH();
                return new float[]{IslandSpec.lockW(), h, h / 2f};
            }
            case MODE_UNLOCK: {
                float s = IslandSpec.unlockSize();
                return new float[]{s, s, s * 0.26f};
            }
            case MODE_TIMER_EXPANDED: {
                return new float[]{IslandSpec.expandedW(), IslandSpec.timerExpandedH(), IslandSpec.expandedRadius()};
            }
            case MODE_MUSIC_EXPANDED: {
                return new float[]{IslandSpec.expandedW(), IslandSpec.musicExpandedH(), IslandSpec.expandedRadius()};
            }
            case MODE_NOTIF_COMPACT: {
                float h = IslandSpec.compactH();
                return new float[]{notificationCompactWidth(), h, h / 2f};
            }
            case MODE_NOTIF_EXPANDED: {
                return new float[]{IslandSpec.expandedW(), notificationExpandedHeight(),
                        IslandSpec.expandedRadius()};
            }
            default: {
                float d = IslandSpec.HC * DOT;
                return new float[]{d, d, d / 2f};
            }
        }
    }

    /** release axes: expanding — v5.0 fitted video spring (same smooth spring for both axes). */
    private void applyReleaseAxes() {
        wS.configure(RELEASE_W_ZETA, RELEASE_W_K);
        hS.configure(RELEASE_H_ZETA, RELEASE_H_K);
        rS.configure(RELEASE_H_ZETA, RELEASE_H_K);
    }

    /** press axes: collapsing / hiding (legacy one-stage / hide path). */
    private void applyPressAxes() {
        wS.configure(PRESS_W_ZETA, PRESS_W_K);
        hS.configure(PRESS_H_ZETA, PRESS_H_K);
        rS.configure(PRESS_H_ZETA, PRESS_H_K);
    }

    /** v5.0 collapse phase 1: very fast, almost critically damped shrink to the empty pill. */
    private void applyCollapse1Axes() {
        wS.configure(COLLAPSE1_ZETA, COLLAPSE1_K);
        hS.configure(COLLAPSE1_ZETA, COLLAPSE1_K);
        rS.configure(COLLAPSE1_ZETA, COLLAPSE1_K);
    }

    /** v5.0 collapse phase 2: empty pill relayout to the compact pill width. */
    private void applyRelayoutAxes() {
        wS.configure(RELAYOUT_W_ZETA, RELAYOUT_W_K);
        hS.configure(COLLAPSE1_ZETA, COLLAPSE1_K);
        rS.configure(COLLAPSE1_ZETA, COLLAPSE1_K);
    }

    private void resetCollapsePhases() {
        collapsePhased = false;
        collapseStage = 0;
        collapseHeightClamp = false;
    }

    /** Pop-in from the idle dot: release springs + content fade-in (340 ms, 75 ms delay). */
    public void showFromHidden(int newMode) {
        prevMode = MODE_HIDDEN;
        mode = newMode;
        resetMarquee();
        resetCollapsePhases();
        suppressMotionBlur = false;
        pressActive = false;
        pressS.snap(0f);
        settlePending = true;
        settleGeneration++;
        // F-08: a charging session starts with a fresh one-shot glow (armed on the first draw
        // that sees fast/superFast charging).
        if (newMode == MODE_CHARGE) {
            chargeGlowWasActive = false;
            chargeGlowStart = 0L;
        }
        float d = IslandSpec.HC * DOT;
        cardW = cardH = d;
        radius = d / 2f;
        snapSprings();
        applyReleaseAxes();
        float[] t = targetGeometry(newMode);
        wS.setTarget(t[0]);
        hS.setTarget(t[1]);
        rS.setTarget(t[2]);
        prevFade.snap(0f);
        targetFade.snap(0f);
        targetFade.start(0f, 1f, CONTENT_MS, SHOW_DELAY_MS, IslandEase.CONTENT);
        kick();
    }

    /** Morph between two visible states (compact <-> expanded / compact <-> charging). */
    public void morphTo(int newMode, boolean expanding) {
        prevMode = mode;
        mode = newMode;
        resetMarquee();
        resetCollapsePhases();
        suppressMotionBlur = false;
        pressActive = false;
        pressS.snap(0f);
        settlePending = true;
        settleGeneration++;
        // F-08: entering the charging state re-arms the one-shot glow for this session.
        if (newMode == MODE_CHARGE) {
            chargeGlowWasActive = false;
            chargeGlowStart = 0L;
        }
        if (expanding) applyReleaseAxes(); else applyPressAxes();
        float[] t = targetGeometry(newMode);
        wS.setTarget(t[0]);
        hS.setTarget(t[1]);
        rS.setTarget(t[2]);
        // pill / old content fades out with STANDARD; new content fades in with CONTENT
        float shown = visibleContentAlpha();
        prevFade.snap(shown);
        prevFade.start(shown, 0f, expanding ? POP_MS : COLLAPSE_OUT_MS, 0L, IslandEase.STANDARD);
        targetFade.snap(0f);
        targetFade.start(0f, 1f, CONTENT_MS, EXPAND_DELAY_MS, IslandEase.CONTENT);
        pendingCollapseContentIn = false;
        kick();
    }

    /** Expanded card -> compact pill: press springs, big content fades out 170 ms, pill content in 90 ms after settle. */
    public void collapseToCompact(int compactMode) {
        prevMode = mode;
        mode = compactMode;
        resetMarquee();
        suppressMotionBlur = true;
        clearMotionBlur();
        pressActive = false;
        pressS.snap(0f);
        settlePending = true;
        settleGeneration++;
        // v5.0: reference does expanded -> empty pill -> compact. First target the empty pill
        // (0.244 W, compact height); doFrame switches to phase 2 when the springs settle.
        collapsePhased = true;
        collapseStage = 1;
        collapseCompactMode = compactMode;
        collapseHeightClamp = true;
        collapsePhaseStartMs = SystemClock.uptimeMillis();
        applyCollapse1Axes();
        float[] t = targetGeometry(compactMode);
        wS.setTarget(IslandSpec.minPillW());
        hS.setTarget(t[1]);
        rS.setTarget(t[1] / 2f);
        float shown = visibleContentAlpha();
        prevFade.snap(shown);
        prevFade.start(shown, 0f, COLLAPSE_OUT_MS, 0L, IslandEase.STANDARD);
        // no compact/pill content until phase 2: the empty pill is content-less in the reference
        targetFade.snap(0f);
        pendingCollapseContentIn = false;
        kick();
    }

    /** Hide with the press springs; the content fades out over 170 ms while the card shrinks to the dot. */
    public void hide(boolean instant) {
        if (instant) {
            snapHidden();
            return;
        }
        prevMode = mode;
        mode = MODE_HIDDEN;
        resetMarquee();
        resetCollapsePhases();
        suppressMotionBlur = true;
        clearMotionBlur();
        pressActive = false;
        pressS.snap(0f);
        settlePending = true;
        settleGeneration++;
        applyPressAxes();
        float d = IslandSpec.HC * DOT;
        wS.setTarget(d);
        hS.setTarget(d);
        rS.setTarget(d / 2f);
        float shown = visibleContentAlpha();
        prevFade.snap(shown);
        prevFade.start(shown, 0f, HIDE_OUT_MS, 0L, IslandEase.STANDARD);
        targetFade.snap(0f);
        pendingCollapseContentIn = false;
        kick();
    }

    /** Dismiss (charging): same spring press as hide. */
    public void dismiss() { hide(false); }

    /** v8.22: notification close — keep the collapse animation but remove spring overshoot. */
    public void morphToNoBounce(int newMode) {
        morphTo(newMode, false);
        wS.configure(1.0f, PRESS_W_K);
        hS.configure(1.0f, PRESS_H_K);
        rS.configure(1.0f, PRESS_H_K);
        kick();
    }

    /** v8.22: notification close to the hidden dot — animated, critically damped. */
    public void hideNoBounce() {
        hide(false);
        wS.configure(1.0f, PRESS_W_K);
        hS.configure(1.0f, PRESS_H_K);
        rS.configure(1.0f, PRESS_H_K);
        kick();
    }

    public void snapHidden() {
        prevMode = MODE_HIDDEN;
        mode = MODE_HIDDEN;
        resetMarquee();
        resetCollapsePhases();
        settlePending = false;   // snapped, no settle callback to deliver
        float d = IslandSpec.HC * DOT;
        cardW = cardH = d;
        radius = d / 2f;
        snapSprings();
        prevFade.snap(0f);
        targetFade.snap(0f);
        pressActive = false;
        pressS.snap(0f);
        pendingCollapseContentIn = false;
        clearMotionBlur();
        invalidate();
    }

    /** v8.21: jump to a visible mode with no spring/fade animation (notification instant close). */
    public void snapToMode(int newMode) {
        if (newMode == MODE_HIDDEN) {
            snapHidden();
            return;
        }
        prevMode = MODE_HIDDEN;
        mode = newMode;
        resetMarquee();
        resetCollapsePhases();
        settlePending = false;
        cardTop = IslandSpec.cardTop();
        float[] t = targetGeometry(newMode);
        cardW = Math.max(1f, t[0]);
        cardH = Math.max(1f, t[1]);
        radius = Math.max(0.5f, Math.min(t[2], Math.min(cardW, cardH) / 2f));
        snapSprings();
        prevFade.snap(0f);
        targetFade.snap(1f);
        pressActive = false;
        pressS.snap(0f);
        pendingCollapseContentIn = false;
        clearMotionBlur();
        invalidate();
    }

    private void snapSprings() {
        wS.snap(cardW);
        hS.snap(cardH);
        rS.snap(radius);
    }

    /** v3.9: restart the "wait for expand to settle, then auto-scroll overflowing text" cycle. */
    private void resetMarquee() {
        expandedSettledAtMs = 0L;
        marqueeNeeded = false;
        musicTitleOverflowPx = 0f;
        notifTitleOverflowPx = 0f;
        notifBodyOverflowPx = 0f;
    }

    /** Slow marquee position (px) for a text overflow; pauses at the end, then loops. */
    private float marqueeOffset(float overflowPx, long nowMs) {
        if (expandedSettledAtMs == 0L || overflowPx <= 0.5f) return 0f;
        long t = nowMs - expandedSettledAtMs - MARQUEE_DELAY_MS;
        if (t <= 0L) return 0f;
        float density = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 2.75f;
        float speed = MARQUEE_SPEED_DP * density;                 // px / s
        float travelSec = overflowPx / speed;
        float cycleSec = travelSec + MARQUEE_END_PAUSE_MS / 1000f;
        float pos = (t / 1000f) % cycleSec;
        return Math.min(overflowPx, pos * speed);
    }

    /**
     * v3.7: apply a RenderEffect blur whose radius tracks the spring velocity. Fast expand/collapse
     * therefore looks like a soft motion-blur pass, and the image becomes crisp as soon as the
     * springs stop (velocity is snapped to 0 by {@link Spring#step(float)}).
     */
    private void updateMotionBlur() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (suppressMotionBlur) {
            clearMotionBlur();
            return;
        }
        float v = Math.abs(wS.velocity);
        v = Math.max(v, Math.abs(hS.velocity));
        v = Math.max(v, Math.abs(rS.velocity));
        float density = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 2.75f;
        float radius = Math.min(MAX_BLUR_DP * density, v * BLUR_PER_VELOCITY);
        if (radius < 0.8f) radius = 0f;
        if (Math.abs(radius - appliedBlurRadiusPx) < BLUR_EPSILON_PX) return;
        appliedBlurRadiusPx = radius;
        if (radius <= 0f) {
            setRenderEffect(null);
        } else {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    private void clearMotionBlur() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (appliedBlurRadiusPx == 0f) return;
        appliedBlurRadiusPx = 0f;
        setRenderEffect(null);
    }

    /** alpha of the currently visible content (used when a new transition starts) */
    private float visibleContentAlpha() {
        return Math.max(prevFade.value(), targetFade.value());
    }

    public void resumeRendering() {
        flushSettleIfNeeded();
        if (mode != MODE_HIDDEN || prevMode != MODE_HIDDEN) kick();
        invalidate();
    }

    /**
     * R-04: re-delivers a settle callback that would otherwise be lost when the window is
     * detached and re-attached while the springs are already inside the settle gate.
     * The flag is cleared synchronously, so it can never be delivered twice for one transition;
     * the callback itself is posted because this runs from attach/rebind paths.
     */
    private void flushSettleIfNeeded() {
        if (!settlePending || callback == null) return;
        if (!wS.settled() || !hS.settled() || !rS.settled()) return;
        final int generation = settleGeneration;
        settlePending = false;
        post(() -> {
            // A newer transition started while this callback was queued: it will deliver its own
            // settle callback, so drop the stale one instead of delivering twice.
            if (generation == settleGeneration && callback != null) callback.onIslandSettled();
        });
    }

    private void kick() {
        if (!frameScheduled) {
            frameScheduled = true;
            lastFrameNanos = 0L;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (choreographer == null || getWindowToken() == null) {
                // Not drawable yet. Do not stall forever: lastFrameNanos is reset so the next
                // kick() (e.g. onAttachedToWindow / resumeRendering) starts with a fresh delta.
                lastFrameNanos = 0L;
                springsWereMoving = false;
                return;
            }
            float dt;
            if (lastFrameNanos == 0L) {
                dt = 1f / 60f;
            } else {
                dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            }
            dt = Math.max(0f, Math.min(DT_MAX, dt));
            lastFrameNanos = frameTimeNanos;
            long nowMs = SystemClock.uptimeMillis();

            boolean springsMoving = false;
            springsMoving |= wS.step(dt);
            springsMoving |= hS.step(dt);
            springsMoving |= rS.step(dt);
            boolean pressMoving = pressS.step(dt);

            // v5.9: two-stage collapse — phase 1 has reached the content-less empty pill; now
            // relayout that pill to the compact pill width and fade its content in immediately
            // (starting the fade only after the relayout spring settles left an empty pill on
            // screen long enough to look like a missing-content bug). A timeout also forces this
            // transition in case a window detachment/frame stall prevented the spring from settling.
            boolean phase1TimedOut = collapsePhased && collapseStage == 1
                    && nowMs - collapsePhaseStartMs > 650L;
            if ((!springsMoving || phase1TimedOut) && collapsePhased && collapseStage == 1) {
                collapseStage = 2;
                applyRelayoutAxes();
                float[] t = targetGeometry(collapseCompactMode);
                wS.setTarget(t[0]);
                hS.setTarget(t[1]);
                rS.setTarget(t[2]);
                // Fade the compact content in while the pill relayouts, so it is never left empty.
                targetFade.start(0f, 1f, COLLAPSE_IN_MS, 0L, IslandEase.CONTENT);
                pendingCollapseContentIn = false;
                springsMoving = true;   // phase 2 is now in flight
            }

            if (!springsMoving && pendingCollapseContentIn) {
                pendingCollapseContentIn = false;
                targetFade.start(0f, 1f, COLLAPSE_IN_MS, 0L, IslandEase.CONTENT);
            }
            boolean fadesMoving = false;
            fadesMoving |= prevFade.update(nowMs);
            fadesMoving |= targetFade.update(nowMs);
            if (!springsMoving && !fadesMoving && isExpanded() && expandedSettledAtMs == 0L) {
                // the expand/collapse content has finished: allow the marquee to start after a
                // short pause (the layout itself was already fixed for the final card size).
                expandedSettledAtMs = nowMs;
            }

            cardW = Math.max(1f, wS.value);
            cardH = Math.max(1f, hS.value);
            // v5.0: reference empty pill keeps the compact height; a press spring would otherwise
            // undershoot it (the video's height never drops below the compact pill height).
            if (collapseHeightClamp) {
                cardH = Math.max(cardH, IslandSpec.compactH());
            }
            radius = Math.max(0.5f, Math.min(rS.value, Math.min(cardW, cardH) / 2f));
            updateMotionBlur();
            invalidate();

            if (!springsMoving) {
                boolean allSettled = wS.settled() && hS.settled() && rS.settled();
                if (settlePending && allSettled) {
                    // exactly one callback per started transition, even if no frame happened to
                    // observe the "moving" state (e.g. the window was re-attached mid-animation)
                    if (callback != null) {
                        settlePending = false;
                        resetCollapsePhases();
                        callback.onIslandSettled();
                    } else {
                        resetCollapsePhases();
                    }
                } else if (springsWereMoving && allSettled && callback != null) {
                    callback.onIslandSettled();
                }
            }
            springsWereMoving = springsMoving;

            if (springsMoving || fadesMoving || pressMoving) {
                frameScheduled = true;
                choreographer.postFrameCallback(this);
                return;
            }
            if (ambientAnimationNeeded(nowMs)) {
                // the playing waveform or the one-shot fast-charge glow still needs frames
                frameScheduled = true;
                choreographer.postFrameCallback(this);
                return;
            }
            // Visually static: stop the frame loop. The ticking texts reschedule their own
            // once-per-second redraw, and lastFrameNanos is reset so a later kick starts clean.
            lastFrameNanos = 0L;
            scheduleSlowRefreshIfNeeded();
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // An animation may have been started while getWindowToken() was still null; make sure
        // the render loop runs again now that the window is really attached.
        lastFrameNanos = 0L;
        if (isVisible()) kick();
        // R-04: if the springs already settled while detached, the settle callback was lost.
        flushSettleIfNeeded();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
        removeCallbacks(slowRefreshTask);
        clearMotionBlur();
        slowRefreshPosted = false;
        frameScheduled = false;
        lastFrameNanos = 0L;
        springsWereMoving = false;
    }

    /** True while an ambient (non-spring) animation still needs a frame every vsync. */
    private boolean ambientAnimationNeeded(long nowMs) {
        // v3.9: expanded card with overflowing text keeps its frame loop alive for the marquee.
        if (isExpanded() && marqueeNeeded) return true;
        if (mode == MODE_MUSIC_COMPACT && music.playing) return true;
        // expanded card: the seek progress + playback clock keep moving while playing
        if (mode == MODE_MUSIC_EXPANDED && music.playing) return true;
        return (charging.fast || charging.superFast) && chargeGlowWasActive
                && nowMs - chargeGlowStart < CHARGE_GLOW_MS;
    }

    /**
     * Schedules a single delayed redraw for the text that changes once per second (timer digits,
     * playback clock), so those keep ticking without keeping the 60 fps loop alive.
     */
    private void scheduleSlowRefreshIfNeeded() {
        if (slowRefreshPosted || !isVisible()) return;
        long delay = slowRefreshDelayMs();
        if (delay <= 0L) return;
        slowRefreshPosted = true;
        postDelayed(slowRefreshTask, delay);
    }

    /** Delay until the next second-boundary of the currently displayed text, or -1 if none. */
    private long slowRefreshDelayMs() {
        int m = mode == MODE_HIDDEN ? prevMode : mode;
        if ((m == MODE_TIMER_COMPACT || m == MODE_TIMER_EXPANDED)
                && timer.running && timer.when > 0) {
            long wallNow = System.currentTimeMillis();
            long raw = timer.countDown ? timer.when - wallNow : wallNow - timer.when;
            if (raw < 0L) raw = 0L;
            if (timer.countDown && raw == 0L) return -1L;   // finished countdown: digits frozen
            return 1000L - (raw % 1000L) + 2L;
        }
        if (m == MODE_MUSIC_EXPANDED && music.playing && music.durationMs > 0) {
            long pos = music.position(SystemClock.elapsedRealtime());
            if (pos < music.durationMs) return 1000L - (pos % 1000L) + 2L;
        }
        return -1L;
    }

    // ------------------------------------------------------------------ touch
    /**
     * v3.2: taps outside the island. ACTION_OUTSIDE is only delivered once the service sets
     * FLAG_WATCH_OUTSIDE_TOUCH on the overlay window.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (mode != MODE_HIDDEN && callback != null) callback.onIslandOutsideTap();
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    /** Resets every per-gesture flag (gesture end / cancellation / hidden state). */
    private void resetTouchGesture() {
        touchTracking = false;
        touchTapCandidate = false;
        touchStartedInside = false;
        swipeDismiss = false;
        endPress();
    }

    private void beginPress() {
        if (pressActive) return;
        pressActive = true;
        pressS.setTarget(1f);
        kick();
    }

    private void endPress() {
        if (!pressActive) return;
        pressActive = false;
        pressS.setTarget(0f);
        kick();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        // While hidden (including the collapse animation) the island must not swallow touches.
        if (mode == MODE_HIDDEN) {
            resetTouchGesture();
            return false;
        }
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float left = getWidth() / 2f - cardW / 2f;
            float top = cardTop;
            boolean inside = e.getX() >= left - 12f && e.getX() <= left + cardW + 12f
                    && e.getY() >= top - 12f && e.getY() <= top + cardH + 12f;
            if (!inside) {
                // v3.2: tapping the transparent part of the expanded (window-filling) island
                // collapses it. Compact states keep letting the touch through, as before.
                if (isExpanded() && callback != null) {
                    resetTouchGesture();
                    touchTracking = true;   // swallow the rest of this gesture too
                    callback.onIslandOutsideTap();
                    return true;
                }
                return false;
            }
            touchDownX = e.getX();
            touchDownY = e.getY();
            touchTapCandidate = true;
            touchTracking = true;
            touchStartedInside = true;
            swipeDismiss = false;
            // v6.1: the always-on idle capsule gets a small "press grows" feedback.
            if (mode == MODE_IDLE) beginPress();
            return true;
        }
        if (!touchTracking) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            if (touchStartedInside && !swipeDismiss) {
                float dx = e.getX() - touchDownX;
                float dy = touchDownY - e.getY();   // positive = moving up
                // v3.2: upward, mostly vertical drag on the card = "swipe up to collapse"
                if (dy > swipeDismissPx && Math.abs(dx) <= Math.abs(dy) * 1.2f) {
                    swipeDismiss = true;
                    touchTapCandidate = false;
                    return true;
                }
            }
            // A drag that starts on the island (e.g. pulling the status bar down) is not a tap.
            if (Math.abs(e.getX() - touchDownX) > touchSlop
                    || Math.abs(e.getY() - touchDownY) > touchSlop) {
                touchTapCandidate = false;
            }
            return true;   // consume the stream: the gesture is handled here, not as a tap
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean swipe = swipeDismiss;
            boolean tap = touchTapCandidate;
            resetTouchGesture();
            if (swipe) {
                if (callback != null) callback.onIslandSwipeUp();
                return true;
            }
            if (!tap) return true;   // cancelled by the slop check: swallow, do not tap
            float left = getWidth() / 2f - cardW / 2f;
            float top = cardTop;
            boolean inside = e.getX() >= left - 12f && e.getX() <= left + cardW + 12f
                    && e.getY() >= top - 12f && e.getY() <= top + cardH + 12f;
            if (!inside) return true;
            float xf = cardW > 0 ? Math.max(0f, Math.min(1f, (e.getX() - left) / cardW)) : 0.5f;
            float yf = cardH > 0 ? Math.max(0f, Math.min(1f, (e.getY() - top) / cardH)) : 0.5f;
            if (callback != null) callback.onIslandTap(xf, yf);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            resetTouchGesture();
            return true;
        }
        return true;
    }

    // ------------------------------------------------------------------- draw
    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0) return;
        float cx = getWidth() / 2f;
        float left = cx - cardW / 2f;
        float top = cardTop;
        cardRect.set(left, top, left + cardW, top + cardH);

        clipPath.reset();
        clipPath.addRoundRect(cardRect, radius, radius, Path.Direction.CW);
        // v6.1: idle press feedback — scale the whole card slightly around its centre.
        float press = pressS.value;
        int pressSave = -1;
        if (Math.abs(press) > 0.001f) {
            float ps = 1f + 0.03f * press;
            pressSave = canvas.save();
            canvas.scale(ps, ps, cx, cardRect.centerY());
        }
        canvas.save();
        canvas.clipPath(clipPath);
        drawCardBackground(canvas);
        // v3.2: the ambient colour glow used to be painted before the clip, so the red/orange
        // bloom spilled outside the card onto the screen edge. It now lives inside the card clip.
        drawAmbientGlow(canvas, cx);
        if (prevMode != MODE_HIDDEN && prevFade.value() > 0.002f) {
            drawContent(canvas, prevMode, prevFade.value());
        }
        if (mode != MODE_HIDDEN && targetFade.value() > 0.002f) {
            drawContent(canvas, mode, targetFade.value());
        }
        canvas.restore();
        if (pressSave >= 0) canvas.restoreToCount(pressSave);

        afterDraw();
    }

    /** Ambient colour glow, clipped to the card so no colour can be drawn outside the island. */
    private void drawAmbientGlow(Canvas canvas, float cx) {
        int glowColor = ambientColor();
        if (Color.alpha(glowColor) <= 0) return;
        float rad = Math.min(cardW * 0.66f, Math.max(cardW, cardH) * 0.8f);
        glowPaint.setShader(radial2(ambientGlowShader, cx, cardRect.centerY(), rad,
                withAlpha(glowColor, 26), withAlpha(glowColor, 0), 0.55f, 1f));
        canvas.drawCircle(cx, cardRect.centerY(), rad, glowPaint);
        glowPaint.setShader(null);
    }

    /**
     * Called after every draw: restarts the continuous frame loop if an ambient animation still
     * needs it, otherwise schedules the next once-per-second text redraw.
     */
    private void afterDraw() {
        long nowMs = SystemClock.uptimeMillis();
        // v3.9: overflow flags are produced while drawing; they keep the 60fps loop alive only
        // for an expanded card that actually has something to scroll.
        marqueeNeeded = isExpanded()
                && (musicTitleOverflowPx > 0.5f || notifTitleOverflowPx > 0.5f
                    || notifBodyOverflowPx > 0.5f);
        if (ambientAnimationNeeded(nowMs)) {
            kick();   // no-op when frames are already running; bootstraps a stopped loop otherwise
            return;
        }
        if (!frameScheduled) scheduleSlowRefreshIfNeeded();
    }


    private int ambientColor() {
        switch (mode == MODE_HIDDEN ? prevMode : mode) {
            case MODE_CHARGE: return 0; // v8.25: reference charging card is pure black; no green wash
            case MODE_TIMER_COMPACT:
            case MODE_TIMER_EXPANDED: return IslandSpec.TIMER_GLOW;
            // v3.5: music cards are pure black at the top; the album-art colour is drawn only by
            // drawMusicExpandedGlow() (bottom bloom) / the waveform, never as a full-card wash.
            case MODE_MUSIC_COMPACT:
            case MODE_MUSIC_EXPANDED:
            // v3.3: notifications are pure black; no ambient colour bleeds onto the card.
            case MODE_NOTIF_COMPACT:
            case MODE_NOTIF_EXPANDED: return 0;
            default: return 0;
        }
    }

    private void drawCardBackground(Canvas canvas) {
        int m = mode == MODE_HIDDEN ? prevMode : mode;
        long now = SystemClock.uptimeMillis();
        // v3.4: every normal island state is drawn on an OPAQUE black card. The timer card used to fade to
        // ~62 % black at its bottom edge (a copy of the reference recording), which made the island
        // look semi-transparent; the user explicitly asked for an opaque island.
        canvas.drawRect(cardRect, cardPaint);
        switch (m) {
            case MODE_MUSIC_EXPANDED:
                drawMusicExpandedGlow(canvas);
                break;
            case MODE_TIMER_EXPANDED:
                drawTimerExpandedGlow(canvas);
                break;
            case MODE_CHARGE:
                drawChargingGlow(canvas, now);
                break;
            case MODE_TIMER_COMPACT:
                drawTimerCompactGlow(canvas);
                break;
            case MODE_MUSIC_COMPACT:
                drawMusicCompactGlow(canvas, now);
                break;
            case MODE_NOTIF_COMPACT:
            case MODE_NOTIF_EXPANDED:
                // v3.3: pure black card - no ambient gradient, no timer veil.
                break;
            default:
                break;
        }
    }

    // --------------------------------------------------------------- geometry
    private float textSize(float h, float ratio) { return h * ratio; }

    private float baselineFor(Paint p, float centerY) {
        p.getFontMetrics(fontMetrics);   // reuse the same FontMetrics object every frame
        return centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
    }

    private void drawIcon(Canvas canvas, Path icon, float cx, float cy, float size, int color) {
        canvas.save();
        float s = size / 24f;
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(s, s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(icon, paint);
        canvas.restore();
    }

    /**
     * v6.5: like {@link #drawIcon}, but centres the path's actual bounds on (cx,cy). Material
     * glyphs are not always centred in their 24x24 viewport (the music note is offset by
     * ~1.5dp right / 1dp up), which made the note look off-centre inside the red app badge.
     */
    /** v6.5: centre a Material glyph by its actual path bounds (MusicNote is not centred). */
    private void drawIconCentered(Canvas canvas, Path icon, float cx, float cy, float size, int color) {
        icon.computeBounds(tmpRect, true);
        float s = size / 24f;
        canvas.save();
        canvas.translate(cx - tmpRect.centerX() * s, cy - tmpRect.centerY() * s);
        canvas.scale(s, s);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(icon, paint);
        canvas.restore();
    }

    /** v6.8: draw the user-supplied 龍 artwork as the circular app badge. */
    private Bitmap musicBadgeBitmap() {
        if (musicBadgeBitmap == null || musicBadgeBitmap.isRecycled()) {
            try {
                musicBadgeBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.music_badge);
            } catch (Throwable ignored) {
                musicBadgeBitmap = null;
            }
        }
        return musicBadgeBitmap;
    }

    private void drawMusicBadge(Canvas canvas, float cx, float cy, float d, float alpha) {
        Bitmap b = musicBadgeBitmap();
        if (b == null || b.isRecycled()) {
            // Fallback to the old coral badge + note if the drawable cannot be decoded.
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(IslandSpec.APP_ICON_RED, (int) (255 * alpha)));
            canvas.drawCircle(cx, cy, d / 2f, paint);
            drawIconCentered(canvas, ICON_NOTE, cx, cy, d * 0.66f,
                    withAlpha(0xFFFFFFFF, (int) (255 * alpha)));
            return;
        }
        int save = canvas.save();
        tmpPath.reset();
        tmpPath.addCircle(cx, cy, d / 2f, Path.Direction.CW);
        canvas.clipPath(tmpPath);
        tmpRect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setFilterBitmap(true);
        paint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(b, null, tmpRect, paint);
        paint.setAlpha(255);
        paint.setFilterBitmap(false);
        canvas.restoreToCount(save);
    }

    // ================================================================ charging
    private void drawChargingGlow(Canvas canvas, long now) {
        // v8.26: fast/super-fast visual states are disabled.
        chargeGlowWasActive = false;
        return;
    }

    /** Formats the charging percentage only when level / precision / speed actually changed. */
    private String chargingPercentText() {
        IslandData.Charging c = charging;
        if (percentText == null || percentLevel != c.level
                || Float.compare(percentPrecise, c.preciseLevel) != 0
                || percentFast != c.fast || percentSuperFast != c.superFast) {
            percentText = c.percentText();
            percentLevel = c.level;
            percentPrecise = c.preciseLevel;
            percentFast = c.fast;
            percentSuperFast = c.superFast;
        }
        return percentText;
    }

    private void drawCharging(Canvas canvas, float alpha) {
        float h = cardH;
        float cy = cardRect.centerY();
        textPaint.setTypeface(bold);
        textPaint.setColor(IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));

        // label at the left (v8.25: measured from the 1920x432 reference: cap 55/167 h,
        // advance ≈60.75 px = 0.364 h, left inset 57 px = 0.341 h)
        textPaint.setTextSize(textSize(h, 0.364f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(charging.label(), cardRect.left + h * 0.318f, baselineFor(textPaint, cy), textPaint);

        // percentage (right edge 1289, card right 1464 -> 1.048 h from the right)
        String pct = chargingPercentText();
        textPaint.setTextSize(textSize(h, 0.360f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(pct, cardRect.right - h * 1.034f, baselineFor(textPaint, cy), textPaint);

        // bolt icon: v8.26 always the normal single green bolt
        float boltCx = cardRect.right - h * 0.560f;
        float boltCy = cy;
        int accent = charging.accent();
        // ICON_BOLT's path fills 18/24 of its box; 0.575 h -> measured glyph height ≈0.431 h.
        drawIcon(canvas, ICON_BOLT, boltCx, boltCy, h * 0.575f,
                withAlpha(accent, (int) (255 * alpha)));
    }

    // ================================================================== timer
    private void drawTimerCompactGlow(Canvas canvas) {
        float cx = cardRect.left + cardH * 0.60f;
        float cy = cardRect.centerY();
        glowPaint.setShader(radial3(timerCompactGlowShader, cx, cy, cardH * 0.85f,
                0x66F79C00, 0x22F79C00, 0x00F79C00, 0.06f, 0.5f, 1f));
        canvas.drawRect(cardRect, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawTimerCompact(Canvas canvas, float alpha) {
        updateTimerDigits(System.currentTimeMillis());
        float h = cardH;
        float cy = cardRect.centerY();
        float iconSize = h * 0.63f;
        float iconCx = cardRect.left + h * 0.313f + iconSize / 2f;
        drawStopwatchIcon(canvas, iconCx, cy, iconSize, alpha);
        // countdown / elapsed digits, right aligned
        textPaint.setTypeface(bold);
        textPaint.setColor(IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));
        textPaint.setTextSize(textSize(h, 0.392f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(timer.digits, cardRect.right - h * 0.42f, baselineFor(textPaint, cy), textPaint);
    }

    private void drawStopwatchIcon(Canvas canvas, float cx, float cy, float size, float alpha) {
        float half = size / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(IslandSpec.TIMER_ICON_BG, (int) (255 * alpha)));
        tmpRect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(tmpRect, size * 0.30f, size * 0.30f, paint);

        float glyph = size * 0.42f;
        int accent = withAlpha(IslandSpec.TIMER_ACCENT, (int) (255 * alpha));
        // stopwatch crown
        paint.setColor(accent);
        tmpRect.set(cx - glyph * 0.20f, cy - glyph * 0.86f, cx + glyph * 0.20f, cy - glyph * 0.70f);
        canvas.drawRoundRect(tmpRect, glyph * 0.07f, glyph * 0.07f, paint);
        // face
        canvas.drawCircle(cx, cy - glyph * 0.10f, glyph * 0.60f, paint);
        // hand
        paint.setColor(withAlpha(0xFF3A2A10, (int) (255 * alpha)));
        float handW = glyph * 0.11f;
        tmpRect.set(cx - handW / 2f, cy - glyph * 0.58f, cx + handW / 2f, cy - glyph * 0.12f);
        canvas.drawRoundRect(tmpRect, handW / 2f, handW / 2f, paint);
    }

    private void drawTimerExpandedGlow(Canvas canvas) {
        float cx = cardRect.left + cardRect.width() * 0.11f;
        float cy = cardRect.centerY();
        glowPaint.setShader(radial3(timerExpandedGlowShader, cx, cy, cardRect.height() * 1.1f,
                0x88F79C00, 0x30F79C00, 0x00F79C00, 0.04f, 0.45f, 1f));
        canvas.drawRect(cardRect, glowPaint);
        // card bottom is slightly translucent/light in the recording
        glowPaint.setShader(null);
    }

    private void drawTimerExpanded(Canvas canvas, float alpha) {
        updateTimerDigits(System.currentTimeMillis());
        float cw = IslandSpec.expandedW();
        float ch = IslandSpec.timerExpandedH();
        float left = 0f;
        float top = 0f;
        int layerSave = canvas.save();
        canvas.translate(cardRect.left, cardRect.top);
        canvas.scale(Math.max(0.001f, cardW / cw), Math.max(0.001f, cardH / ch));
        float cy = top + ch / 2f;

        float iconSize = ch * 0.474f;
        float iconLeft = left + cw * 0.058f;
        drawStopwatchIcon(canvas, iconLeft + iconSize / 2f, cy, iconSize, alpha);

        float textLeft = iconLeft + iconSize + cw * 0.036f;
        textPaint.setTypeface(bold);
        textPaint.setColor(IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));
        textPaint.setTextSize(ch * 0.190f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        // SPEC timer card (card top 32, h 804): digits bbox top .346 h, bottom .478 h;
        // subtitle bbox top .543 h, bottom .670 h.
        float digitsBase = top + ch * 0.478f;
        canvas.drawText(timer.digits, textLeft, digitsBase, textPaint);
        textPaint.setTypeface(regular);
        textPaint.setTextSize(ch * 0.160f);
        textPaint.setColor(withAlpha(0xFF587185, (int) (255 * alpha)));
        canvas.drawText(timer.title, textLeft, top + ch * 0.670f, textPaint);

        // two round action buttons on the right (measured centers 0.731 / 0.885 of card width)
        float btnD = ch * 0.474f;
        float orangeCx = left + cw * TIMER_BUTTON_X[1];   // pause (orange)
        float greyCx = left + cw * TIMER_BUTTON_X[0];     // play/resume (grey)
        paint.setColor(withAlpha(IslandSpec.BTN_GREY, (int) (255 * alpha)));
        canvas.drawCircle(greyCx, cy, btnD / 2f, paint);
        drawPlayGlyph(canvas, greyCx + btnD * 0.04f, cy, btnD * 0.60f,
                withAlpha(IslandSpec.BTN_GREY_ICON, (int) (255 * alpha)));
        paint.setColor(withAlpha(IslandSpec.BTN_ORANGE, (int) (255 * alpha)));
        canvas.drawCircle(orangeCx, cy, btnD / 2f, paint);
        drawPauseGlyph(canvas, orangeCx, cy, btnD, withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        canvas.restoreToCount(layerSave);
    }

    private void drawPlayGlyph(Canvas canvas, float cx, float cy, float size, int color) {
        tmpPath.reset();
        tmpPath.moveTo(cx - size * 0.30f, cy - size * 0.44f);
        tmpPath.lineTo(cx + size * 0.50f, cy);
        tmpPath.lineTo(cx - size * 0.30f, cy + size * 0.44f);
        tmpPath.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(tmpPath, paint);
    }

    private void drawPauseGlyph(Canvas canvas, float cx, float cy, float btnD, int color) {
        paint.setColor(color);
        float barW = btnD * 0.075f;
        float barH = btnD * 0.34f;
        float off = btnD * 0.135f;
        tmpRect.set(cx - off - barW / 2f, cy - barH / 2f, cx - off + barW / 2f, cy + barH / 2f);
        canvas.drawRoundRect(tmpRect, barW * 0.28f, barW * 0.28f, paint);
        tmpRect.set(cx + off - barW / 2f, cy - barH / 2f, cx + off + barW / 2f, cy + barH / 2f);
        canvas.drawRoundRect(tmpRect, barW * 0.28f, barW * 0.28f, paint);
    }

    // ================================================================== music
    private void drawMusicCompactGlow(Canvas canvas, long now) {
        // reference shows a plain black capsule with no ambient gradient in compact state
    }

    private void drawMusicCompact(Canvas canvas, float alpha) {
        float h = cardH;
        float artSize = h * 0.663f;
        float artLeft = cardRect.left + h * 0.29f;
        float artTop = cardRect.centerY() - artSize / 2f - h * 0.015f;
        drawArtwork(canvas, artLeft, artTop, artSize, alpha, 0.22f);

        float waveW = h * 0.615f;
        // v6.2: WAVE_RANGE values are fractions of the *pill height* (reference max bar =
        // 0.44 * pill h). The old code passed 0.44*h here, which halved the visible amplitude
        // twice; pass the card height so the bars actually reach the reference swing.
        float waveH = h;
        float waveRight = cardRect.right - h * 0.31f;
        drawWaveform(canvas, waveRight - waveW, cardRect.centerY() - waveH / 2f, waveW, waveH, alpha);
    }

    private boolean chargeGlowWasActive;
    private long chargeGlowStart;

    // measured per-bar ranges (fraction of compact height): {min, max}, and per-bar phase offsets
    private static final float[][] WAVE_RANGE = {{0.100f, 0.169f}, {0.116f, 0.406f}, {0.142f, 0.438f},
            {0.063f, 0.311f}, {0.084f, 0.185f}};
    private static final float[] WAVE_FREQ_A = {6.3f, 8.7f, 5.1f, 9.4f, 7.0f};
    private static final float[] WAVE_FREQ_B = {2.1f, 3.3f, 4.7f, 1.7f, 3.9f};
    private static final float[] WAVE_PHASE_A = {0.0f, 1.3f, 2.1f, 3.4f, 4.6f};
    private static final float[] WAVE_PHASE_B = {1.1f, 2.8f, 0.7f, 4.0f, 2.3f};
    private static final float[] WAVE_FREQ_C = {0.73f, 1.13f, 0.91f, 1.37f, 0.61f};
    private static final float[] WAVE_PHASE_C = {0.4f, 2.2f, 4.4f, 1.6f, 3.2f};
    private static final float[] WAVE_FREQ_D = {11.3f, 13.7f, 10.1f, 14.9f, 12.7f};
    private static final float[] WAVE_PHASE_D = {2.9f, 0.8f, 3.7f, 1.4f, 5.1f};
    /** v7.7: global animation-rate multiplier for the compact music waveform. */
    private static final float WAVE_TIME_SCALE = 1.7f;

    private void drawWaveform(Canvas canvas, float left, float top, float w, float h, float alpha) {
        int bars = 5;
        long now = SystemClock.uptimeMillis();
        float t = (now - startUptime) / 1000f * WAVE_TIME_SCALE;
        float slot = w / bars;
        float stroke = slot * 0.52f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        // v7.1: waveform tint follows the current album art's extracted accent colour.
        int waveLeft = blendColor(music.accent, 0xFFFFFFFF, 0.38f);
        int waveRight = blendColor(music.accent, 0xFFFFFFFF, 0.08f);
        paint.setShader(linear2(waveformShader, left, 0, left + w, 0,
                withAlpha(waveLeft, (int) (255 * alpha)),
                withAlpha(waveRight, (int) (255 * alpha))));
        float cy = top + h / 2f;
        for (int i = 0; i < bars; i++) {
            float[] range = WAVE_RANGE[i];
            float osc;
            if (music.playing) {
                // v7.1: three incommensurate per-bar sines plus a fourth high-frequency jitter,
                // with a drifting phase and a slow per-bar amplitude envelope. The bars now
                // wander independently instead of only differing by a fixed phase.
                float drift = 0.34f * (float) Math.sin(t * 0.41f + i * 1.7f);
                osc = 0.5f
                        + 0.21f * (float) Math.sin(t * WAVE_FREQ_A[i] + WAVE_PHASE_A[i] + drift)
                        + 0.17f * (float) Math.sin(t * WAVE_FREQ_B[i] + WAVE_PHASE_B[i])
                        + 0.10f * (float) Math.sin(t * WAVE_FREQ_C[i] + WAVE_PHASE_C[i])
                        + 0.07f * (float) Math.sin(t * WAVE_FREQ_D[i] + WAVE_PHASE_D[i]);
                float env = 0.72f + 0.28f * (float) Math.sin(
                        t * 0.53f + i * 1.7f + WAVE_PHASE_C[i]);
                osc = 0.5f + (osc - 0.5f) * env;
                if (osc < 0f) osc = 0f;
                if (osc > 1f) osc = 1f;
            } else {
                osc = 0f;
            }
            float bar = range[0] + (range[1] - range[0]) * osc;
            float half = Math.max(stroke / 2f, h / 2f * bar);
            float x = left + slot * i + slot / 2f;
            canvas.drawLine(x, cy - half, x, cy + half, paint);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMusicExpandedGlow(Canvas canvas) {
        int accent = music.accent;
        // Reference comparison: the expanded music card is pure BLACK at the top (sampled
        // y510..720 = RGB 0,0,0 in the "别人的" panel); only the bottom carries the artwork-derived
        // bloom. Keep that shape — a full-card artwork wash looked softer but was not 1:1.
        glowPaint.setShader(linear3(musicBandShader, 0, cardRect.bottom, 0, cardRect.top + cardH * 0.34f,
                withAlpha(accent, 190), withAlpha(accent, 60), 0x00000000,
                0f, 0.45f, 1f));
        canvas.drawRect(cardRect, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawMusicExpanded(Canvas canvas, float alpha) {
        // v3.9: content is laid out for the FINAL expanded card and anchored to its final centre,
        // so during the spring expand/collapse the text does not scale, re-wrap or jump. The card
        // clip simply reveals more/less of the fixed layout.
        // v5.5: content must be GLUED to the island, not pre-positioned on screen and merely
        // revealed by the expanding clip. Lay it out in final-card coordinates, then translate +
        // scale that layer to the *current* card rect, so cover/text/buttons move and grow with
        // the island during expand/collapse (the reference's "stuck on the island" feel).
        float cw = IslandSpec.expandedW();
        float ch = IslandSpec.musicExpandedH();
        float left = 0f;
        float top = 0f;
        float right = left + cw;
        long nowMs = SystemClock.uptimeMillis();
        int layerSave = canvas.save();
        canvas.translate(cardRect.left, cardRect.top);
        canvas.scale(Math.max(0.001f, cardW / cw), Math.max(0.001f, cardH / ch));

        float padL = cw * 0.0687f;
        float artSize = cw * 0.136f;
        float artTop = top + ch * 0.150f;
        // Reference: cover corner radius 26/282 ~= 0.09 of the cover size (was too round before).
        drawArtwork(canvas, left + padL, artTop, artSize, alpha, 0.10f);

        float titleLeft = left + padL + artSize + cw * 0.0337f;
        float titleSize = ch * 0.127f;
        float clipMaxW = right - cw * 0.10f - titleLeft;
        textPaint.setTypeface(bold);
        textPaint.setColor(withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        textPaint.setTextSize(titleSize);
        textPaint.setTextAlign(Paint.Align.LEFT);
        // Reference music card: title cap top = (186-21)/929 = 0.178 h_card, cap H = 85/929 = 0.091.
        float titleY = top + ch * 0.178f + titleSize * 0.72f;
        musicTitleOverflowPx = Math.max(0f, textPaint.measureText(music.title) - clipMaxW);
        if (musicTitleOverflowPx > 0.5f) {
            int save = canvas.save();
            canvas.clipRect(titleLeft, titleY - titleSize * 1.25f,
                    titleLeft + clipMaxW, titleY + titleSize * 0.45f);
            canvas.translate(-marqueeOffset(musicTitleOverflowPx, nowMs), 0f);
            canvas.drawText(music.title, titleLeft, titleY, textPaint);
            canvas.restoreToCount(save);
        } else {
            canvas.drawText(clipTextCached(titleClipCache, music.title, textPaint, clipMaxW),
                    titleLeft, titleY, textPaint);
        }
        textPaint.setTypeface(regular);
        textPaint.setTextSize(ch * 0.100f);
        textPaint.setColor(withAlpha(0xFF8E8E8E, (int) (255 * alpha)));
        // SPEC: artist bbox top (317-21)/929 = 0.319 h_card.
        canvas.drawText(clipTextCached(artistClipCache, music.artist, textPaint, clipMaxW),
                titleLeft, titleY + ch * 0.125f, textPaint);

        // seek row
        float seekY = top + ch * 0.542f;
        float timeSize = ch * 0.100f;
        textPaint.setTypeface(regular);
        textPaint.setTextSize(timeSize);
        textPaint.setColor(withAlpha(0xC0C0C0, (int) (255 * alpha)));
        textPaint.setTextAlign(Paint.Align.LEFT);
        long pos = music.position(SystemClock.elapsedRealtime());
        if (music.durationMs > 0) pos = Math.min(pos, music.durationMs);
        updateMusicTimes(pos);
        canvas.drawText(elapsedText, left + padL, baselineFor(textPaint, seekY), textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(totalText, right - padL, baselineFor(textPaint, seekY), textPaint);

        // v3.5: the reference track starts AFTER the left time label and stops BEFORE the right one.
        // With our (slightly wider) time font the fixed 0.161..0.844 range overlapped both labels,
        // so the track is clamped between the measured label widths.
        float barH = ch * 0.011f;
        float minLeft = left + cw * 0.161f;
        float maxRight = left + cw * 0.844f;
        float labelGap = cw * 0.018f;
        textPaint.setTextSize(timeSize);
        float elapsedW = textPaint.measureText(elapsedText);
        float totalW = textPaint.measureText(totalText);
        float barLeft = Math.max(minLeft, left + padL + elapsedW + labelGap);
        float barRight = Math.min(maxRight, right - padL - totalW - labelGap);
        if (barRight - barLeft < cw * 0.12f) {
            // Extremely narrow screen / very long time strings: fall back to the reference range.
            barLeft = minLeft;
            barRight = maxRight;
        }
        paint.setColor(withAlpha(0x50FFFFFF, (int) (255 * alpha)));
        paint.setShader(null);
        tmpRect.set(barLeft, seekY - barH / 2f, barRight, seekY + barH / 2f);
        canvas.drawRoundRect(tmpRect, barH / 2f, barH / 2f, paint);
        float frac = music.durationMs > 0 ? Math.min(1f, pos / (float) music.durationMs) : 0f;
        paint.setColor(withAlpha(IslandSpec.MUSIC_SEEK_FILL, (int) (255 * alpha)));
        tmpRect.set(barLeft, seekY - barH / 2f, barLeft + (barRight - barLeft) * frac, seekY + barH / 2f);
        canvas.drawRoundRect(tmpRect, barH / 2f, barH / 2f, paint);
        // v6.6: visible round thumb at the playback position (the old pure line read as a
        // straight bar with no progress indicator). Keep it inside the rounded track ends.
        float thumbR = ch * 0.020f;
        if (thumbR > barH * 0.5f) {
            float thumbX = barLeft + (barRight - barLeft) * frac;
            thumbX = Math.max(barLeft + thumbR, Math.min(barRight - thumbR, thumbX));
            // soft dark halo so the white thumb stays visible on both the dark track and the
            // light filled part.
            paint.setColor(withAlpha(0x66000000, (int) (140 * alpha)));
            canvas.drawCircle(thumbX, seekY, thumbR + ch * 0.005f, paint);
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (235 * alpha)));
            canvas.drawCircle(thumbX, seekY, thumbR, paint);
        }

        // action row: app icon, prev, pause/play, next, heart
        float ctrlY = top + ch * 0.766f;
        // v6.4: the 24dp Material glyphs only fill ~58% of their box; the old 0.115 h size made
        // the transport icons noticeably smaller than the reference. Larger control glyphs, a
        // slightly larger app badge, and the reference coral badge colour.
        float iconSize = ch * 0.175f;
        float heartSize = ch * 0.138f;
        float[] xs = MUSIC_BUTTON_X;   // shared with the service hit-testing (no drift)
        float appCx = left + cw * xs[0];
        float appD = ch * 0.162f;
        drawMusicBadge(canvas, appCx, ctrlY, appD, alpha);
        drawIcon(canvas, ICON_PREV, left + cw * xs[1], ctrlY, iconSize,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        drawIcon(canvas, music.playing ? ICON_PAUSE : ICON_PLAY, left + cw * xs[2], ctrlY, iconSize,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        drawIcon(canvas, ICON_NEXT, left + cw * xs[3], ctrlY, iconSize,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        drawIcon(canvas, ICON_HEART, left + cw * xs[4], ctrlY, heartSize,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        canvas.restoreToCount(layerSave);
    }

    private void drawArtwork(Canvas canvas, float left, float top, float size, float alpha, float cornerRatio) {
        tmpRect.set(left, top, left + size, top + size);
        float r = size * cornerRatio;
        int save = canvas.save();
        tmpPath.reset();
        tmpPath.addRoundRect(tmpRect, r, r, Path.Direction.CW);
        canvas.clipPath(tmpPath);
        if (music.artwork != null && !music.artwork.isRecycled()) {
            paint.setAlpha((int) (255 * alpha));
            // center-crop the bitmap
            Bitmap bmp = music.artwork;
            float scale = Math.max(size / bmp.getWidth(), size / bmp.getHeight());
            float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
            tmpRect.set(left + (size - dw) / 2f, top + (size - dh) / 2f,
                    left + (size + dw) / 2f, top + (size + dh) / 2f);
            canvas.drawBitmap(bmp, null, tmpRect, paint);
        } else if (music.appIcon != null && !music.appIcon.isRecycled()) {
            // v7.0: no embedded album art -> show the source app's launcher icon in the cover.
            paint.setAlpha((int) (255 * alpha));
            Bitmap bmp = music.appIcon;
            float scale = Math.max(size / bmp.getWidth(), size / bmp.getHeight());
            float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
            tmpRect.set(left + (size - dw) / 2f, top + (size - dh) / 2f,
                    left + (size + dw) / 2f, top + (size + dh) / 2f);
            canvas.drawBitmap(bmp, null, tmpRect, paint);
        } else {
            paint.setShader(linear2(artworkFallbackShader, left, top, left + size, top + size,
                    withAlpha(music.accent, (int) (255 * alpha)),
                    withAlpha(darken(music.accent, 0.5f), (int) (255 * alpha))));
            canvas.drawRect(tmpRect, paint);
            paint.setShader(null);
            drawIcon(canvas, ICON_NOTE, left + size / 2f, top + size / 2f, size * 0.45f,
                    withAlpha(0xFFFFFFFF, (int) (220 * alpha)));
        }
        canvas.restoreToCount(save);
    }

    // =============================================================== notification
    /**
     * v3.3: dynamic compact-pill width. The 12sp proportional label is measured only when the
     * label or the compact height changed; the result is clamped to the reference bounds.
     */
    public float notificationCompactWidth() {
        String label = notification.compactLabel();
        float hc = IslandSpec.HC;
        if (notifWidthValid && notifWidthHc == hc && label.equals(notifWidthLabel)) {
            return notifWidthValue;
        }
        float iconD = hc * NOTIF_COMPACT_ICON;
        float padLeft = hc * NOTIF_COMPACT_PAD_L;
        float gap = hc * NOTIF_COMPACT_GAP;
        float padRight = hc * NOTIF_COMPACT_PAD_R;
        textPaint.setTypeface(regular);
        textPaint.setTextSize(notifLabelSizePx);
        float textW = textPaint.measureText(label);
        float w = padLeft + iconD + gap + textW + padRight;
        w = Math.max(IslandSpec.notifCompactMinW(), Math.min(w, IslandSpec.notifCompactMaxW()));
        notifWidthLabel = label;
        notifWidthHc = hc;
        notifWidthValue = w;
        notifWidthValid = true;
        return w;
    }

    /** First glyph of the compact label, cached so the fallback icon never allocates per frame. */
    private String notificationLetter() {
        String label = notification.compactLabel();
        if (notifLetterSource == null || !notifLetterSource.equals(label)) {
            notifLetterSource = label;
            notifLetter = label.isEmpty() ? "?"
                    : new String(Character.toChars(label.codePointAt(0)));
        }
        return notifLetter;
    }

    /**
     * v3.6: dynamic height of the expanded notification card, computed from the real content
     * (reference popup_big is wrap_content). The service uses this for the overlay window size too.
     */
    public float notificationExpandedHeight() {
        updateNotificationExpandedMetrics();
        return notifExpCardH;
    }

    /**
     * Computes the popup_big-style layout metrics from the current notification: 60dp round icon on
     * the left, a text column (11sp appName/time, 15sp title, 13sp body <=6 lines, up to 3 action
     * buttons) with 4dp card padding + 16dp frame padding. Cached by content / card width / density.
     */
    private void updateNotificationExpandedMetrics() {
        float cw = IslandSpec.expandedW();
        float d = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 2.75f;
        String title = notification.title == null ? "" : notification.title;
        String text = notification.text == null ? "" : notification.text;
        int actions = notification.actionCount <= 0 ? 0
                : Math.min(NOTIF_MAX_ACTIONS, notification.actionCount);
        String key = title + '\u0001' + text + '\u0001' + actions + '\u0001' + cw + '\u0001' + d;
        if (notifExpansionKey != null && notifExpansionKey.equals(key)) return;
        notifExpansionKey = key;

        notifExpPad = 4f * d;              // CardView contentPadding
        notifExpFramePad = 16f * d;        // popup_big frame padding
        notifExpIconD = 60f * d;           // 60dp round icon
        notifExpHeaderSize = 11f * d;      // header / app name / time
        notifExpTitleSize = 15f * d;       // bold title
        notifExpBodySize = 13f * d;        // body text
        notifExpLineH = notifExpBodySize * 1.25f;
        notifExpHeaderGap = 6f * d;
        notifExpBodyGap = 6f * d;
        notifExpActionTextSize = 13f * d;
        notifExpActionGapTop = 16f * d;
        float actionPadV = 10f * d;
        notifExpActionH = notifExpActionTextSize * 1.25f + 2f * actionPadV;
        notifExpActionCount = actions;

        notifExpContentW = cw - 2f * notifExpPad - notifExpIconD - 2f * notifExpFramePad;
        // v5.8: move the whole content column right, keeping the same right margin by shrinking
        // the column by the shift.
        notifExpShift = cw * NOTIF_EXPANDED_SHIFT_OVER_W;
        notifExpContentW -= notifExpShift;
        if (notifExpContentW < cw * 0.30f) notifExpContentW = cw * 0.30f;
        textPaint.setTypeface(bold);
        textPaint.setTextSize(notifExpTitleSize);
        notifTitleOverflowPx = Math.max(0f,
                textPaint.measureText(title) - notifExpContentW);
        // v3.9: wrap the whole body (up to a sane limit) so the card can auto-scroll the remainder.
        wrapNotificationBody(text, notifExpContentW, notifExpBodySize, NOTIF_MAX_WRAP_LINES);
        notifBodyVisibleLineCount = Math.min(notifBodyLineCount, NOTIF_MAX_BODY_LINES);
        notifBodyOverflowPx = Math.max(0f,
                (notifBodyLineCount - notifBodyVisibleLineCount) * notifExpLineH);
        int lines = notifBodyVisibleLineCount;

        float contentH = notifExpHeaderSize * 1.25f + notifExpHeaderGap
                + notifExpTitleSize * 1.25f
                + (lines > 0 ? notifExpBodyGap + lines * notifExpLineH : 0f)
                + (actions > 0 ? notifExpActionGapTop + notifExpActionH : 0f);
        notifExpContentH = contentH;
        float frameContentH = contentH + 2f * notifExpFramePad;
        float h = Math.max(notifExpIconD + 2f * notifExpPad, frameContentH + 2f * notifExpPad);
        h = Math.max(IslandSpec.notifExpandedMinH(), Math.min(h, IslandSpec.notifExpandedMaxH()));
        notifExpCardH = h;
    }

    /**
     * v3.3/v3.6: action-button hit test in card fractions. Returns 0..2 for an action button, -1
     * when the tap is on the icon / app name / title / body (the service then uses contentIntent).
     */
    public int notificationActionAt(float xFraction, float yFraction) {
        if (mode != MODE_NOTIF_EXPANDED) return -1;
        updateNotificationExpandedMetrics();
        int actions = notifExpActionCount;
        if (actions <= 0) return -1;
        float cw = IslandSpec.expandedW();
        float ch = notifExpCardH;
        float d = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 2.75f;
        float left = cardRect.centerX() - cw / 2f;
        float top = cardRect.top;
        float contentLeft = left + notifExpPad + notifExpShift + notifExpIconD + notifExpFramePad;
        float contentTop = top + (ch - notifExpContentH) / 2f;
        float actionTop = contentTop + notifExpContentH - notifExpActionH;
        float absX = cardRect.left + xFraction * cardW;
        float absY = cardRect.top + yFraction * cardH;
        if (absY < actionTop || absY > actionTop + notifExpActionH) return -1;
        float gap = 12f * d;
        float btnW = (notifExpContentW - (actions - 1) * gap) / actions;
        for (int i = 0; i < actions; i++) {
            float bx = contentLeft + i * (btnW + gap);
            if (absX >= bx && absX <= bx + btnW) return i;
        }
        return -1;
    }

    /** App icon in a circular clip; falls back to a grey disc with the first glyph. */
    private void drawNotificationIcon(Canvas canvas, float cx, float cy, float diameter, float alpha) {
        tmpPath.reset();
        tmpPath.addCircle(cx, cy, diameter / 2f, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(tmpPath);
        Bitmap bmp = notification.icon;
        if (bmp != null && !bmp.isRecycled()) {
            paint.setAlpha((int) (255 * alpha));
            paint.setShader(null);
            float scale = Math.max(diameter / bmp.getWidth(), diameter / bmp.getHeight());
            float dw = bmp.getWidth() * scale;
            float dh = bmp.getHeight() * scale;
            tmpRect.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f);
            canvas.drawBitmap(bmp, null, tmpRect, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFF3A3A3C, (int) (255 * alpha)));
            paint.setShader(null);
            tmpRect.set(cx - diameter / 2f, cy - diameter / 2f,
                    cx + diameter / 2f, cy + diameter / 2f);
            canvas.drawRect(tmpRect, paint);
            textPaint.setTypeface(bold);
            textPaint.setTextSize(diameter * 0.5f);
            textPaint.setColor(withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(notificationLetter(), cx, baselineFor(textPaint, cy), textPaint);
        }
        canvas.restoreToCount(save);
    }

    private void drawNotificationCompact(Canvas canvas, float alpha) {
        float h = cardH;
        float cy = cardRect.centerY();
        float iconD = h * NOTIF_COMPACT_ICON;
        // v5.8: user asked for the notification content to sit further to the right.
        float shift = h * NOTIF_COMPACT_SHIFT_OVER_H;
        float iconCx = cardRect.left + h * NOTIF_COMPACT_PAD_L + shift + iconD / 2f;
        drawNotificationIcon(canvas, iconCx, cy, iconD, alpha);

        float textLeft = iconCx + iconD / 2f + h * NOTIF_COMPACT_GAP;
        float maxW = cardRect.right - h * NOTIF_COMPACT_PAD_R - textLeft;
        if (maxW <= 0f) return;
        textPaint.setTypeface(bold);
        textPaint.setColor(withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        textPaint.setTextSize(h * 0.36f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(clipTextCached(notifCompactClipCache, notification.compactLabel(),
                textPaint, maxW), textLeft, baselineFor(textPaint, cy), textPaint);
    }

    private void drawNotificationExpanded(Canvas canvas, float alpha) {
        updateNotificationExpandedMetrics();
        float cw = IslandSpec.expandedW();
        float ch = notifExpCardH;
        float d = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 2.75f;
        float left = 0f;
        float top = 0f;
        long nowMs = SystemClock.uptimeMillis();
        int layerSave = canvas.save();
        canvas.translate(cardRect.left, cardRect.top);
        canvas.scale(Math.max(0.001f, cardW / cw), Math.max(0.001f, cardH / ch));

        // 60dp round icon, vertically centred in the wrap_content card (popup_big container).
        // v5.8: icon + text column shifted right together.
        float iconLeft = left + notifExpPad + notifExpShift;
        float iconTop = top + (ch - notifExpIconD) / 2f;
        drawNotificationIcon(canvas, iconLeft + notifExpIconD / 2f, iconTop + notifExpIconD / 2f,
                notifExpIconD, alpha);

        float contentLeft = iconLeft + notifExpIconD + notifExpFramePad;
        float contentW = notifExpContentW;
        float contentTop = top + (ch - notifExpContentH) / 2f;
        float y = contentTop;

        // Header row: app name (left, 11sp grey) + time (right, 11sp grey).
        textPaint.setTypeface(regular);
        textPaint.setTextSize(notifExpHeaderSize);
        textPaint.setColor(fade(IslandSpec.NOTIF_TIME, alpha));
        textPaint.setTextAlign(Paint.Align.LEFT);
        String timeText = notification.timeText == null ? "" : notification.timeText;
        float headerBase = y + notifExpHeaderSize * 0.95f;
        float timeW = timeText.isEmpty() ? 0f : textPaint.measureText(timeText);
        float appMaxW = contentW - (timeW > 0f ? timeW + 12f * d : 0f);
        if (appMaxW > 0f) {
            canvas.drawText(clipTextCached(notifAppClipCache, notification.appName, textPaint, appMaxW),
                    contentLeft, headerBase, textPaint);
        }
        if (!timeText.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(timeText, contentLeft + contentW, headerBase, textPaint);
        }
        y += notifExpHeaderSize * 1.25f + notifExpHeaderGap;

        // Bold 15sp title: single line; if it overflows, it auto-scrolls after the expand settles.
        textPaint.setTypeface(bold);
        textPaint.setTextSize(notifExpTitleSize);
        textPaint.setColor(withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        textPaint.setTextAlign(Paint.Align.LEFT);
        float titleBase = y + notifExpTitleSize * 0.95f;
        if (notifTitleOverflowPx > 0.5f) {
            int save = canvas.save();
            canvas.clipRect(contentLeft, titleBase - notifExpTitleSize * 1.25f,
                    contentLeft + contentW, titleBase + notifExpTitleSize * 0.45f);
            canvas.translate(-marqueeOffset(notifTitleOverflowPx, nowMs), 0f);
            canvas.drawText(notification.title, contentLeft, titleBase, textPaint);
            canvas.restoreToCount(save);
        } else {
            canvas.drawText(clipTextCached(notifTitleClipCache, notification.title, textPaint, contentW),
                    contentLeft, titleBase, textPaint);
        }
        y += notifExpTitleSize * 1.25f;

        // 13sp body: the card shows NOTIF_MAX_BODY_LINES; the rest is auto-scrolled vertically.
        if (notifBodyVisibleLineCount > 0) {
            y += notifExpBodyGap;
            textPaint.setTypeface(regular);
            textPaint.setTextSize(notifExpBodySize);
            textPaint.setColor(fade(IslandSpec.TEXT_SECONDARY, alpha));
            textPaint.setTextAlign(Paint.Align.LEFT);
            int save = canvas.save();
            canvas.clipRect(contentLeft, y, contentLeft + contentW,
                    y + notifBodyVisibleLineCount * notifExpLineH + 2f);
            canvas.translate(0f, -marqueeOffset(notifBodyOverflowPx, nowMs));
            for (int i = 0; i < notifBodyLineCount; i++) {
                canvas.drawText(notifBodyLines[i], contentLeft,
                        y + notifExpBodySize * 0.95f + i * notifExpLineH, textPaint);
            }
            canvas.restoreToCount(save);
            y += notifBodyVisibleLineCount * notifExpLineH;
        }

        // Up to 3 action buttons (13sp text, 10dp vertical padding, 12dp gaps).
        int actions = notifExpActionCount;
        if (actions > 0) {
            y += notifExpActionGapTop;
            float gap = 12f * d;
            float btnW = (contentW - (actions - 1) * gap) / actions;
            float radius = notifExpActionH * 0.45f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(fade(IslandSpec.NOTIF_ACTION_BG, alpha));
            for (int i = 0; i < actions; i++) {
                float bx = contentLeft + i * (btnW + gap);
                tmpRect.set(bx, y, bx + btnW, y + notifExpActionH);
                canvas.drawRoundRect(tmpRect, radius, radius, paint);
                String actionTitle = notification.actionTitles[i];
                if (actionTitle == null || actionTitle.isEmpty()) continue;
                textPaint.setTypeface(regular);
                textPaint.setTextSize(notifExpActionTextSize);
                textPaint.setColor(withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(clipTextCached(notifActionClipCaches[i], actionTitle, textPaint,
                        btnW - 10f * d), bx + btnW / 2f,
                        baselineFor(textPaint, y + notifExpActionH / 2f), textPaint);
            }
        }
        canvas.restoreToCount(layerSave);
    }

    /**
     * v3.3/v3.6: wraps the notification body to at most {@code maxLines} lines (last line
     * ellipsized). Fully cached: re-wraps only when text / width / size / line budget change.
     */
    private void wrapNotificationBody(String text, float maxW, float textSize, int maxLines) {
        if (notifBodyText != null && notifBodyText.equals(text) && notifBodyMaxW == maxW
                && notifBodyTextSize == textSize && notifBodyBudget == maxLines) {
            return;
        }
        notifBodyText = text;
        notifBodyMaxW = maxW;
        notifBodyTextSize = textSize;
        notifBodyBudget = maxLines;
        notifBodyLineCount = 0;
        if (maxLines <= 0 || text.isEmpty()) return;
        textPaint.setTypeface(regular);
        textPaint.setTextSize(textSize);
        String flat = text.indexOf('\n') < 0 && text.indexOf('\r') < 0
                ? text : text.replace('\n', ' ').replace('\r', ' ');
        int len = flat.length();
        int start = 0;
        while (start < len && notifBodyLineCount < maxLines) {
            while (start < len && flat.charAt(start) == ' ') start++;
            if (start >= len) break;
            int line = notifBodyLineCount;
            if (line == maxLines - 1) {
                notifBodyLines[line] = clipText(flat.substring(start), textPaint, maxW);
                notifBodyLineCount++;
                break;
            }
            if (textPaint.measureText(flat, start, len) <= maxW) {
                notifBodyLines[line] = flat.substring(start);
                notifBodyLineCount++;
                break;
            }
            int lo = start + 1, hi = len;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (textPaint.measureText(flat, start, mid) <= maxW) lo = mid;
                else hi = mid - 1;
            }
            int end = lo;
            int lastSpace = -1;
            for (int i = end - 1; i > start; i--) {
                if (flat.charAt(i) == ' ') { lastSpace = i; break; }
            }
            if (lastSpace > start) end = lastSpace;
            notifBodyLines[line] = flat.substring(start, end).trim();
            notifBodyLineCount++;
            start = end;
        }
    }

    /** v8.47: system-volume HUD; icon + stream label + level, transient while volume keys change. */
    private void drawVolume(Canvas canvas, float alpha) {
        float h = cardH;
        float cy = cardRect.centerY();
        drawIcon(canvas, ICON_VOLUME, cardRect.left + h * 0.45f, cy, h * 0.44f,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        String label;
        switch (volumeStream) {
            case AudioManager.STREAM_MUSIC: label = "媒体音量"; break;
            case AudioManager.STREAM_RING: label = "铃声"; break;
            case AudioManager.STREAM_ALARM: label = "闹钟"; break;
            case AudioManager.STREAM_VOICE_CALL: label = "通话音量"; break;
            case AudioManager.STREAM_NOTIFICATION: label = "通知音量"; break;
            case AudioManager.STREAM_SYSTEM: label = "系统音量"; break;
            default: label = "音量"; break;
        }
        textPaint.setTypeface(bold);
        textPaint.setTextSize(textSize(h, 0.30f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));
        float textLeft = cardRect.left + h * 0.82f;
        canvas.drawText(label, textLeft, baselineFor(textPaint, cy), textPaint);

        // v8.51: no progress line — show the exact volume percentage instead.
        int pct = Math.round(volumeLevel * 100f / Math.max(1, volumeMax));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(textSize(h, 0.38f));
        textPaint.setColor(IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));
        canvas.drawText(pct + "%", cardRect.right - h * 0.30f,
                baselineFor(textPaint, cy), textPaint);
    }

    /** v8.31: ringer HUD from the reference video — black pill, bell, 响铃 / 静音. */
    private void drawRinger(Canvas canvas, float alpha) {
        float h = cardH;
        float cy = cardRect.centerY();
        int red = IslandSpec.RINGER_RED;
        float iconSize = h * 0.60f;
        float iconCx = cardRect.left + h * 0.49f;
        if (ringerSilent) {
            float bw = h * 1.02f;
            float bh = h * 0.525f;
            // v8.33: video silent pill has a noticeably wider left inset than the ring pill.
            float bl = cardRect.left + h * 0.233f;
            float bt = cy - bh / 2f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(red);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawRoundRect(bl, bt, bl + bw, bt + bh, bh / 2f, bh / 2f, paint);
            iconCx = bl + bw / 2f;
            // v8.39: clip the bell+slash to the red badge so the slash can never spill out.
            int save = canvas.save();
            tmpPath.reset();
            tmpPath.addRoundRect(bl, bt, bl + bw, bt + bh, bh / 2f, bh / 2f,
                    android.graphics.Path.Direction.CW);
            canvas.clipPath(tmpPath);
            drawIcon(canvas, ICON_BELL, iconCx, cy, iconSize,
                    withAlpha(0xFFFFFFFF, (int) (255 * alpha)));
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(h * 0.052f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(red);
            paint.setAlpha((int) (255 * alpha));
            float r = iconSize * 0.42f;
            canvas.drawLine(iconCx - r, cy - r, iconCx + r, cy + r, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(save);
        } else {
            drawIcon(canvas, ICON_BELL, iconCx, cy, iconSize,
                    withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
        }
        textPaint.setTypeface(bold);
        textPaint.setTextSize(textSize(h, 0.36f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(ringerSilent ? red : IslandSpec.TEXT_WHITE);
        textPaint.setAlpha((int) (255 * alpha));
        canvas.drawText(ringerSilent ? "静音" : "响铃",
                cardRect.right - h * 0.32f, baselineFor(textPaint, cy), textPaint);
    }

    /** v8.31: lock-screen padlock pill. */
    private void drawLockPill(Canvas canvas, float alpha) {
        float h = cardH;
        float cy = cardRect.centerY();
        drawIcon(canvas, ICON_LOCK, cardRect.left + h * 0.55f, cy, h * 0.60f,
                withAlpha(IslandSpec.TEXT_WHITE, (int) (255 * alpha)));
    }

    /** v8.36: play the supplied unlock video as-is, pre-packed into a 9x8 sprite sheet. */
    private void drawUnlock(Canvas canvas, float alpha) {
        Bitmap sheet = unlockSheet;
        if (sheet == null) {
            try {
                sheet = BitmapFactory.decodeResource(getResources(), R.drawable.unlock_frames);
            } catch (Throwable ignored) {}
            if (sheet == null) return;
            unlockSheet = sheet;
        }
        int f = unlockFrame;
        if (f < 0) f = 0;
        if (f >= UNLOCK_FRAME_COUNT) f = UNLOCK_FRAME_COUNT - 1;
        int col = f % UNLOCK_SHEET_COLS;
        int row = f / UNLOCK_SHEET_COLS;
        unlockSrc.set(col * UNLOCK_CELL, row * UNLOCK_CELL,
                (col + 1) * UNLOCK_CELL, (row + 1) * UNLOCK_CELL);
        // v8.38: keep the island square unchanged (0.300W) and draw the video smaller inside it.
        float side = Math.min(cardRect.width(), cardRect.height()) * IslandSpec.UNLOCK_VIDEO_SCALE;
        float cx = cardRect.centerX();
        float cy = cardRect.centerY();
        tmpRect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setFilterBitmap(true);
        paint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(sheet, unlockSrc, tmpRect, paint);
        paint.setFilterBitmap(false);
    }

    private void drawContent(Canvas canvas, int m, float alpha) {
        if (alpha <= 0.001f) return;
        paint.setAlpha(255);
        switch (m) {
            case MODE_CHARGE: drawCharging(canvas, alpha); break;
            case MODE_IDLE: break;
            case MODE_RINGER: drawRinger(canvas, alpha); break;
            case MODE_VOLUME: drawVolume(canvas, alpha); break;
            case MODE_LOCK: drawLockPill(canvas, alpha); break;
            case MODE_UNLOCK: drawUnlock(canvas, alpha); break;
            case MODE_TIMER_COMPACT: drawTimerCompact(canvas, alpha); break;
            case MODE_TIMER_EXPANDED: drawTimerExpanded(canvas, alpha); break;
            case MODE_MUSIC_COMPACT: drawMusicCompact(canvas, alpha); break;
            case MODE_MUSIC_EXPANDED: drawMusicExpanded(canvas, alpha); break;
            case MODE_NOTIF_COMPACT: drawNotificationCompact(canvas, alpha); break;
            case MODE_NOTIF_EXPANDED: drawNotificationExpanded(canvas, alpha); break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- helpers
    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Scales the colour's own alpha by the content fade, so #95FFFFFF style veils stay veils. */
    private static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return withAlpha(color, a);
    }

    private static int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }

    /** Linear blend from {@code a} to {@code b}; used to derive a readable waveform tint. */
    private static int blendColor(int a, int b, float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private String clipText(String text, Paint p, float maxWidth) {
        if (text == null) return "";
        if (p.measureText(text) <= maxWidth) return text;
        String ell = "\u2026";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(text.substring(0, mid)) + p.measureText(ell) <= maxWidth) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + ell;
    }

    /** Same result as clipText(), but reuses the previous result while its inputs are unchanged. */
    private String clipTextCached(ClipTextCache cache, String text, Paint p, float maxWidth) {
        if (text == null) return "";
        float size = p.getTextSize();
        Typeface typeface = p.getTypeface();
        if (cache.result != null && text.equals(cache.text)
                && cache.maxWidth == maxWidth && cache.textSize == size
                && cache.typeface == typeface) {
            return cache.result;
        }
        String result = clipText(text, p, maxWidth);
        cache.text = text;
        cache.maxWidth = maxWidth;
        cache.textSize = size;
        cache.typeface = typeface;
        cache.result = result;
        return result;
    }

    private static final class ClipTextCache {
        String text;
        String result;
        float maxWidth;
        float textSize;
        Typeface typeface;
    }

    /**
     * Recomputes timer.digits only when the displayed second (or the timer source data) changed,
     * exactly like the old per-draw Timer.tick(), but without formatting every frame.
     */
    private void updateTimerDigits(long wallNow) {
        if (!timer.running || timer.when <= 0) {
            timerDigitsValid = false;
            return;
        }
        long raw = timer.countDown ? timer.when - wallNow : wallNow - timer.when;
        if (raw < 0L) raw = 0L;
        long sec = raw / 1000L;
        if (timerDigitsValid && sec == timerDigitsSec
                && timerDigitsWhen == timer.when && timerDigitsCountDown == timer.countDown) {
            return;
        }
        timer.digits = IslandData.Timer.format(raw);
        timerDigitsSec = sec;
        timerDigitsWhen = timer.when;
        timerDigitsCountDown = timer.countDown;
        timerDigitsValid = true;
    }

    /** Formats the playback clock only when the displayed second or the duration changed. */
    private void updateMusicTimes(long posMs) {
        long posSec = posMs / 1000L;
        long durSec = Math.max(0L, music.durationMs) / 1000L;
        if (posSec != timeTextPosSec || durSec != timeTextDurSec) {
            elapsedText = formatTime(posSec * 1000L);
            totalText = formatTime(durSec * 1000L);
            timeTextPosSec = posSec;
            timeTextDurSec = durSec;
        }
    }

    // ------------------------------------------------------------------ shaders
    // Every shader used while drawing is cached; a shader is rebuilt only when one of its
    // parameters changed (geometry moves during a spring animation, otherwise it is stable).
    private static final int SHADER_RADIAL2 = 1;
    private static final int SHADER_RADIAL3 = 2;
    private static final int SHADER_LINEAR2 = 3;
    private static final int SHADER_LINEAR3 = 4;

    private static final class ShaderCache {
        Shader shader;
        int kind;
        float g0, g1, g2, g3;
        int c0, c1, c2;
        float s0, s1, s2;
    }

    private static Shader radial2(ShaderCache cache, float cx, float cy, float radius,
                                  int color0, int color1, float stop0, float stop1) {
        if (cache.shader != null && cache.kind == SHADER_RADIAL2
                && cache.g0 == cx && cache.g1 == cy && cache.g2 == radius
                && cache.c0 == color0 && cache.c1 == color1
                && cache.s0 == stop0 && cache.s1 == stop1) {
            return cache.shader;
        }
        cache.shader = new RadialGradient(cx, cy, radius, new int[]{color0, color1},
                new float[]{stop0, stop1}, Shader.TileMode.CLAMP);
        cache.kind = SHADER_RADIAL2;
        cache.g0 = cx; cache.g1 = cy; cache.g2 = radius;
        cache.c0 = color0; cache.c1 = color1;
        cache.s0 = stop0; cache.s1 = stop1;
        return cache.shader;
    }

    private static Shader radial3(ShaderCache cache, float cx, float cy, float radius,
                                  int color0, int color1, int color2,
                                  float stop0, float stop1, float stop2) {
        if (cache.shader != null && cache.kind == SHADER_RADIAL3
                && cache.g0 == cx && cache.g1 == cy && cache.g2 == radius
                && cache.c0 == color0 && cache.c1 == color1 && cache.c2 == color2
                && cache.s0 == stop0 && cache.s1 == stop1 && cache.s2 == stop2) {
            return cache.shader;
        }
        cache.shader = new RadialGradient(cx, cy, radius, new int[]{color0, color1, color2},
                new float[]{stop0, stop1, stop2}, Shader.TileMode.CLAMP);
        cache.kind = SHADER_RADIAL3;
        cache.g0 = cx; cache.g1 = cy; cache.g2 = radius;
        cache.c0 = color0; cache.c1 = color1; cache.c2 = color2;
        cache.s0 = stop0; cache.s1 = stop1; cache.s2 = stop2;
        return cache.shader;
    }

    private static Shader linear2(ShaderCache cache, float x0, float y0, float x1, float y1,
                                  int color0, int color1) {
        if (cache.shader != null && cache.kind == SHADER_LINEAR2
                && cache.g0 == x0 && cache.g1 == y0 && cache.g2 == x1 && cache.g3 == y1
                && cache.c0 == color0 && cache.c1 == color1) {
            return cache.shader;
        }
        cache.shader = new LinearGradient(x0, y0, x1, y1, new int[]{color0, color1}, null,
                Shader.TileMode.CLAMP);
        cache.kind = SHADER_LINEAR2;
        cache.g0 = x0; cache.g1 = y0; cache.g2 = x1; cache.g3 = y1;
        cache.c0 = color0; cache.c1 = color1;
        return cache.shader;
    }

    private static Shader linear3(ShaderCache cache, float x0, float y0, float x1, float y1,
                                  int color0, int color1, int color2,
                                  float stop0, float stop1, float stop2) {
        if (cache.shader != null && cache.kind == SHADER_LINEAR3
                && cache.g0 == x0 && cache.g1 == y0 && cache.g2 == x1 && cache.g3 == y1
                && cache.c0 == color0 && cache.c1 == color1 && cache.c2 == color2
                && cache.s0 == stop0 && cache.s1 == stop1 && cache.s2 == stop2) {
            return cache.shader;
        }
        cache.shader = new LinearGradient(x0, y0, x1, y1, new int[]{color0, color1, color2},
                new float[]{stop0, stop1, stop2}, Shader.TileMode.CLAMP);
        cache.kind = SHADER_LINEAR3;
        cache.g0 = x0; cache.g1 = y0; cache.g2 = x1; cache.g3 = y1;
        cache.c0 = color0; cache.c1 = color1; cache.c2 = color2;
        cache.s0 = stop0; cache.s1 = stop1; cache.s2 = stop2;
        return cache.shader;
    }
}
