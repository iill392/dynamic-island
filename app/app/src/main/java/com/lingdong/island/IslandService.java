package com.lingdong.island;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Foreground service that owns the overlay window and decides which island state is visible.
 * Priority: user expanded view &gt; charging (transient) &gt; music &gt; timer.
 */
public class IslandService extends Service implements IslandBus.NotificationSink {

    private static final String TAG = "IslandService";

    public static final String ACTION_STOP = "com.lingdong.island.STOP";
    public static final String ACTION_DEMO_CHARGE = "com.lingdong.island.DEMO_CHARGE";
    public static final String ACTION_DEMO_TIMER = "com.lingdong.island.DEMO_TIMER";
    public static final String ACTION_DEMO_MUSIC = "com.lingdong.island.DEMO_MUSIC";
    public static final String ACTION_DEMO_HIDE = "com.lingdong.island.DEMO_HIDE";
    public static final String ACTION_REFRESH = "com.lingdong.island.REFRESH";

    private static final String CHANNEL_ID = "island";
    private static final int NOTIF_ID = 1001;

    /** Window-top retries: the first one often runs before the first layout / before the ROM settles. */
    private static final long[] POSITION_RETRY_DELAYS_MS = {0L, 120L, 400L};
    /** Battery binder properties are polled at most once per second (tick keeps its 300 ms cadence). */
    private static final long BATTERY_POLL_INTERVAL_MS = 1000L;
    /** Tap tolerance for the expanded music card buttons: ~40 % of the ~0.197 spacing. */
    private static final float MUSIC_BUTTON_HIT_HALF = 0.079f;
    /** Tap tolerance for the two (decorative) timer-card circles, ~ half of their drawn radius. */
    private static final float TIMER_BUTTON_HIT_HALF = 0.062f;

    /** P-05: album art is downscaled to this longest side once, off the main thread. */
    private static final int ARTWORK_MAX_PX = 512;
    /** F-06: retry delay / cap for re-registering the media-session listener. */
    private static final long MEDIA_SETUP_RETRY_MS = 2000L;
    private static final int MEDIA_SETUP_RETRY_MAX = 3;
    /** R-03: ~900 ms (3 x 300 ms tick) without a window token means the window was removed. */
    private static final int WINDOW_LOST_TICKS = 3;

    /** P-07: pre-compiled notification text patterns (no per-notification compilation). */
    private static final Pattern TIME_TEXT_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern HAS_TIME_PATTERN =
            Pattern.compile(".*\\d{1,2}:\\d{2}.*");

    /** v3.3: notification island constants (see IslandSpec notification block). */
    private static final int NOTIF_ICON_MAX_PX = 192;
    private static final int APP_LABEL_CACHE_MAX = 32;
    private static final int APP_ICON_CACHE_MAX = 32;
    /** Expanded notification stays a bit longer than the normal expand auto-collapse timeout. */
    private static final long NOTIF_EXPANDED_HOLD_MS = IslandSpec.EXPANDED_TIMEOUT_MS + 2000L;

    private static IslandService instance;

    private WindowManager wm;
    private IslandView view;
    private WindowManager.LayoutParams lp;
    /** v8.6: overlay reply editor for notification RemoteInput actions. */
    private View replyInputView;
    private EditText replyInputEdit;
    private boolean replyIsForced;
    private boolean attached;
    private int winW, winH;
    private boolean windowOnA11y;
    private long keepDotUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IslandData.Charging charging = new IslandData.Charging();
    private final IslandData.Timer timer = new IslandData.Timer();
    private final IslandData.Music music = new IslandData.Music();
    /** v3.3: the notification currently rendered by the island (model owned by the service). */
    private final IslandData.NotificationItem notif = new IslandData.NotificationItem();
    private boolean notifActive;
    private long notifUntil;
    /** v8.8: notification island always starts expanded (never shows the compact pill first). */
    private boolean notifForceExpanded;
    /** v8.22: notification close animation is in flight (no-bounce critical damping). */
    private boolean notifClosing;
    /** v8.28: where the notification card should collapse to (IDLE when always-on). */
    private int notifCloseTarget;
    private String currentNotifKey;
    /** Notifications posted before the service started are replayed by IslandBus and must not pop. */
    private long serviceStartWallMs;
    private long iconRequestSeq;
    private final HashMap<String, String> appLabelCache = new HashMap<>();
    private final HashMap<String, Bitmap> appIconCache = new HashMap<>();

    private boolean chargingActive;
    private long chargingUntil;
    private long lastChargingCurrentMa;
    private boolean timerActive;
    private String timerKey;
    private boolean musicActive;

    /** v8.31: transient ringer HUD (响铃/静音) after AudioManager ringer-mode changes. */
    private boolean ringerActive;
    private boolean ringerSilent;
    private long ringerUntil;
    private int lastRingerMode = -1;
    private boolean ringerRegistered;

    /** v8.47: transient system-volume HUD, detected by polling AudioManager streams. */
    private boolean volumeActive;
    private long volumeUntil;
    private int volumeStream = android.media.AudioManager.STREAM_MUSIC;
    private int volumeLevel;
    private int volumeMax = 1;
    private final int[] lastVolumes = new int[]{-1, -1, -1, -1, -1, -1};
    private static final int[] VOLUME_STREAMS = {
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.STREAM_RING,
            android.media.AudioManager.STREAM_NOTIFICATION,
            android.media.AudioManager.STREAM_ALARM,
            android.media.AudioManager.STREAM_VOICE_CALL,
            android.media.AudioManager.STREAM_SYSTEM
    };
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private boolean volumeRegistered;

    /** v8.31: lock-screen padlock + unlock animation driven by ACTION_USER_PRESENT. */
    private boolean locked;
    private boolean unlockPending;
    private boolean unlockAnimating;
    private boolean unlockHold;
    private long unlockStart;
    private long unlockRestingUntil;
    /** v8.43: pre-size the overlay window for the unlock square before starting playback. */
    private boolean unlockStartPending;
    private long unlockPendingSince;
    private int unlockSeq;
    /** Ignore a stale KeyguardManager "locked" after USER_PRESENT for a short grace period. */
    private long lockIgnoreUntil;
    private boolean lockRegistered;
    private boolean screenOn = true;

    private MediaSessionManager mediaSessionManager;
    private MediaController mediaController;
    private ComponentName listenerComponent;
    private long expandedAt;

    private int demoMode; // 0 none, 1 charge, 2 timer, 3 music
    private long demoUntil;
    /** v6.0: keep an idle capsule visible when no music/timer/notification/charging is active. */
    private boolean alwaysOn;
    /** v8.56: independent visibility switches (default all on). */
    private boolean showNotifications = true;
    private boolean showCharging = true;
    private boolean showVolume = true;
    private boolean showMusic = true;
    private boolean showRinger = true;
    private boolean showUnlock = true;
    private boolean showLock = true;
    /** v8.64: in landscape, optionally place the island below the camera cutout. */
    private boolean cameraFollow;
    private int cameraFollowWindowY;
    private boolean wasPlugged;
    /** v8.40: battery status can lag one tick behind ACTION_POWER_CONNECTED; do not hide the
     * charging island immediately when the first BATTERY_CHANGED still says NOT_CHARGING. */
    private long chargingLossSince;
    private static final long CHARGING_LOSS_GRACE_MS = 1500L;
    private float batteryCapacityMahCache = -1f;

    /** True once onDestroy started; posted callbacks must not touch views/windows afterwards. */
    private boolean destroyed;

    /** R-01: startForeground() failed -> the service must stop, not keep running without FGS. */
    private boolean foregroundFailed;
    /** Single background thread for album-art scaling / colour extraction (never on the main thread). */
    private ExecutorService colorExecutor;
    private long demoArtworkToken;
    private Bitmap demoArtwork;
    /**
     * P-05/L-01: app-owned downscaled album art (longest side <= {@link #ARTWORK_MAX_PX}). Only a
     * bitmap created by this service is ever recycled — never the framework's metadata bitmap.
     */
    private long artworkRequestSeq;
    private Bitmap artworkThumb;
    private boolean artworkThumbOwned;
    /** v7.0: media app whose launcher icon is used when the track has no album art. */
    private String musicIconPackage = "";
    private int musicIconRequestSeq;
    /** Stable identity of the currently displayed artwork (title|artist|album|track|duration). */
    private String artworkKey;
    /** Key of the thumbnail request that is currently in flight. */
    private String pendingArtworkKey;
    /** Pre-demo timer values, restored by endDemo() when a real timer notification is still up. */
    private IslandData.Timer savedTimer;

    private long lastBatteryPollAt;
    private int positionRetryIndex;
    private int lastPositionErrorPx = -1;
    /** R-03: consecutive ticks where `attached` is true but the view has no window token. */
    private int windowLostCount;
    /** v3.9: consecutive ticks where a visible state has no attached overlay (add failed etc). */
    private int reattachTries;
    /** F-06: SecurityException retries for the media-session listener (3 x 2 s, no duplicates). */
    private int mediaSetupRetryCount;
    private boolean mediaSetupRetryScheduled;

    /** v3.6: brief grace period before hiding after a notification removal (rapid group updates). */
    private static final long NOTIF_DISMISS_DELAY_MS = 250L;

    private final Runnable notifDismissTask = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            // v8.18: notification cards are fully sticky. A pending dismissal scheduled before the
            // expand animation finished was closing the card mid-flight, so the message could
            // never be read. Keep it until the user taps/replies/swipes.
            if (notifActive && notifForceExpanded) return;
            // Keep the notification model/data alive while the user is typing a forced reply,
            // even if launching the app removed the notification from the shade.
            if (replyInputView != null) {
                handler.postDelayed(this, 800L);
                return;
            }
            // v8.10: a spurious remove callback (many apps update by remove+re-post) must not hide
            // a notification that is still present in the shade.
            if (notifActive && currentNotifKey != null
                    && IslandNotificationListener.isNotificationActive(currentNotifKey)) {
                handler.postDelayed(this, 800L);
                return;
            }
            dismissNotificationIsland();
        }
    };
    /** v8.4: a new notification should expand the notification card automatically. */
    private boolean notifAutoExpandPending;
    private int notifAutoExpandTries;
    /** v8.21: expanded notification card auto-closes after 4 s without spring animation. */
    private boolean notifAutoHideScheduled;
    private final Runnable notifAutoHideTask = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!notifActive || !notifForceExpanded) return;
            if (replyInputView != null) {
                handler.postDelayed(this, 1000L);
                return;
            }
            notifAutoHideScheduled = false;
            dismissNotificationIsland();
        }
    };
    private final Runnable notifAutoExpandTask = new Runnable() {
        @Override public void run() {
            if (destroyed || !notifAutoExpandPending) return;
            if (!notifActive || view == null) {
                notifAutoExpandPending = false;
                return;
            }
            int m = view.getMode();
            if (m == IslandView.MODE_NOTIF_EXPANDED) {
                notifAutoExpandPending = false;
                return;
            }
            if (m == IslandView.MODE_NOTIF_COMPACT) {
                notifAutoExpandPending = false;
                expandedAt = SystemClock.uptimeMillis();
                extendNotifHoldForExpanded();
                ensureWindow(IslandView.MODE_NOTIF_EXPANDED, false);
                view.morphTo(IslandView.MODE_NOTIF_EXPANDED, true);
                return;
            }
            // The notification may still be morphing in from music/charging/hidden; retry briefly.
            if (++notifAutoExpandTries <= 6) {
                handler.postDelayed(this, 180L);
            } else {
                notifAutoExpandPending = false;
            }
        }
    };
    /** v8.1: vivo Clock may remove+re-post its stopwatch notification on each tick. */
    private String pendingTimerRemoveKey;
    private final Runnable timerRemoveTask = new Runnable() {
        @Override public void run() {
            if (destroyed || timerKey == null || !timerKey.equals(pendingTimerRemoveKey)) return;
            pendingTimerRemoveKey = null;
            timerActive = false;
            timerKey = null;
            timer.when = 0;
            timer.running = false;
            timer.packageName = "";
            timer.actionCount = 0;
            for (int i = 0; i < timer.actionTitles.length; i++) {
                timer.actionTitles[i] = null;
                timer.actionIntents[i] = null;
            }
            syncModelsToView();
            recomputeState(false);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 300);
        }
    };

    // ==================================================================== life
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        destroyed = false;
        foregroundFailed = false;
        colorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "island-accent"));
        IslandSpec.init(this);
        IslandSpec.USER_OFFSET_PX = getSharedPreferences("island", MODE_PRIVATE)
                .getFloat("offset_dp", 0f) * IslandSpec.DENSITY;
        alwaysOn = getSharedPreferences("island", MODE_PRIVATE).getBoolean("always_on", false);
        loadVisibilityPrefs();
        startForegroundCompat();
        if (foregroundFailed) {
            // R-01: without a foreground notification Android would kill us with a
            // RemoteServiceException a few seconds later. stopSelf() was already issued; do not
            // build the overlay / register listeners for a service that is about to die.
            Log.e(TAG, "service stopping: could not enter foreground state");
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        registerBattery();
        registerRinger();
        registerLock();
        initVolumeBaseline();
        registerVolumeReceiver();
        // Must be recorded before IslandBus.register() replays currently-posted notifications:
        // only notifications that arrive after this point are allowed to pop on the island.
        serviceStartWallMs = System.currentTimeMillis();
        IslandBus.register(this);
        setupMediaSessions();
        handler.post(ticker);
        // v6.0: when always-on was enabled before the service starts, show the idle capsule now
        // instead of waiting for the tick retry.
        if (alwaysOn || locked) handler.post(() -> { if (!destroyed) recomputeState(true); });
        Log.i(TAG, "service created, screen=" + IslandSpec.W + "x" + IslandSpec.H
                + " density=" + IslandSpec.DENSITY);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundFailed) return START_NOT_STICKY;
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_STOP:
                    stopSelf();
                    break;
                case ACTION_DEMO_CHARGE:
                    startChargingDemo();
                    break;
                case ACTION_DEMO_TIMER:
                    // v8.3: timer island removed; ignore stale preview intents.
                    break;
                case ACTION_DEMO_MUSIC:
                    startMusicDemo();
                    break;
                case ACTION_DEMO_HIDE:
                    // F-13: "结束预览" is an immediate action — an expanded preview snaps away
                    // instead of playing the collapse spring first.
                    endDemo(true);
                    break;
                case ACTION_REFRESH:
                    loadVisibilityPrefs();
                    if (lp != null && attached) {
                        applyWindowPlacement(lp);
                        updateWindowLayout();
                        schedulePositionCorrection();
                    }
                    if (notifActive && !showNotifications) dismissNotificationIsland();
                    if (chargingActive && !showCharging) endChargingNow();
                    if (!showVolume) volumeActive = false;
                    if (!showRinger) ringerActive = false;
                    if (!showUnlock) {
                        unlockHold = false;
                        unlockAnimating = false;
                        unlockStartPending = false;
                        unlockSeq++;
                        unlockRestingUntil = 0L;
                    }
                    setupMediaSessions();
                    applyDisplayAndOffset();
                    // v7.6: the app whitelist may have just changed; drop a notification that is
                    // no longer allowed before recomputing the visible state.
                    if (notifActive && !isNotificationAppAllowed(notif.packageName)) {
                        dismissNotificationIsland();
                    }
                    if (!attached && desiredMode() != IslandView.MODE_HIDDEN) {
                        attachOverlay(desiredMode());
                    }
                    recomputeState(false);
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    /** Re-read the display size / status-bar height / user offset and re-seat the window if needed. */
    private void applyDisplayAndOffset() {
        if (view == null) return;
        float oldW = IslandSpec.W;
        float oldH = IslandSpec.H;
        float oldDensity = IslandSpec.DENSITY;
        float oldStatusBarH = IslandSpec.STATUS_BAR_H;
        float oldOffset = IslandSpec.USER_OFFSET_PX;
        float oldGeomScale = IslandSpec.GEOM_SCALE;
        float oldCw = IslandSpec.COMPACT_W_MUL, oldCh = IslandSpec.COMPACT_H_MUL;
        float oldEw = IslandSpec.EXPANDED_W_MUL, oldEh = IslandSpec.EXPANDED_H_MUL;
        float oldRadius = IslandSpec.RADIUS_MUL;
        float oldIdleW = IslandSpec.IDLE_W_MUL, oldIdleH = IslandSpec.IDLE_H_MUL;
        boolean oldAlwaysOn = alwaysOn;
        IslandSpec.init(this);
        alwaysOn = getSharedPreferences("island", MODE_PRIVATE).getBoolean("always_on", false);
        boolean alwaysChanged = oldAlwaysOn != alwaysOn;
        float newOffset = getSharedPreferences("island", MODE_PRIVATE)
                .getFloat("offset_dp", 0f) * IslandSpec.DENSITY;
        IslandSpec.USER_OFFSET_PX = newOffset;
        boolean geometryChanged = oldGeomScale != IslandSpec.GEOM_SCALE
                || oldCw != IslandSpec.COMPACT_W_MUL || oldCh != IslandSpec.COMPACT_H_MUL
                || oldEw != IslandSpec.EXPANDED_W_MUL || oldEh != IslandSpec.EXPANDED_H_MUL
                || oldRadius != IslandSpec.RADIUS_MUL
                || oldIdleW != IslandSpec.IDLE_W_MUL || oldIdleH != IslandSpec.IDLE_H_MUL;
        if (geometryChanged) {
            // v5.7: size sliders changed -> recreate the view/window so the new geometry is used.
            boolean wasAttached = attached;
            detachOverlay();
            buildView();
            if (wasAttached) {
                int want = desiredMode();
                if (want != IslandView.MODE_HIDDEN) {
                    attachOverlay(want);
                    if (attached) {
                        view.refreshFrame();
                        view.showFromHidden(want);
                    }
                }
            }
            return;
        }
        boolean changed = oldW != IslandSpec.W
                || oldH != IslandSpec.H
                || oldDensity != IslandSpec.DENSITY
                || oldStatusBarH != IslandSpec.STATUS_BAR_H
                || oldOffset != newOffset;
        if (!changed) {
            // S11: nothing moved — a remove/attach cycle would just make the island flicker and
            // re-run the position compensation. Just refresh the frame-dependent numbers.
            view.refreshFrame();
            if (alwaysChanged) recomputeState(false);
            return;
        }
        int m = view.getMode();
        boolean wasAttached = attached;
        if (wasAttached) removeWindow();
        view.refreshFrame();
        if (wasAttached && m != IslandView.MODE_HIDDEN) {
            attachOverlay(m);
            if (attached) view.resumeRendering();
        }
        if (alwaysChanged) recomputeState(false);
    }

    @Override public void onDestroy() {
        destroyed = true;
        instance = null;
        IslandBus.unregister(this);
        handler.removeCallbacksAndMessages(null);
        handler.removeCallbacks(unlockTicker);
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        if (ringerRegistered) {
            try { unregisterReceiver(ringerReceiver); } catch (Exception ignored) {}
            ringerRegistered = false;
        }
        if (lockRegistered) {
            try { unregisterReceiver(lockReceiver); } catch (Exception ignored) {}
            lockRegistered = false;
        }
        if (volumeRegistered) {
            try { unregisterReceiver(volumeReceiver); } catch (Exception ignored) {}
            volumeRegistered = false;
        }
        try {
            if (mediaSessionManager != null && listenerComponent != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            }
        } catch (Exception ignored) {}
        if (mediaController != null) {
            try { mediaController.unregisterCallback(mediaCallback); } catch (Exception ignored) {}
            mediaController = null;
        }
        discardArtworkThumb();
        discardDemoArtwork();
        // v3.3: drop notification references (PendingIntents / icon bitmaps) and in-flight icon loads.
        iconRequestSeq++;
        notifActive = false;
        currentNotifKey = null;
        notif.reset();
        appLabelCache.clear();
        appIconCache.clear();
        if (colorExecutor != null) {
            colorExecutor.shutdownNow();
            colorExecutor = null;
        }
        detachOverlay();
        hideNotificationReplyInput();
        lp = null;
        view = null;
        wm = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "游龙灵动岛服务",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("游龙灵动岛正在运行")
                .setContentText("充电 / 计时器 / 音乐效果")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                // framework Notification.Builder has no setSilent(); the IMPORTANCE_MIN channel with
                // sound/vibration disabled above is the equivalent for this foreground notification.
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // R-01: never run on after a failed startForeground() — the system treats the FGS as
            // not started and kills the process. Stop explicitly and remember why.
            Log.e(TAG, "startForeground failed, stopping service", e);
            foregroundFailed = true;
            stopSelf();
        }
    }

    private void buildView() {
        view = new IslandView(this);
        view.setCallback(new IslandView.Callback() {
            /** v3.3: the callback also carries the vertical fraction for the notification card. */
            @Override public void onIslandTap(float xFraction, float yFraction) {
                handleTap(xFraction, yFraction);
            }

            @Override public void onIslandSettled() {
                onViewSettled();
            }

            /**
             * v3.2: a touch outside the island (delivered as ACTION_OUTSIDE thanks to
             * FLAG_WATCH_OUTSIDE_TOUCH) only collapses an expanded card. Compact states are left
             * alone so a real running timer / playing music capsule is not dismissed by accident.
             */
            @Override public void onIslandOutsideTap() {
                // v8.8: the auto-expanded notification card is sticky; outside screen taps must
                // not collapse it. Other expanded cards keep the old collapse behaviour.
                if (view != null && view.getMode() == IslandView.MODE_NOTIF_EXPANDED) return;
                if (view != null && view.isExpanded()) collapseExpanded();
            }

            /** v3.2: an upward swipe on the island still collapses an expanded card. */
            @Override public void onIslandSwipeUp() {
                if (view != null && view.isExpanded()) collapseExpanded();
            }
        });
        // S2: a brand new IslandView starts with default models (00:01 / Unknown track / no cover),
        // so backfill everything the service already knows before the window is attached.
        syncModelsToView();
    }

    /** S2: copy the service-owned models into the view that is actually drawn. */
    private void syncModelsToView() {
        if (view == null) return;
        view.setRingerSilent(ringerSilent);
        view.setVolume(volumeStream, volumeLevel, volumeMax);
        view.setUnlockAnim(unlockAnimating,
                unlockAnimating ? SystemClock.uptimeMillis() - unlockStart : 0L);
        if (unlockAnimating) {
            long e = SystemClock.uptimeMillis() - unlockStart;
            int f = (int) (e * IslandSpec.UNLOCK_VIDEO_FRAMES / IslandSpec.UNLOCK_VIDEO_MS);
            view.setUnlockVideoFrame(f > IslandSpec.UNLOCK_VIDEO_FRAMES - 1
                    ? IslandSpec.UNLOCK_VIDEO_FRAMES - 1 : f);
        }
        IslandData.Charging vc = view.charging();
        vc.charging = charging.charging;
        vc.level = charging.level;
        vc.preciseLevel = charging.preciseLevel;
        vc.fast = charging.fast;
        vc.superFast = charging.superFast;

        if (timer.running && timer.when > 0) timer.tick(System.currentTimeMillis());
        IslandData.Timer vt = view.timer();
        vt.title = timer.title;
        vt.digits = timer.digits;
        vt.running = timer.running;
        vt.countDown = timer.countDown;
        vt.when = timer.when;
        // C: expose the Clock notification's actions for the two expanded-card buttons.
        vt.actionCount = timer.actionCount;
        for (int i = 0; i < vt.actionTitles.length; i++) {
            vt.actionTitles[i] = timer.actionTitles[i];
            vt.actionIntents[i] = timer.actionIntents[i];
        }

        IslandData.Music vm = view.music();
        vm.title = music.title;
        vm.artist = music.artist;
        vm.artwork = music.artwork;
        vm.appIcon = music.appIcon;
        vm.accent = music.accent;
        vm.playing = music.playing;
        vm.positionMs = music.positionMs;
        vm.positionAtUptime = music.positionAtUptime;
        vm.durationMs = music.durationMs;

        // A: notification island model (the icon Bitmap is drawn by the view; never recycled here).
        IslandData.NotificationItem vn = view.notification();
        vn.key = notif.key;
        vn.packageName = notif.packageName;
        vn.appName = notif.appName;
        vn.title = notif.title;
        vn.text = notif.text;
        vn.timeText = notif.timeText;
        vn.icon = notif.icon;
        vn.postedAt = notif.postedAt;
        vn.contentIntent = notif.contentIntent;
        vn.actionCount = notif.actionCount;
        vn.replyActionIndex = notif.replyActionIndex;
        vn.replyInputs = notif.replyInputs;
        vn.forceReplyActionIndex = notif.forceReplyActionIndex;
        for (int i = 0; i < vn.actionTitles.length; i++) {
            vn.actionTitles[i] = notif.actionTitles[i];
            vn.actionIntents[i] = notif.actionIntents[i];
        }
    }

    // ================================================================ overlay
    private void attachOverlay(int targetMode) {
        if (attached || view == null) return;
        // v8.19: if an earlier remove failed (a11y host disappeared / wrong WindowManager), the
        // view can still be attached while `attached` is false. Adding it again throws
        // "View has already been added to the window manager"; force-clean both hosts first.
        if (view.getWindowToken() != null || view.getParent() != null) {
            forceRemoveViewFromAnyWindow();
        }
        view.refreshFrame();
        // v3.3: the notification modes need the dynamic width computed by the view instance.
        int[] size = view.windowSizeForMode(targetMode);
        lp = new WindowManager.LayoutParams();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                // v3.2: outside touches are reported to the view as ACTION_OUTSIDE (only useful
                // together with FLAG_NOT_TOUCH_MODAL; the view turns them into onIslandOutsideTap).
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.width = size[0];
        lp.height = size[1];
        lp.y = 0;
        applyWindowPlacement(lp);
        winW = size[0];
        winH = size[1];
        lp.setTitle("Island");
        lp.windowAnimations = 0;             // manual §10.7: system must not animate/move the window
        try {
            java.lang.reflect.Field f = lp.getClass().getField("privateFlags");
            f.setInt(lp, f.getInt(lp) | 0x40);   // PRIVATE_FLAG_NO_MOVE_ANIMATION
        } catch (Throwable ignored) {
            // hidden API blocked on some ROMs; windowAnimations=0 already avoids the visible jump
        }
        if (Build.VERSION.SDK_INT >= 28) {
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        // R-02: try the accessibility overlay first — TYPE_ACCESSIBILITY_OVERLAY does not need the
        // "draw over other apps" permission, so enhanced mode can work on its own.
        IslandAccessibilityService a11y = IslandAccessibilityService.get();
        if (a11y != null) {
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            try {
                a11y.addView(view, lp);
                windowOnA11y = true;
                attached = true;
                schedulePositionCorrection();
                return;
            } catch (Exception e) {
                Log.w(TAG, "accessibility overlay failed, falling back", e);
                windowOnA11y = false;
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission (neither accessibility host nor SYSTEM_ALERT_WINDOW)");
            attached = false;
            windowOnA11y = false;
            return;
        }
        if (wm == null) return;
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        try {
            wm.addView(view, lp);
            windowOnA11y = false;
            attached = true;
            schedulePositionCorrection();
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            attached = false;
            windowOnA11y = false;
        }
    }

    private void updateWindowLayout() {
        if (!attached || view == null || lp == null) return;
        try {
            if (windowOnA11y) {
                IslandAccessibilityService a11y = IslandAccessibilityService.get();
                // S12: the accessibility host was disconnected; the system already tore the
                // window down. Do not hand our view to the app WindowManager here — the rebind
                // path (onAccessibilityChanged) re-attaches it as a regular overlay.
                if (a11y == null) return;
                a11y.updateView(view, lp);
            } else if (wm != null) {
                wm.updateViewLayout(view, lp);
            }
        } catch (Exception ignored) {}
    }

    private void removeWindow() {
        if (view == null) return;
        forceRemoveViewFromAnyWindow();
        attached = false;
        windowOnA11y = false;
    }

    /** v8.19: remove the view from whichever WindowManager may still own it. */
    private void forceRemoveViewFromAnyWindow() {
        if (view == null) return;
        try {
            if (wm != null) wm.removeViewImmediate(view);
        } catch (Throwable ignored) {}
        try {
            IslandAccessibilityService a11y = IslandAccessibilityService.get();
            if (a11y != null) a11y.removeView(view);
        } catch (Throwable ignored) {}
    }

    /**
     * S4: keep the overlay exactly at the top edge of the display. Some ROMs letterbox overlay
     * windows away from the status-bar / camera-cutout area; measuring the real on-screen position
     * lets us compensate. The window may not be laid out yet on the first attempt and some ROMs
     * push it down only after the window settles, so measure up to three times (0/120/400 ms).
     */
    private void schedulePositionCorrection() {
        if (view == null) return;
        handler.removeCallbacks(positionCorrectionTask);
        positionRetryIndex = 0;
        lastPositionErrorPx = -1;
        handler.postDelayed(positionCorrectionTask, POSITION_RETRY_DELAYS_MS[0]);
    }

    private final Runnable positionCorrectionTask = new Runnable() {
        @Override public void run() {
            if (destroyed || !attached || view == null || lp == null) return;
            // "layout 前执行" case: measuring now would return junk (0 / old value) -> retry later.
            boolean laidOut = view.isLaidOut() && view.getWidth() > 0 && view.getHeight() > 0;
            if (laidOut && view.getWindowToken() != null) {
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                int error = loc[1] - cameraFollowWindowY;
                int absError = Math.abs(error);
                // Adjust only when it is worth doing (still off by >1px and the residual is not
                // getting worse, so a ROM that clamps the window cannot push us further and
                // further away). Never return early here: a ROM that pushes the window down a
                // few hundred ms after it settled must still be caught by the 120/400 ms probes.
                boolean shouldAdjust = absError > 1
                        && (lastPositionErrorPx < 0 || absError < lastPositionErrorPx);
                if (shouldAdjust) {
                    lastPositionErrorPx = absError;
                    // loc[1] is the residual on-screen offset, so subtract it from the current y
                    // (lp.y may already carry a previous compensation; never overwrite it absolutely).
                    lp.y -= error;
                    updateWindowLayout();
                }
            }
            positionRetryIndex++;
            if (positionRetryIndex < POSITION_RETRY_DELAYS_MS.length) {
                handler.postDelayed(this, POSITION_RETRY_DELAYS_MS[positionRetryIndex]);
            }
        }
    };

    private void ensureWindow(int targetMode) {
        ensureWindow(targetMode, true);
    }

    /**
     * v8.64: optional landscape camera-follow placement.
     *
     * Normal mode keeps the centre-top position (lp.x/lp.y = 0). In camera-follow mode, if the
     * device is landscape and exposes a display cutout, the island is moved horizontally below
     * that cutout (left/right camera notch in landscape) and clamped so the card stays on-screen.
     */
    private void applyWindowPlacement(WindowManager.LayoutParams params) {
        if (params == null) return;
        params.x = 0;
        params.y = 0;
        cameraFollowWindowY = 0;
        if (!cameraFollow || view == null) return;
        Configuration cfg = getResources().getConfiguration();
        if (cfg.orientation != Configuration.ORIENTATION_LANDSCAPE) return;
        if (Build.VERSION.SDK_INT < 29) return;

        Display display = null;
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Throwable ignored) {}
        if (display == null) return;
        DisplayCutout cutout = null;
        try { cutout = display.getCutout(); } catch (Throwable ignored) {}
        if (cutout == null) return;
        Rect best = null;
        for (Rect r : cutout.getBoundingRects()) {
            if (r == null || r.width() <= 0 || r.height() <= 0) continue;
            if (best == null || (long) r.width() * r.height()
                    > (long) best.width() * best.height()) {
                best = new Rect(r);
            }
        }
        if (best == null) return;

        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        try { display.getRealMetrics(metrics); } catch (Throwable ignored) {}
        int screenW = metrics.widthPixels > 0 ? metrics.widthPixels
                : getResources().getDisplayMetrics().widthPixels;
        int screenH = metrics.heightPixels > 0 ? metrics.heightPixels
                : getResources().getDisplayMetrics().heightPixels;
        float cardW = Math.max(1f, view.getCardWidth());
        float cardH = Math.max(1f, view.getCardHeight());
        float margin = 8f * (IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 3f);

        float centerX = best.centerX();
        float half = cardW / 2f + margin;
        if (half * 2f < screenW) {
            centerX = Math.max(half, Math.min(screenW - half, centerX));
        }
        float desiredTop = best.bottom + margin;
        float maxTop = Math.max(0f, screenH - cardH - margin);
        desiredTop = Math.max(0f, Math.min(maxTop, desiredTop));

        params.x = Math.round(centerX - screenW / 2f);
        cameraFollowWindowY = Math.round(desiredTop - IslandSpec.cardTop());
        params.y = cameraFollowWindowY;
    }

    /**
     * F-14: while a morph starts, the window may only grow — shrinking it to the target size first
     * would clip the still-big current card for a frame or two. Shrinking is deferred to
     * {@link #onViewSettled()} once the springs are done ({@code allowShrink=true}).
     */
    private void ensureWindow(int targetMode, boolean allowShrink) {
        if (view == null) return;
        if (!attached) {
            attachOverlay(targetMode);
            return;
        }
        int[] size = view.windowSizeForMode(targetMode);
        int targetW = size[0];
        int targetH = size[1];
        if (!allowShrink) {
            // v8.61: grow each axis independently. The old code returned entirely when *either*
            // axis wanted to shrink, so unlock-square (tall) -> longer capsule (wider, shorter)
            // kept the narrow unlock window and clipped the card into a square-cornered rectangle
            // until the settle pass resized the window (~0.5 s). Grow width now, keep the extra
            // height until settle.
            targetW = Math.max(winW, targetW);
            targetH = Math.max(winH, targetH);
        }
        if (targetW == winW && targetH == winH) return;
        winW = targetW;
        winH = targetH;
        lp.width = targetW;
        lp.height = targetH;
        updateWindowLayout();
        schedulePositionCorrection();
    }

    private void detachOverlay() {
        removeWindow();
    }

    // ================================================================ charging
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                boolean chargingNow = plugged != 0
                        && status == BatteryManager.BATTERY_STATUS_CHARGING
                        && level * 100 / Math.max(1, scale) < 100;
                boolean pluggedNow = plugged != 0;
                // only pop the charging island on the plug edge (not on every battery % tick)
                if (chargingNow && !wasPlugged) {
                    onChargingStarted(level, scale);
                } else if (!chargingNow && chargingActive) {
                    if (pluggedNow) {
                        // Still physically plugged in. Some ROMs report FULL/NOT_CHARGING while
                        // plugged in for a long time (or lag right after plugging); never let that
                        // collapse the charging island. Let the normal 6 s hold / disconnect path
                        // decide instead.
                        chargingLossSince = 0L;
                    } else {
                        // Even the unplugged battery tick can be stale right after POWER_CONNECTED;
                        // require a stable loss before hiding.
                        long t = SystemClock.uptimeMillis();
                        if (chargingLossSince == 0L) chargingLossSince = t;
                        else if (t - chargingLossSince >= CHARGING_LOSS_GRACE_MS) {
                            endChargingNow();
                        }
                    }
                } else {
                    chargingLossSince = 0L;
                }
                wasPlugged = pluggedNow;
                if (chargingNow) {
                    chargingLossSince = 0L;
                    updateChargingData(level, scale);
                }
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                if (!chargingActive) onChargingStarted(-1, 100);
                wasPlugged = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                wasPlugged = false;
                endChargingNow();
            }
        }
    };

    private void registerBattery() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, f);
    }

    /** v8.31: AudioManager ringer-mode changes -> transient 响铃/静音 HUD. */
    private final BroadcastReceiver ringerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!AudioManager.RINGER_MODE_CHANGED_ACTION.equals(intent.getAction())) return;
            int mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            if (mode < 0) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) mode = am.getRingerMode();
            }
            if (mode < 0 || mode == lastRingerMode) return;
            lastRingerMode = mode;
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                ringerSilent = false;
            } else if (mode == AudioManager.RINGER_MODE_SILENT
                    || mode == AudioManager.RINGER_MODE_VIBRATE) {
                ringerSilent = true;
            } else {
                return;
            }
            ringerActive = showRinger;
            ringerUntil = SystemClock.uptimeMillis() + IslandSpec.RINGER_HOLD_MS;
            if (view != null) {
                view.setRingerSilent(ringerSilent);
                if (view.getMode() == IslandView.MODE_RINGER) {
                    // ring pill and silent pill have different widths; animate the relayout.
                    ensureWindow(IslandView.MODE_RINGER, false);
                    view.morphTo(IslandView.MODE_RINGER, false);
                }
            }
            recomputeState(false);
        }
    };

    /** v8.47: remember the current stream volumes so we only react to actual key/slider changes. */
    private void initVolumeBaseline() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        for (int i = 0; i < VOLUME_STREAMS.length; i++) {
            lastVolumes[i] = am.getStreamVolume(VOLUME_STREAMS[i]);
        }
    }

    /** v8.49: show/update the volume HUD for the exact stream that changed. */
    private void showVolume(int stream, int level) {
        if (!showVolume) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am != null ? Math.max(1, am.getStreamMaxVolume(stream)) : 1;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < VOLUME_STREAMS.length; i++) {
            if (VOLUME_STREAMS[i] == stream) lastVolumes[i] = level;
        }
        volumeActive = true;
        volumeStream = stream;
        volumeLevel = level;
        volumeMax = max;
        volumeUntil = now + IslandSpec.VOLUME_HOLD_MS;
        if (view != null) {
            view.setVolume(volumeStream, volumeLevel, volumeMax);
            view.invalidate();
        }
        recomputeState(false);
    }

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_VOLUME_CHANGED.equals(intent.getAction())) return;
            int stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
            int value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1);
            if (stream < 0 || value < 0) return;
            for (int s : VOLUME_STREAMS) {
                if (s == stream) {
                    showVolume(stream, value);
                    return;
                }
            }
        }
    };

    private void registerVolumeReceiver() {
        try {
            IntentFilter f = new IntentFilter(ACTION_VOLUME_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(volumeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(volumeReceiver, f);
            }
            volumeRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "volume receiver register failed", t);
        }
    }

    /** v8.47: fallback poll; only used when the hidden VOLUME_CHANGED broadcast is unavailable. */
    private void pollVolume() {
        if (destroyed) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int changed = -1;
        int changedLevel = 0;
        for (int i = 0; i < VOLUME_STREAMS.length; i++) {
            int v = am.getStreamVolume(VOLUME_STREAMS[i]);
            if (v != lastVolumes[i]) {
                lastVolumes[i] = v;
                if (changed < 0) {
                    changed = VOLUME_STREAMS[i];
                    changedLevel = v;
                }
            }
        }
        if (changed >= 0) showVolume(changed, changedLevel);
    }

    private void registerRinger() {        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        lastRingerMode = am != null ? am.getRingerMode() : -1;
        try {
            IntentFilter f = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(ringerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(ringerReceiver, f);
            }
            ringerRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "ringer receiver register failed", t);
        }
    }

    /** v8.31: lock-screen padlock and unlock animation. */
    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                boolean wasLocked = unlockPending || locked;
                locked = false;
                unlockPending = false;
                lockIgnoreUntil = Long.MAX_VALUE;
                if (wasLocked) startUnlockAnimation();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                boolean wasLocked = locked;
                locked = km != null && km.isKeyguardLocked();
                unlockPending = locked;
                lockIgnoreUntil = locked ? 0L : Long.MAX_VALUE;
                if (wasLocked && !locked) {
                    startUnlockAnimation();
                } else {
                    recomputeState(false);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                unlockAnimating = false;
                unlockHold = false;
                unlockStartPending = false;
                unlockSeq++;
                unlockRestingUntil = 0;
                unlockPending = false;
                handler.removeCallbacks(unlockTicker);
                locked = true;
                lockIgnoreUntil = 0L;
            }
        }
    };

    private void registerLock() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        locked = km != null && km.isKeyguardLocked();
        lockIgnoreUntil = locked ? 0L : Long.MAX_VALUE;        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(lockReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(lockReceiver, f);
            }
            lockRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "lock receiver register failed", t);
        }
    }

    /**
     * v8.34: some ROMs do not deliver ACTION_USER_PRESENT to a background service reliably.
     * Poll KeyguardManager from the 300 ms tick while the screen is on; a locked -> unlocked
     * transition starts the same unlock animation as the broadcast path.
     */
    private void pollKeyguard() {
        if (destroyed || !screenOn || view == null) return;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null) return;
        boolean kg = km.isKeyguardLocked();
        long now = SystemClock.uptimeMillis();
        if (kg) {
            if (!locked && !unlockAnimating && now >= lockIgnoreUntil) {
                locked = true;
                recomputeState(false);
            }
        } else if (locked) {
            locked = false;
            unlockPending = false;
            if (!unlockAnimating) {
                if (now >= lockIgnoreUntil) {
                    startUnlockAnimation();
                } else {
                    recomputeState(false);
                }
            }
        }
    }

    private final Runnable unlockTicker = new Runnable() {
        @Override public void run() {
            if (!unlockAnimating || destroyed || view == null) return;
            long elapsed = SystemClock.uptimeMillis() - unlockStart;
            int frame = (int) (elapsed * IslandSpec.UNLOCK_VIDEO_FRAMES / IslandSpec.UNLOCK_VIDEO_MS);
            if (frame > IslandSpec.UNLOCK_VIDEO_FRAMES - 1) frame = IslandSpec.UNLOCK_VIDEO_FRAMES - 1;
            view.setUnlockVideoFrame(frame);
            if (elapsed >= IslandSpec.UNLOCK_VIDEO_MS) {
                unlockAnimating = false;
                unlockHold = false;
                unlockRestingUntil = 0L;
                view.setUnlockAnim(false, 0L);
                if (notifActive && notifForceExpanded) scheduleNotifAutoHide();
                recomputeState(false);
                return;
            }
            handler.postDelayed(this, 16L);
        }
    };

    private void startUnlockAnimation() {
        if (!showUnlock) return;
        if (destroyed || unlockAnimating || unlockStartPending || view == null) return;
        lockIgnoreUntil = Long.MAX_VALUE;
        // v8.53: reserve the island for the whole unlock playback, including the 120 ms
        // window-preparation phase. While unlockHold is true no notification/volume/charging/
        // ringer state is allowed to take the island away.
        unlockHold = true;
        notifClosing = false;
        handler.removeCallbacks(notifDismissTask);
        unlockStartPending = true;
        unlockPendingSince = SystemClock.uptimeMillis();
        final int seq = ++unlockSeq;
        // v8.43: the "only half the island" bug was the view expanding while the overlay window
        // was still sized for the previous lock pill. Grow/attach the window to the full unlock
        // square first, then start the frame ticker two frames later.
        if (!attached) {
            attachOverlay(IslandView.MODE_UNLOCK);
        } else {
            ensureWindow(IslandView.MODE_UNLOCK, false);
        }
        if (attached && lp != null) {
            int[] size = view.windowSizeForMode(IslandView.MODE_UNLOCK);
            winW = size[0];
            winH = size[1];
            lp.width = size[0];
            lp.height = size[1];
            updateWindowLayout();
        }
        handler.postDelayed(() -> beginUnlockAnimation(seq), 120L);
    }

    private void beginUnlockAnimation(int seq) {
        if (destroyed || seq != unlockSeq || !unlockStartPending || view == null) return;
        unlockStartPending = false;
        unlockAnimating = true;
        unlockHold = true;
        unlockStart = SystemClock.uptimeMillis();
        if (view.isExpanded()) {
            // An existing expanded card must not block the unlock video with a collapse/fade.
            view.snapHidden();
            detachOverlay();
        }
        // Re-assert the full square window immediately before the first frame is drawn.
        if (attached && lp != null) {
            int[] size = view.windowSizeForMode(IslandView.MODE_UNLOCK);
            winW = size[0];
            winH = size[1];
            lp.width = size[0];
            lp.height = size[1];
            updateWindowLayout();
        }
        view.setUnlockAnim(true, 0L);
        view.setUnlockVideoFrame(0);
        recomputeState(false);
        handler.removeCallbacks(unlockTicker);
        handler.post(unlockTicker);
    }

    private void onChargingStarted(int level, int scale) {
        if (!showCharging) return;
        chargingLossSince = 0L;
        // Charging interrupts the preview: restore the real media/timer state first, then show it.
        if (demoMode != 0) endDemo();
        demoMode = 0;
        demoUntil = 0;
        // F-07: a new charging session must not inherit the previous session's current/fast-charge
        // reading (some ROMs return MIN_VALUE/0 for CURRENT_NOW right after plugging in, which used
        // to show "fast charging" + double bolt/gold for the first frames).
        lastChargingCurrentMa = 0;
        charging.fast = false;
        charging.superFast = false;
        // B: seed the model with the REAL battery reading (the old preview used a hard-coded fake %).
        // The level/scale passed by the battery receiver is only a fallback.
        if (!readBatterySnapshot() && level >= 0 && scale > 0) {
            charging.level = Math.round(level * 100f / scale);
            charging.preciseLevel = charging.level;
        }
        chargingActive = true;
        chargingUntil = SystemClock.uptimeMillis() + IslandSpec.CHARGE_HOLD_MS;
        updateChargingData(level, scale);
        recomputeState(false);
    }

    /** v8.40: central charging end; also clears the battery-status grace timer. */
    private void endChargingNow() {
        chargingLossSince = 0L;
        chargingActive = false;
        chargingUntil = 0L;
        recomputeState(false);
    }

    /**
     * B: read the real battery state from the sticky ACTION_BATTERY_CHANGED broadcast (level / scale)
     * and, if that is unavailable, from the integer capacity property. Never fabricates a value.
     *
     * @return true when a real percentage was applied to {@link #charging}
     */
    private boolean readBatterySnapshot() {
        int percent = -1;
        try {
            Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) percent = Math.round(level * 100f / scale);
            }
        } catch (Exception ignored) {}
        if (percent < 0) {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                if (bm != null) {
                    int capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (capacity >= 0) percent = capacity;
                }
            } catch (Exception ignored) {}
        }
        if (percent < 0) return false;
        charging.level = percent;
        float precise = readPreciseLevelPercent();
        // Real value only: the charge counter gives honest decimals when the ROM exposes it,
        // otherwise the integer level is used (never a fabricated fraction).
        charging.preciseLevel = precise > 0f ? precise : percent;
        charging.charging = true;
        return true;
    }

    /** Real percentage with decimals from the charge counter, or -1 when unavailable. */
    private float readPreciseLevelPercent() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                long counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                float capacityMah = getBatteryCapacityMahCached();
                if (counter > 0 && capacityMah > 0) {
                    float pct = counter / (capacityMah * 1000f) * 100f;
                    if (pct > 0.5f && pct <= 100.5f) return pct;
                }
            }
        } catch (Exception ignored) {}
        return -1f;
    }

    /** B: refresh the real level while the charging preview evolves (throttled to >= 1 s). */
    private void refreshRealChargingLevel() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm == null) return;
            int capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (capacity >= 0 && capacity <= 100) {
                charging.level = capacity;
                float precise = readPreciseLevelPercent();
                charging.preciseLevel = precise > 0f ? precise : capacity;
            }
        } catch (Exception ignored) {}
    }

    private void updateChargingData(int level, int scale) {
        lastBatteryPollAt = SystemClock.uptimeMillis();
        if (level >= 0 && scale > 0) {
            charging.level = Math.round(level * 100f / scale);
        }
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                long counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                float capacityMah = getBatteryCapacityMahCached();
                if (counter > 0 && capacityMah > 0) {
                    float pct = counter / (capacityMah * 1000f) * 100f;
                    if (pct > 0.5f && pct <= 100.5f) charging.preciseLevel = pct;
                } else {
                    charging.preciseLevel = charging.level;
                }
                int currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (currentUa != Integer.MIN_VALUE && currentUa != 0) {
                    int ma = Math.abs(currentUa) / 1000;
                    if (ma > 0 && ma < 30000) {
                        lastChargingCurrentMa = ma;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (charging.preciseLevel <= 0) charging.preciseLevel = charging.level;
        charging.charging = true;
        charging.superFast = lastChargingCurrentMa >= 5000;
        charging.fast = !charging.superFast && lastChargingCurrentMa >= 2000;
        if (view != null) {
            IslandData.Charging vc = view.charging();
            boolean changed = vc.charging != charging.charging
                    || vc.level != charging.level
                    || vc.preciseLevel != charging.preciseLevel
                    || vc.fast != charging.fast
                    || vc.superFast != charging.superFast;
            vc.charging = charging.charging;
            vc.level = charging.level;
            vc.preciseLevel = charging.preciseLevel;
            vc.fast = charging.fast;
            vc.superFast = charging.superFast;
            if (changed && view.isVisible()) view.invalidate();
        }
    }

    private float getBatteryCapacityMahCached() {
        if (batteryCapacityMahCache > 0) return batteryCapacityMahCache;
        try {
            Class<?> pp = Class.forName("com.android.internal.os.PowerProfile");
            Object profile = pp.getConstructor(Context.class).newInstance(this);
            java.lang.reflect.Method m = pp.getMethod("getBatteryCapacity");
            Object v = m.invoke(profile);
            if (v instanceof Double) {
                batteryCapacityMahCache = ((Double) v).floatValue();
                return batteryCapacityMahCache;
            }
        } catch (Throwable ignored) {}
        batteryCapacityMahCache = 0f;
        return 0f;
    }

    // =========================================================================
    // system clock timer / stopwatch
    // =========================================================================
    private boolean isClockPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.contains("deskclock") || p.contains("clock") || p.contains("alarm")
                || p.contains("timer");
    }

    private boolean looksLikeTimer(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        Bundle ex = n.extras;
        boolean chronometer = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                || ex.getBoolean("android.chronometerCountDown", false);
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
        CharSequence info = ex.getCharSequence(Notification.EXTRA_INFO_TEXT);
        String t = (title == null ? "" : title.toString());
        String s = (text == null ? "" : text.toString());
        String st = sub == null ? "" : sub.toString();
        String it = info == null ? "" : info.toString();
        String pkg = sbn.getPackageName();
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        boolean clock = isClockPackage(pkg);
        String lt = t.toLowerCase(Locale.US);
        boolean keyword = t.contains("秒表") || t.contains("计时") || st.contains("秒表")
                || st.contains("计时") || it.contains("秒表") || it.contains("计时")
                || lt.contains("stopwatch") || lt.contains("timer")
                || s.contains("秒表") || s.contains("计时")
                || t.contains("倒计时") || s.contains("倒计时");
        boolean alarm = t.contains("闹钟") || s.contains("闹钟") || st.contains("闹钟")
                || it.contains("闹钟") || lt.contains("alarm")
                || Notification.CATEGORY_ALARM.equals(n.category);
        // Some OEM clocks tag timer notifications with the stopwatch category, or only put the
        // remaining time in the title/text (e.g. "05:00") without EXTRA_SHOW_CHRONOMETER.
        boolean timerCategory = "stopwatch".equals(n.category) || "timer".equals(n.category);
        boolean timeText = TIME_TEXT_PATTERN.matcher(t).find()
                || TIME_TEXT_PATTERN.matcher(s).find()
                || TIME_TEXT_PATTERN.matcher(st).find()
                || TIME_TEXT_PATTERN.matcher(it).find();
        // v8.2: the user can explicitly mark their Clock/timer app (dynamicSpot-style per-app
        // timer flag). Any non-alarm notification from that package is then a timer notification,
        // even if the ROM strips its title/text.
        String selectedTimerApp = getSharedPreferences("island", MODE_PRIVATE)
                .getString("timer_app_pkg", "");
        if (!selectedTimerApp.isEmpty() && selectedTimerApp.equals(pkg) && !alarm) return true;
        if (keyword || timerCategory) return true;
        if (clock && !alarm && (chronometer || timeText)) return true;
        // Some ROMs strip notification extras for third-party listeners; the Clock app's timer
        // still exposes 计次/暂停/继续 actions even when title/text are empty.
        if (clock && !alarm && n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.title == null) continue;
                String at = a.title.toString().toLowerCase(Locale.US);
                if (at.contains("计次") || at.contains("暂停") || at.contains("继续")
                        || at.contains("重置") || at.contains("lap") || at.contains("pause")
                        || at.contains("resume") || at.contains("start") || at.contains("开始")) {
                    return true;
                }
            }
        }
        // vivo's timer widget sometimes posts only a persistent notification with sparse text.
        boolean timerPkg = p.contains("timer") || p.contains("stopwatch");
        if (timerPkg && !alarm
                && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return true;
        return false;
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || destroyed) return;
        // v3.8: never treat our own foreground notification as a timer. Its text contains
        // "计时器" ("充电 / 计时器 / 音乐效果"), which used to make looksLikeTimer() return true and
        // overwrite the timer title with "灵动岛正在运行".
        if (getPackageName().equals(sbn.getPackageName())) return;
        // v8.3: the timer island has been removed. Swallow Clock/stopwatch notifications here so
        // they do not fall through and appear as a normal message notification.
        if (looksLikeTimer(sbn)) return;
        // v3.3 A: every other notification may pop the notification island.
        onNotificationArrived(sbn);
    }

    private void onTimerNotificationPosted(StatusBarNotification sbn) {
        // A real timer showed up: leave the preview and let the notification data take over.
        if (demoMode != 0) endDemo();
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        boolean countDown = ex.getBoolean("android.chronometerCountDown", false);
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = ex.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
        String shownTitle = title != null ? title.toString().trim() : "";
        String shownText = text != null ? text.toString().trim() : "";
        if (shownTitle.isEmpty() && sub != null) shownTitle = sub.toString().trim();
        if (shownTitle.isEmpty()) shownTitle = "计时器";

        timerActive = true;
        timer.title = shownTitle;
        timer.countDown = countDown;
        timer.when = n.when;
        timer.packageName = sbn.getPackageName();
        timerKey = sbn.getKey();
        pendingTimerRemoveKey = null;
        handler.removeCallbacks(timerRemoveTask);
        boolean chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false);
        // v7.9: OEM Clock apps (e.g. vivo BBKClock's stopwatch, title "秒表") often omit
        // EXTRA_SHOW_CHRONOMETER; infer the running state from the notification actions instead.
        boolean pauseAction = false;
        boolean resumeAction = false;
        Notification.Action[] actions = n.actions;
        if (actions != null) {
            for (Notification.Action a : actions) {
                if (a == null || a.title == null) continue;
                String at = a.title.toString().toLowerCase(Locale.US);
                if (at.contains("暂停") || at.contains("pause")) pauseAction = true;
                if (at.contains("继续") || at.contains("恢复") || at.contains("resume")) {
                    resumeAction = true;
                }
            }
        }
        boolean stopwatchTitle = shownTitle.contains("秒表")
                || shownTitle.toLowerCase(Locale.US).contains("stopwatch");
        timer.running = chrono || (n.when > 0
                && (pauseAction || (stopwatchTitle && !resumeAction)));
        if (timer.running && n.when > 0) {
            timer.tick(System.currentTimeMillis());
        } else if (HAS_TIME_PATTERN.matcher(shownText).matches()) {
            java.util.regex.Matcher m = TIME_TEXT_PATTERN.matcher(shownText);
            if (m.find()) timer.digits = m.group(1);
        } else {
            timer.tick(System.currentTimeMillis());
        }
        // C: keep up to two Clock actions for the two round buttons of the expanded card.
        fillTimerActions(n);
        syncModelsToView();
        recomputeState(false);
    }

    /** C: store the first two notification actions (pause / resume on stock Clock apps). */
    private void fillTimerActions(Notification n) {
        timer.actionCount = 0;
        for (int i = 0; i < timer.actionTitles.length; i++) {
            timer.actionTitles[i] = null;
            timer.actionIntents[i] = null;
        }
        Notification.Action[] actions = n.actions;
        if (actions == null) return;
        for (int i = 0; i < actions.length && timer.actionCount < timer.actionIntents.length; i++) {
            Notification.Action action = actions[i];
            if (action == null || action.actionIntent == null) continue;
            timer.actionTitles[timer.actionCount] =
                    action.title == null ? "" : action.title.toString();
            timer.actionIntents[timer.actionCount] = action.actionIntent;
            timer.actionCount++;
        }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || destroyed) return;
        String key = sbn.getKey();
        if (timerKey != null && timerKey.equals(key)) {
            // Delay the clear: some OEM clocks update the stopwatch by remove+re-post, and an
            // immediate clear after the new post arrived would leave the island permanently empty.
            pendingTimerRemoveKey = key;
            handler.removeCallbacks(timerRemoveTask);
            handler.postDelayed(timerRemoveTask, 1200L);
        }
        if (currentNotifKey != null && currentNotifKey.equals(key)) {
            // v3.6: QQ/WeChat update a group notification by removing + re-posting it. Hiding
            // immediately made the island flash through an empty pill; wait a moment so a quick
            // re-post can update the existing card in place instead.
            handler.removeCallbacks(notifAutoExpandTask);
            notifAutoExpandPending = false;
            scheduleNotificationDismiss();
        }
    }

    // ======================================================================
    // v3.3 A: notification island (dynamicSpot popup_first / popup_big style)
    // ======================================================================

    /** A: filter + fill + show/update the notification island for one posted notification. */
    private void onNotificationArrived(StatusBarNotification sbn) {
        if (!showNotifications) return;
        if (!shouldShowNotification(sbn)) return;
        if (!isNotificationAppAllowed(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        notifClosing = false; // a new notification supersedes any in-flight close animation
        // v3.6: a new notification supersedes a pending removal (remove+re-post update pattern).
        handler.removeCallbacks(notifDismissTask);
        if (demoMode != 0) endDemo();
        fillNotification(sbn, n);
        boolean newNotificationKey = currentNotifKey == null
                || !currentNotifKey.equals(notif.key);
        notifActive = true;
        expandedAt = SystemClock.uptimeMillis();
        // v8.15: always show the expanded card on the very first notification. Some messaging
        // apps do not set CATEGORY_MESSAGE / RemoteInput / messages extras, so the earlier
        // "message detection" left the first message as a compact pill and only expanded on the
        // second one. Expanding unconditionally removes that dependency.
        notifForceExpanded = true;
        notifUntil = SystemClock.uptimeMillis() + IslandSpec.NOTIF_HOLD_MS;
        // v8.27: a genuinely new notification restarts the 4 s clock; updates of the same
        // notification keep the original deadline so the card cannot be kept alive forever.
        if (newNotificationKey) cancelNotifAutoHide();
        scheduleNotifAutoHide();
        currentNotifKey = notif.key;
        demoMode = 0;
        demoUntil = 0;
        syncModelsToView();
        if (view == null) return;
        int mode = view.getMode();
        if (mode == IslandView.MODE_NOTIF_COMPACT || mode == IslandView.MODE_NOTIF_EXPANDED) {
            // Same notification card already up. v8.8: a compact card is promoted to expanded
            // immediately instead of staying as a small capsule.
            if (mode == IslandView.MODE_NOTIF_COMPACT) {
                extendedAtToExpanded();
            } else {
                repairNotificationContentIfBlank("notif-repost");
                extendNotifHoldForExpanded();
                view.refreshFrame();
                ensureWindow(IslandView.MODE_NOTIF_EXPANDED, false);
                if (view.isVisible()) view.invalidate();
            }
        } else {
            recomputeState(false);
            if (view != null && view.getMode() == IslandView.MODE_NOTIF_EXPANDED) {
                extendNotifHoldForExpanded();
            }
        }
    }

    /** v8.13: messaging-style notifications auto-expand on the very first message. */
    private static boolean isMessageNotification(String pkg, Notification n) {
        if (isMessagingPackage(pkg)) return true;
        if (n == null) return false;
        if (Notification.CATEGORY_MESSAGE.equals(n.category)
                || Notification.CATEGORY_EMAIL.equals(n.category)) return true;
        if (n.getShortcutId() != null) return true;
        if (n.extras != null && (n.extras.containsKey("android.messages")
                || n.extras.containsKey("android.people.list"))) return true;
        Notification.Action[] actions = n.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                if (action == null) continue;
                android.app.RemoteInput[] inputs = action.getRemoteInputs();
                if (inputs != null && inputs.length > 0) return true;
            }
        }
        return false;
    }

    /** v8.16: common chat apps that should always expose the forced-reply button. */
    private static boolean isMessagingPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.US);
        return p.equals("com.tencent.mm")
                || p.contains("mobileqq") || p.contains("tencent.tim")
                || p.contains("wechat") || p.contains("weixin")
                || p.contains("wework") || p.contains("dingtalk")
                || p.contains("whatsapp") || p.contains("telegram")
                || p.contains("lark") || p.contains("feishu")
                || p.contains("discord") || p.contains("line.");
    }

    /** v8.8: compact notification -> expanded card without ever showing a notification pill. */
    private void extendedAtToExpanded() {
        if (view == null) return;
        expandedAt = SystemClock.uptimeMillis();
        extendNotifHoldForExpanded();
        ensureWindow(IslandView.MODE_NOTIF_EXPANDED, false);
        view.morphTo(IslandView.MODE_NOTIF_EXPANDED, true);
    }

    private void extendNotifHoldForExpanded() {
        if (notifActive) notifUntil = SystemClock.uptimeMillis() + NOTIF_EXPANDED_HOLD_MS;
    }

    /** v8.21: auto-close after 4 s; updates of the same notification do not extend it. */
    private void scheduleNotifAutoHide() {
        if (notifAutoHideScheduled) return;
        notifAutoHideScheduled = true;
        handler.removeCallbacks(notifAutoHideTask);
        handler.postDelayed(notifAutoHideTask, 4000L);
    }

    private void cancelNotifAutoHide() {
        notifAutoHideScheduled = false;
        handler.removeCallbacks(notifAutoHideTask);
    }

    /** v8.4: request an automatic notification-card expansion after a new notification appears. */
    private void scheduleNotifAutoExpand() {
        notifAutoExpandPending = true;
        notifAutoExpandTries = 0;
        handler.removeCallbacks(notifAutoExpandTask);
        handler.postDelayed(notifAutoExpandTask, 240L);
    }

    /** A: central notification filter (see dynamicSpot NotificationObserverSpot#w). */
    private boolean shouldShowNotification(StatusBarNotification sbn) {
        if (sbn == null) return false;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return false;
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(getPackageName())) return false;              // our own foreground notification
        if ("android".equals(pkg)) return false;                     // low level system notifications
        long postTime = sbn.getPostTime();
        if (postTime > 0 && postTime < serviceStartWallMs) return false; // replayed old notifications
        // Group summaries are noise: only show the actual content notification of a group.
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        // Music is rendered by the media mode; never pop a notification for the current player.
        if (mediaController != null && pkg.equals(mediaController.getPackageName())) return false;
        Bundle extras = n.extras;
        String template = extras.getString("android.template", "");
        if (template != null && template.contains("MediaStyle")) return false;
        String title = charSeq(extras.getCharSequence(Notification.EXTRA_TITLE)).trim();
        String text = charSeq(extras.getCharSequence(Notification.EXTRA_TEXT)).trim();
        String bigText = charSeq(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).trim();
        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return false;
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        boolean message = Notification.CATEGORY_MESSAGE.equals(n.category);
        if (!sbn.isClearable() && !call && !message) return false;   // persistent non-call/message
        return true;
    }

    private static String charSeq(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    /**
     * v7.6: app whitelist for the *message* notification island. A missing key means "all apps"
     * (existing behaviour); once the user saves a selection, only those packages may pop.
     * Timer notifications are handled before this filter, so Clock/stopwatch cards still work.
     */
    private boolean isNotificationAppAllowed(String pkg) {
        Set<String> selected = getSharedPreferences("island", MODE_PRIVATE)
                .getStringSet("notif_apps", null);
        return selected == null || selected.contains(pkg);
    }

    private void fillNotification(StatusBarNotification sbn, Notification n) {
        Bundle extras = n.extras;
        String newKey = sbn.getKey() == null ? "" : sbn.getKey();
        String newPkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        boolean keyChanged = !newKey.equals(notif.key);
        boolean sameApp = newPkg.equals(notif.packageName);
        // v3.6: keep the previous icon when only the notification key changed within the same app
        // (group updates); the new icon replaces it when loaded, so the pill never flashes a
        // letter placeholder between two messages.
        if (keyChanged && !sameApp) notif.icon = null;
        notif.key = newKey;
        notif.packageName = newPkg;
        notif.appName = appLabel(notif.packageName);
        notif.title = charSeq(extras.getCharSequence(Notification.EXTRA_TITLE)).trim();
        String text = charSeq(extras.getCharSequence(Notification.EXTRA_TEXT)).trim();
        if (text.isEmpty()) {
            text = charSeq(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).trim();
        }
        notif.text = text;
        notif.postedAt = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        notif.timeText = formatNotifTime(notif.postedAt);
        notif.contentIntent = n.contentIntent;
        notif.actionCount = 0;
        notif.replyActionIndex = -1;
        notif.replyInputs = null;
        notif.forceReplyActionIndex = -1;
        for (int i = 0; i < notif.actionTitles.length; i++) {
            notif.actionTitles[i] = null;
            notif.actionIntents[i] = null;
        }
        Notification.Action[] actions = n.actions;
        if (actions != null) {
            for (Notification.Action action : actions) {
                if (action == null || action.actionIntent == null) continue;
                // v8.24: reply feature removed. Skip RemoteInput actions entirely; do not show a
                // "回复" button and do not send those actions without their input results.
                android.app.RemoteInput[] remoteInputs = action.getRemoteInputs();
                if (remoteInputs != null && remoteInputs.length > 0) continue;
                if (notif.actionCount >= notif.actionIntents.length) break;
                notif.actionTitles[notif.actionCount] =
                        action.title == null ? "" : action.title.toString();
                notif.actionIntents[notif.actionCount] = action.actionIntent;
                notif.actionCount++;
            }
        }
        if (keyChanged || notif.icon == null) {
            requestNotificationIcon(n, notif.packageName, notif.key);
        }
    }

    private static String formatNotifTime(long wallMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(wallMs);
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    /** A: notification app label, cached (<= 32 entries, bounded like the reference). */
    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String cached = appLabelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence value = pm.getApplicationLabel(info);
            if (value != null && value.length() > 0) label = value.toString();
        } catch (Throwable ignored) {}
        if (label.length() > 32) label = label.substring(0, 32);
        if (appLabelCache.size() >= APP_LABEL_CACHE_MAX) appLabelCache.clear();
        appLabelCache.put(pkg, label);
        return label;
    }

    /**
     * A/11: load the notification icon (large icon first, app icon otherwise) on the worker thread
     * and downscale it to <= {@link #NOTIF_ICON_MAX_PX}. The bitmap currently drawn by the view is
     * never recycled — it is just dropped and left to the GC, so the view can never draw a recycled
     * bitmap.
     */
    /**
     * v7.0: load the media app's launcher icon for the no-album-art fallback. Cached alongside the
     * notification app icons; never recycled while the view may draw it.
     */
    private void requestMusicAppIcon(final String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            music.appIcon = null;
            return;
        }
        Bitmap cached = appIconCache.get(pkg);
        if (cached != null && !cached.isRecycled()
                && cached.getWidth() >= 256 && cached.getHeight() >= 256) {
            music.appIcon = cached;
            syncModelsToView();
            if (view != null && view.isVisible()) view.invalidate();
            return;
        }
        final int seq = ++musicIconRequestSeq;
        ExecutorService executor = colorExecutor;
        if (executor == null) return;
        executor.execute(() -> {
            Bitmap created = null;
            try {
                android.graphics.drawable.Drawable drawable =
                        getPackageManager().getApplicationIcon(pkg);
                created = drawableToIconBitmap(drawable, ARTWORK_MAX_PX);
            } catch (Throwable t) {
                Log.w(TAG, "music app icon failed for " + pkg, t);
            }
            final Bitmap result = created;
            handler.post(() -> {
                if (destroyed || seq != musicIconRequestSeq) {
                    return;
                }
                if (!pkg.equals(music.packageName)) return;
                if (result == null || result.isRecycled()) return;
                if (appIconCache.size() >= APP_ICON_CACHE_MAX) appIconCache.clear();
                appIconCache.put(pkg, result);
                music.appIcon = result;
                syncModelsToView();
                if (view != null && view.isVisible()) view.invalidate();
            });
        });
    }

    private void requestNotificationIcon(Notification n, String pkg, String key) {
        final long seq = ++iconRequestSeq;
        final String expectedKey = key == null ? "" : key;
        final Icon largeIcon = n.getLargeIcon();
        final Bitmap cachedAppIcon = largeIcon == null ? appIconCache.get(pkg) : null;
        if (cachedAppIcon != null && !cachedAppIcon.isRecycled()) {
            notif.icon = cachedAppIcon;
            return;
        }
        ExecutorService executor = colorExecutor;
        if (executor == null) return;
        executor.execute(() -> {
            Bitmap bitmap = null;
            boolean fromAppIcon = false;
            try {
                Drawable drawable = largeIcon != null ? largeIcon.loadDrawable(IslandService.this) : null;
                if (drawable == null && pkg != null && !pkg.isEmpty()) {
                    drawable = getPackageManager().getApplicationIcon(pkg);
                    fromAppIcon = true;
                }
                bitmap = drawableToIconBitmap(drawable, NOTIF_ICON_MAX_PX);
            } catch (Throwable t) {
                Log.w(TAG, "notification icon failed", t);
            }
            final Bitmap created = bitmap;
            final boolean cacheAsAppIcon = fromAppIcon;
            final String iconPkg = pkg;
            handler.post(() -> {
                if (destroyed || created == null) return;
                if (cacheAsAppIcon && iconPkg != null) {
                    if (appIconCache.size() >= APP_ICON_CACHE_MAX) appIconCache.clear();
                    appIconCache.put(iconPkg, created);
                }
                if (seq != iconRequestSeq) return;
                if (!expectedKey.equals(notif.key)) return;   // newer notification took over
                notif.icon = created;
                syncModelsToView();
                if (view != null && view.isVisible()) view.invalidate();
            });
        });
    }

    private static Bitmap drawableToIconBitmap(Drawable drawable, int maxPx) {
        if (drawable == null) return null;
        try {
            if (drawable instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    return scaleIconBitmap(bitmap, maxPx);
                }
            }
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                width = maxPx;
                height = maxPx;
            }
            float scale = Math.min(1f, maxPx / (float) Math.max(width, height));
            int outW = Math.max(1, Math.round(width * scale));
            int outH = Math.max(1, Math.round(height * scale));
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            drawable.setBounds(0, 0, outW, outH);
            drawable.draw(canvas);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "icon conversion failed", t);
            return null;
        }
    }

    private static Bitmap scaleIconBitmap(Bitmap source, int maxPx) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return source;
        float scale = Math.min(1f, maxPx / (float) Math.max(width, height));
        if (scale >= 1f) return source;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    /** A: fire a PendingIntent defensively; returns false if it was cancelled/failed. */
    private boolean sendPendingIntent(PendingIntent intent) {
        if (intent == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ background-activity-launch restrictions can silently swallow a
                // notification contentIntent sent from a service/overlay. Allow this specific
                // user-initiated pending intent to start its activity.
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                intent.send(this, 0, null, null, null, null, opts.toBundle());
            } else {
                intent.send();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "PendingIntent.send failed", e);
            return false;
        }
    }

    /** v3.6: delayed hide so a re-posted group notification can update the card in place. */
    private void scheduleNotificationDismiss() {
        // v8.17: an expanded notification card is sticky. Apps that update notifications by
        // remove+re-post used to trigger dismiss/re-expand loops that made the message unreadable.
        // v8.18: also skip while the card is still expanding (notifForceExpanded is already true).
        if (notifForceExpanded) return;
        if (view != null && view.getMode() == IslandView.MODE_NOTIF_EXPANDED) {
            return;
        }
        handler.removeCallbacks(notifDismissTask);
        long delay = (view != null && view.getMode() == IslandView.MODE_NOTIF_EXPANDED)
                ? 1200L : NOTIF_DISMISS_DELAY_MS;
        handler.postDelayed(notifDismissTask, delay);
    }

    /** Close the notification card with the normal collapse shape but no spring rebound. */
    private void dismissNotificationIsland() {
        if (unlockHold) {
            // v8.53: never hide the unlock video because a notification timer/action fired.
            return;
        }
        handler.removeCallbacks(notifDismissTask);
        handler.removeCallbacks(notifAutoExpandTask);
        cancelNotifAutoHide();
        notifAutoExpandPending = false;
        hideNotificationReplyInput();
        notifForceExpanded = false;
        notifActive = false;
        notifUntil = 0;
        currentNotifKey = null;
        notifClosing = true;
        if (view == null) {
            notifCloseTarget = IslandView.MODE_HIDDEN;
            finalizeNotificationClose();
            return;
        }
        // v8.28: when an idle/always-on capsule exists underneath, collapse straight into it.
        // Do not hide to the dot and detach first — that made the always-on island disappear and
        // pop back after every notification.
        int target = desiredMode();
        notifCloseTarget = target;
        if (target == IslandView.MODE_HIDDEN) {
            view.hideNoBounce();
        } else {
            ensureWindow(target, false);
            view.morphToNoBounce(target);
        }
    }

    /** v8.22/v8.28: after the no-bounce collapse, drop the notification model. */
    private void finalizeNotificationClose() {
        notifClosing = false;
        iconRequestSeq++;
        notif.reset();
        syncModelsToView();
        int target = notifCloseTarget;
        if (view == null) {
            detachOverlay();
            return;
        }
        if (target == IslandView.MODE_HIDDEN) {
            view.snapHidden();
            detachOverlay();
            if (!destroyed) recomputeState(false);
            return;
        }
        // Always-on / music / charging underneath: keep the same window attached and settle on
        // that mode directly. No dot, no detach, no re-show.
        ensureWindow(target, true);
        view.refreshFrame();
        view.snapToMode(target);
        if (view.isVisible()) view.resumeRendering();
        if (!destroyed) recomputeState(false);
    }

    // ==========================================================================
    // music
    // ==========================================================================
    private void setupMediaSessions() {
        // F-06: this call supersedes any pending retry (listener may have just connected).
        handler.removeCallbacks(mediaSetupRetryTask);
        mediaSetupRetryScheduled = false;
        try {
            mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            listenerComponent = new ComponentName(this, IslandNotificationListener.class);
            if (mediaSessionManager == null) return;
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {}
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent);
            onActiveSessionsChanged(mediaSessionManager.getActiveSessions(listenerComponent));
            mediaSetupRetryCount = 0;
        } catch (SecurityException e) {
            // The notification listener is not bound yet (typical BOOT_COMPLETED race): retry a few
            // times; onNotificationListenerConnected() triggers an immediate attempt as well.
            Log.w(TAG, "no notification listener permission yet: " + e.getMessage());
            scheduleMediaSetupRetry();
        } catch (Exception e) {
            Log.w(TAG, "media session setup failed", e);
            scheduleMediaSetupRetry();
        }
    }

    private void scheduleMediaSetupRetry() {
        if (destroyed || mediaSetupRetryScheduled || mediaSetupRetryCount >= MEDIA_SETUP_RETRY_MAX) return;
        mediaSetupRetryScheduled = true;
        mediaSetupRetryCount++;
        handler.postDelayed(mediaSetupRetryTask, MEDIA_SETUP_RETRY_MS);
    }

    private final Runnable mediaSetupRetryTask = new Runnable() {
        @Override public void run() {
            mediaSetupRetryScheduled = false;
            if (destroyed || foregroundFailed) return;
            setupMediaSessions();
        }
    };

    /** F-06: called by IslandNotificationListener.onListenerConnected() (main thread). */
    public static void onNotificationListenerConnected() {
        IslandService s = instance;
        if (s == null || s.destroyed) return;
        s.mediaSetupRetryCount = 0;
        s.handler.post(s::setupMediaSessions);
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> postToMain(() -> onActiveSessionsChanged(controllers));

    /** S7: media-session callbacks are not guaranteed to arrive on the main thread. */
    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }

    private void onActiveSessionsChanged(List<MediaController> controllers) {
        if (destroyed || view == null) return;
        MediaController previous = mediaController;
        List<MediaController> sessions = controllers == null ? java.util.Collections.emptyList() : controllers;
        MediaController chosen = chooseController(sessions, previous);
        if (chosen == previous && chosen != null) {
            // Same controller: keep the registered callback (sessions listener fires on every
            // session state change) and only refresh the data.
            applyMediaState();
            return;
        }
        if (previous != null) {
            try { previous.unregisterCallback(mediaCallback); } catch (Exception ignored) {}
        }
        mediaController = chosen;
        if (chosen == null) {
            // S1: no usable session any more -> the paused/old capsule must disappear.
            clearMediaState();
            return;
        }
        try { chosen.registerCallback(mediaCallback); } catch (Exception ignored) {}
        applyMediaState();
    }

    /**
     * S1: prefer a playing/buffering session, keep the current one while it is still listed,
     * otherwise fall back to a paused one that actually has metadata. Sessions without metadata
     * (phone calls, one-shot notification players…) are ignored so the island does not pop up
     * for them.
     */
    private MediaController chooseController(List<MediaController> sessions, MediaController current) {
        MediaController pausedWithMetadata = null;
        for (MediaController c : sessions) {
            if (c == null) continue;
            PlaybackState st = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            if (st == null || md == null) continue;
            int state = st.getState();
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                return c;
            }
            if (pausedWithMetadata == null
                    && (state == PlaybackState.STATE_PAUSED
                        || state == PlaybackState.STATE_FAST_FORWARDING
                        || state == PlaybackState.STATE_REWINDING
                        || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                        || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS)) {
                pausedWithMetadata = c;
            }
        }
        if (current != null && sessions.contains(current)) {
            MediaMetadata md = current.getMetadata();
            if (md != null) return current;
        }
        return pausedWithMetadata;
    }

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            postToMain(IslandService.this::applyMediaState);
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            postToMain(IslandService.this::applyMediaState);
        }

        @Override public void onSessionDestroyed() {
            // S1: re-query instead of guessing — another session may still be playing.
            postToMain(IslandService.this::requeryActiveSessions);
        }
    };

    private void requeryActiveSessions() {
        if (destroyed || view == null) return;
        try {
            if (mediaSessionManager == null || listenerComponent == null) {
                onActiveSessionsChanged(null);
                return;
            }
            onActiveSessionsChanged(mediaSessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "lost media session access: " + e.getMessage());
            onActiveSessionsChanged(null);
        } catch (Exception e) {
            Log.w(TAG, "media session re-query failed", e);
        }
    }

    private void applyMediaState() {
        if (destroyed || mediaController == null) return;
        MediaMetadata md = mediaController.getMetadata();
        PlaybackState st = mediaController.getPlaybackState();
        if (md == null || st == null) {
            musicActive = false;
            if (demoMode != 3) clearMediaState();
            else recomputeState(false);
            return;
        }
        int stState = st.getState();
        boolean playing = stState == PlaybackState.STATE_PLAYING
                || stState == PlaybackState.STATE_BUFFERING;
        boolean present = playing
                || stState == PlaybackState.STATE_PAUSED
                || stState == PlaybackState.STATE_FAST_FORWARDING
                || stState == PlaybackState.STATE_REWINDING
                || stState == PlaybackState.STATE_SKIPPING_TO_NEXT
                || stState == PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
        music.title = firstNonEmpty(
                md.getString(MediaMetadata.METADATA_KEY_TITLE),
                md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), "Unknown track");
        music.artist = firstNonEmpty(
                md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM), "");
        music.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        // S24: a playing player updates its PlaybackState ~1x/s, and every getMetadata() call
        // returns a fresh MediaMetadata/Bundle whose getBitmap() yields a NEW Bitmap instance. A
        // stable textual key lets us skip both the (binder-deserialising) getBitmap() call and the
        // whole downscale/accent pass while the same track keeps playing.
        String newArtworkKey = buildArtworkKey(md);
        boolean artKeyChanged = !newArtworkKey.equals(artworkKey);
        if (artKeyChanged && !newArtworkKey.equals(pendingArtworkKey)) {
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != null) {
                // P-05/L-01: real artwork wins over a demo cover. The metadata bitmap is only handed
                // to the worker thread (never kept/recycled by us); the island draws an app-owned
                // <= ARTWORK_MAX_PX thumbnail instead of scaling the full-size bitmap every frame.
                discardDemoArtwork();
                // Do NOT drop the previously displayed art here: the old thumbnail stays on screen
                // until the new one is ready (no fallback-colour flash between tracks).
                requestArtworkThumbnail(art, newArtworkKey);
            } else {
                discardArtworkThumb();
                discardDemoArtwork();
                // Remember the "no artwork" key as resolved so this track does not re-enter
                // getBitmap() on every playback-state update.
                artworkKey = newArtworkKey;
                music.artwork = null;
                music.accent = IslandSpec.MUSIC_GLOW_DEFAULT;
            }
        }
        music.playing = playing;
        music.positionMs = st.getPosition();
        music.positionAtUptime = st.getLastPositionUpdateTime() > 0
                ? st.getLastPositionUpdateTime() : SystemClock.elapsedRealtime();
        // v5.1: remember the source app so tapping the expanded music body can open it.
        try {
            music.packageName = firstNonEmpty(mediaController.getPackageName(), "");
        if (!music.packageName.equals(musicIconPackage)) {
            musicIconPackage = music.packageName;
            music.appIcon = null;
            requestMusicAppIcon(musicIconPackage);
        }
        } catch (Throwable ignored) {
            music.packageName = "";
            musicIconPackage = "";
            musicIconRequestSeq++;
            music.appIcon = null;
        }
        syncModelsToView();
        musicActive = present;
        if (present) {
            demoMode = 0;
            demoUntil = 0;
        }
        recomputeState(false);
        // The view stops its vsync loop while visually static, so nudge it when the data changed
        // (a paused -> playing transition has to restart the waveform animation).
        if (view != null && view.isVisible()) view.resumeRendering();
    }

    /** S24: stable identity of the album art; unchanged while the same track keeps playing. */
    private static String buildArtworkKey(MediaMetadata md) {
        return firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE),
                        md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), "")
                + '\u0001'
                + firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                        md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), "")
                + '\u0001'
                + firstNonEmpty(md.getString(MediaMetadata.METADATA_KEY_ALBUM), "")
                + '\u0001'
                + md.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)
                + '\u0001'
                + md.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    /**
     * S24/P-05/L-01: downscale the album art (longest side <= {@link #ARTWORK_MAX_PX}) and extract
     * its glow colour in one worker-thread pass. Only the resulting bitmap is owned by the service;
     * the framework metadata bitmap is never kept nor recycled. The previously displayed thumbnail
     * stays visible (and owned) until the new one has been computed — no fallback-colour flash.
     */
    private void requestArtworkThumbnail(Bitmap source, String newKey) {
        if (source == null || source.isRecycled()) return;
        final long seq = ++artworkRequestSeq;
        pendingArtworkKey = newKey;
        ExecutorService executor = colorExecutor;
        if (executor == null) {
            pendingArtworkKey = null;
            return;
        }
        executor.execute(() -> {
            Bitmap thumb = null;
            boolean owned = false;
            int accent = IslandSpec.MUSIC_GLOW_DEFAULT;
            try {
                int w = source.getWidth();
                int h = source.getHeight();
                if (w > 0 && h > 0) {
                    float scale = Math.min(1f, ARTWORK_MAX_PX / (float) Math.max(w, h));
                    if (scale < 1f) {
                        int tw = Math.max(1, Math.round(w * scale));
                        int th = Math.max(1, Math.round(h * scale));
                        thumb = Bitmap.createScaledBitmap(source, tw, th, true);
                        owned = thumb != source;
                    } else {
                        // Already small enough, but still keep our own copy so the island never
                        // holds the framework's metadata bitmap (players may recycle it).
                        try {
                            thumb = source.copy(Bitmap.Config.ARGB_8888, false);
                        } catch (Throwable ignored) {
                            thumb = null;
                        }
                        if (thumb == null) thumb = source; // displayable fallback: never recycled
                        owned = thumb != source;
                    }
                    accent = averageColor(thumb);
                }
            } catch (Throwable t) {
                Log.w(TAG, "album-art thumbnail failed", t);
            }
            final Bitmap created = thumb;
            final boolean createdOwned = owned;
            final int color = accent;
            handler.post(() -> {
                if (newKey.equals(pendingArtworkKey)) pendingArtworkKey = null;
                boolean stale = destroyed || seq != artworkRequestSeq || view == null;
                if (stale) {
                    if (createdOwned && created != null && !created.isRecycled()) created.recycle();
                    return;
                }
                if (created == null) {
                    // Downscale failed: keep the current art, but remember the key so this track
                    // does not retry the whole pass on every playback-state update.
                    artworkKey = newKey;
                    return;
                }
                Bitmap previous = artworkThumb;
                boolean previousOwned = artworkThumbOwned;
                artworkThumb = created;
                artworkThumbOwned = createdOwned;
                artworkKey = newKey;
                music.artwork = created;
                music.accent = color;
                syncModelsToView();
                // The old thumbnail is only released after the view has been pointed at the new one.
                if (previous != null && previous != created && previousOwned
                        && !previous.isRecycled()) {
                    previous.recycle();
                }
                if (view.isVisible()) view.resumeRendering();
                recomputeState(false);
            });
        });
    }

    /** Recycle the app-owned album-art thumbnail (never a thumbnail that is the metadata bitmap). */
    private void discardArtworkThumb() {
        artworkRequestSeq++;
        artworkKey = null;
        pendingArtworkKey = null;
        Bitmap doomed = artworkThumb;
        boolean owned = artworkThumbOwned;
        artworkThumb = null;
        artworkThumbOwned = false;
        if (doomed == null) return;
        if (music.artwork == doomed) music.artwork = null;
        if (view != null && view.music().artwork == doomed) view.music().artwork = null;
        if (owned && !doomed.isRecycled()) doomed.recycle();
    }

    private int averageColor(Bitmap bmp) {
        try {
            if (bmp == null || bmp.isRecycled()) return IslandSpec.MUSIC_GLOW_DEFAULT;
            Bitmap small = Bitmap.createScaledBitmap(bmp, 8, 8, true);
            long r = 0, g = 0, bl = 0;
            int n = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int c = small.getPixel(x, y);
                    r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++;
                }
            }
            if (small != bmp) small.recycle();
            int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (bl / n);
            // saturate / brighten a bit so the ambient glow is visible on black
            float[] hsv = new float[3];
            Color.RGBToHSV(rr, gg, bb, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.35f + 0.12f);
            hsv[2] = Math.min(1f, Math.max(0.35f, hsv[2] * 1.1f));
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return IslandSpec.MUSIC_GLOW_DEFAULT;
        }
    }

    /** S1: forget the media controllers' data and collapse the music capsule. */
    private void clearMediaState() {
        if (demoMode == 3) {
            // The user is running the music preview; do not wipe what the demo is showing.
            musicActive = false;
            recomputeState(false);
            return;
        }
        discardArtworkThumb();
        discardDemoArtwork();
        musicActive = false;
        music.playing = false;
        music.title = "Unknown track";
        music.artist = "";
        music.artwork = null;
        music.accent = IslandSpec.MUSIC_GLOW_DEFAULT;
        music.durationMs = 0;
        music.positionMs = 0;
        music.positionAtUptime = 0;
        music.packageName = "";
        music.appIcon = null;
        musicIconPackage = "";
        musicIconRequestSeq++;
        syncModelsToView();
        recomputeState(false);
    }

    /** Release the self-made demo cover once nothing draws it any more. */
    private void discardDemoArtwork() {
        demoArtworkToken++;
        Bitmap doomed = demoArtwork;
        demoArtwork = null;
        if (doomed == null) return;
        if (music.artwork == doomed) music.artwork = null;
        if (view != null && view.music().artwork == doomed) view.music().artwork = null;
        if (!doomed.isRecycled()) doomed.recycle();
    }

    /** Build the demo cover + its glow colour off the main thread (no Bitmap work on the UI thread). */
    private void createDemoArtworkAsync() {
        final long token = ++demoArtworkToken;
        ExecutorService executor = colorExecutor;
        if (executor == null) return;
        executor.execute(() -> {
            Bitmap bmp = null;
            int accent = IslandSpec.MUSIC_GLOW_DEFAULT;
            try {
                bmp = createDemoArtwork();
                accent = averageColor(bmp);
            } catch (Throwable t) {
                Log.w(TAG, "demo artwork failed", t);
            }
            final Bitmap created = bmp;
            final int color = accent;
            handler.post(() -> {
                if (destroyed || token != demoArtworkToken || demoMode != 3) {
                    if (created != null && !created.isRecycled()) created.recycle();
                    return;
                }
                demoArtwork = created;
                music.artwork = created;
                music.accent = color;
                syncModelsToView();
                if (view != null && view.isVisible()) view.invalidate();
                recomputeState(false);
            });
        });
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    @Override public void onListenerInvalidated() {
        if (destroyed) return;
        handler.removeCallbacks(notifDismissTask);
        timerActive = false;
        timerKey = null;
        timer.when = 0;
        timer.running = false;
        timer.packageName = "";
        timer.actionCount = 0;
        for (int i = 0; i < timer.actionTitles.length; i++) {
            timer.actionTitles[i] = null;
            timer.actionIntents[i] = null;
        }
        if (mediaController != null) {
            try { mediaController.unregisterCallback(mediaCallback); } catch (Exception ignored) {}
            mediaController = null;
        }
        // v3.3: the notification listener is gone -> no notification island state can be trusted.
        if (notifActive || currentNotifKey != null) {
            notifActive = false;
            notifUntil = 0;
            currentNotifKey = null;
            iconRequestSeq++;
            notif.reset();
        }
        clearMediaState();
        recomputeState(false);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        IslandSpec.init(this);
        // R-05: the display density may have changed with the configuration; re-derive the manual
        // offset in px (otherwise the card keeps the old, wrong offset after rotation).
        IslandSpec.USER_OFFSET_PX = getSharedPreferences("island", MODE_PRIVATE)
                .getFloat("offset_dp", 0f) * IslandSpec.DENSITY;
        boolean wasAttached = attached;
        detachOverlay();
        // buildView() re-creates the IslandView (default models) and syncModelsToView() refills it
        // from the service models, so rotation keeps the current timer/music/charging content.
        buildView();
        if (wasAttached) {
            int want = desiredMode();
            if (want != IslandView.MODE_HIDDEN) {
                attachOverlay(want);
                if (attached) {
                    view.refreshFrame();
                    view.showFromHidden(want);
                }
            }
        }
    }

    // ================================================================== state
    /**
     * v8.56: independent, user-configurable visibility switches. Every switch defaults to true
     * so existing users see no behaviour change until they turn something off.
     */
    private void loadVisibilityPrefs() {
        android.content.SharedPreferences p = getSharedPreferences("island", MODE_PRIVATE);
        showNotifications = p.getBoolean("show_notif", true);
        showCharging = p.getBoolean("show_charge", true);
        showVolume = p.getBoolean("show_volume", true);
        showMusic = p.getBoolean("show_music", true);
        showRinger = p.getBoolean("show_ringer", true);
        showUnlock = p.getBoolean("show_unlock", true);
        showLock = p.getBoolean("show_lock", true);
        cameraFollow = p.getBoolean("landscape_camera_follow", false);
    }

    private int desiredMode() {
        // v8.53: the unlock playback owns the island until it ends — even a preview/demo or a
        // newly arrived notification/charge/volume HUD must wait.
        if (unlockAnimating || unlockHold) return IslandView.MODE_UNLOCK;
        if (demoMode == 1) return chargingActive ? IslandView.MODE_CHARGE : IslandView.MODE_HIDDEN;
        if (demoMode == 3) return IslandView.MODE_MUSIC_COMPACT;
        // v8.47: volume keys produce a short HUD over everything except unlock playback.
        if (volumeActive && showVolume) return IslandView.MODE_VOLUME;
        // v3.3: notifications sit below unlock playback / above charging/music.
        if (notifActive && showNotifications) return notifForceExpanded
                ? IslandView.MODE_NOTIF_EXPANDED : IslandView.MODE_NOTIF_COMPACT;
        // v8.42: charging must be visible even while music is playing.
        if (chargingActive && showCharging) return IslandView.MODE_CHARGE;
        // v8.31: lock-screen padlock.
        if (unlockRestingUntil > SystemClock.uptimeMillis()) return IslandView.MODE_IDLE;
        if (locked && showLock) return IslandView.MODE_LOCK;
        // v8.31: ringer HUD sits above music, below notifications/charging.
        if (ringerActive && showRinger) return IslandView.MODE_RINGER;
        // v7.9: a running music session must stay visible when nothing else is active.
        // v8.3: the timer island is removed.
        if (musicActive && showMusic) return IslandView.MODE_MUSIC_COMPACT;
        return alwaysOn ? IslandView.MODE_IDLE : IslandView.MODE_HIDDEN;
    }

    private void recomputeState(boolean instant) {
        if (view == null) return;
        syncModelsToView();
        int want = desiredMode();
        if (view.isExpanded()) {
            if (want == IslandView.MODE_HIDDEN) {
                if (instant) {
                    // F-13: "结束预览" while an expanded card is open must vanish immediately instead
                    // of playing the ~600 ms collapse spring first.
                    view.snapHidden();
                    detachOverlay();
                    return;
                }
                // F-15: the data vanished while the big card is open -> hide directly instead of
                // collapsing into a compact capsule that is about to disappear anyway.
                view.hide(false);
                return;
            }
            if (want == IslandView.MODE_IDLE) {
                // v6.0: always-on idle capsule — collapse the big card straight to the empty pill
                // instead of collapsing to the old compact content and then shrinking again.
                view.collapseToCompact(IslandView.MODE_IDLE);
                return;
            }
            if (view.getMode() == IslandView.MODE_NOTIF_EXPANDED
                    && want == IslandView.MODE_NOTIF_EXPANDED) {
                return; // v8.8: keep the auto-expanded notification card as-is
            }
            if (want == IslandView.MODE_NOTIF_COMPACT
                    && view.getMode() != IslandView.MODE_NOTIF_COMPACT
                    && view.getMode() != IslandView.MODE_NOTIF_EXPANDED) {
                // v3.3: a new notification takes over the island while another big card is open.
                // Hide the old card directly; the settle callback shows the notification pill.
                // NOTE: a notification's *own* expanded card must be excluded here — otherwise the
                // settle-triggered recompute would immediately hide the card the user just opened.
                view.hide(false);
                return;
            }
            if (view.getMode() == IslandView.MODE_NOTIF_EXPANDED
                    && want != IslandView.MODE_NOTIF_COMPACT) {
                // v3.3: the shown notification vanished (or another state took over) while its
                // details card is open -> fade the card out instead of collapsing into an empty
                // notification pill for ~600 ms and only then switching to music/timer/charging.
                view.hide(false);
                return;
            }
            int compactOf;
            if (view.getMode() == IslandView.MODE_MUSIC_EXPANDED) {
                compactOf = IslandView.MODE_MUSIC_COMPACT;
            } else if (view.getMode() == IslandView.MODE_NOTIF_EXPANDED) {
                compactOf = IslandView.MODE_NOTIF_COMPACT;
            } else {
                compactOf = IslandView.MODE_TIMER_COMPACT;
            }
            if (want == compactOf) return; // data still present, keep the expanded card
            view.collapseToCompact(compactOf); // springs down; window shrinks when settled
            return;
        }
        if (want == view.getMode()) return;
        applyMode(want, instant);
    }

    private void applyMode(int want, boolean instant) {
        if (view == null) return;
        if (want == IslandView.MODE_HIDDEN) {
            hideIsland(instant);
            return;
        }
        boolean toExpanded = want == IslandView.MODE_TIMER_EXPANDED
                || want == IslandView.MODE_MUSIC_EXPANDED
                || want == IslandView.MODE_NOTIF_EXPANDED;
        // Unlock square should pop out quickly, like the reference.
        boolean unlockExpand = want == IslandView.MODE_UNLOCK;
        boolean toCharge = want == IslandView.MODE_CHARGE;
        boolean fromHidden = view.getMode() == IslandView.MODE_HIDDEN;
        // F-14: only grow here; the window is shrunk from onViewSettled() once the card is small.
        ensureWindow(want, false);
        if (!attached) return; // overlay permission missing
        view.refreshFrame();
        keepDotUntil = 0;
        if (toExpanded || toCharge) expandedAt = SystemClock.uptimeMillis();
        if (fromHidden) {
            view.showFromHidden(want);
        } else {
            view.morphTo(want, toExpanded || toCharge || unlockExpand);
        }
    }

    private void hideIsland(boolean instant) {
        if (view == null) return;
        if (instant) {
            view.snapHidden();
            detachOverlay();
            return;
        }
        int m = view.getMode();
        if (m == IslandView.MODE_CHARGE) {
            chargingActive = false;
            chargingUntil = 0;
            view.dismiss();
            keepDotUntil = SystemClock.uptimeMillis() + IslandSpec.CHARGE_DOT_HOLD_MS;
        } else if (m == IslandView.MODE_HIDDEN) {
            detachOverlay();
        } else {
            view.hide(false);
        }
    }

    /** Called by the view whenever the springs have completely settled. */
    private void onViewSettled() {
        if (view == null || destroyed) return;
        if (notifClosing) {
            finalizeNotificationClose();
            return;
        }
        if (view.getMode() == IslandView.MODE_HIDDEN) {
            // F-04: the charging capsule was dismissed by a tap, but a timer/music state is still
            // active -> switch to it instead of detaching and waiting for the next system callback.
            int next = desiredMode();
            if (next != IslandView.MODE_HIDDEN) {
                keepDotUntil = 0;
                applyMode(next, false);
                // If re-attaching failed (e.g. overlay permission revoked) do not leave a bare dot
                // window attached forever: fall back to detaching it.
                if (view.getMode() == IslandView.MODE_HIDDEN) handler.post(this::detachIfHidden);
                return;
            }
            long wait = keepDotUntil - SystemClock.uptimeMillis();
            if (wait > 0) {
                handler.postDelayed(this::detachIfHidden, wait);
            } else {
                detachIfHidden();
            }
            return;
        }
        // F-14: shrinking to the new mode's window is deferred to here (growing uses allowShrink=false).
        view.ensureCompactContentVisible();
        repairNotificationContentIfBlank("settle");
        ensureWindow(view.getMode(), true);
        if (cameraFollow && lp != null) {
            applyWindowPlacement(lp);
            updateWindowLayout();
        }
        handler.post(() -> recomputeState(false));
    }

    /**
     * v8.27: repair the rare "pure black expanded island" state. A window detach/rebind, a lost
     * window, or a race with a close animation can leave the view expanded while its content fade
     * target is still 0. The geometry is already correct, so only restart the content fade.
     */
    private void repairNotificationContentIfBlank(String where) {
        if (view == null || !notifActive || !notifForceExpanded) return;
        if (view.getMode() != IslandView.MODE_NOTIF_EXPANDED) return;
        if (view.replayExpandedIfBlank(IslandView.MODE_NOTIF_EXPANDED)) {
            Log.w(TAG, "repaired blank notification content (replayed expand, " + where + ")");
        }
    }

    /** Called by IslandAccessibilityService when the user enables/disables enhanced mode. */
    public static void onAccessibilityChanged() {
        IslandService s = instance;
        if (s == null || s.handler == null || s.destroyed) return;
        s.handler.post(s::rebindWindowForAccessibility);
    }

    private void rebindWindowForAccessibility() {
        if (destroyed || view == null) return;
        int m = view.getMode();
        if (m == IslandView.MODE_HIDDEN) {
            if (attached) removeWindow();
            return;
        }
        boolean hadWindow = attached;
        if (hadWindow) removeWindow(); // S12: also detects "a11y host gone, system already removed it"
        // v3.9: removeViewImmediate can still be settling inside WindowManagerGlobal when we add
        // the same View to another WindowManager (a11y host); posting one loop later guarantees
        // the old ViewRootImpl is gone. Without this the re-add threw
        // "View has already been added to the window manager" and the island vanished.
        handler.postDelayed(() -> {
            if (destroyed || view == null) return;
            attachOverlay(m);
            if (attached) {
                view.refreshFrame();
                view.resumeRendering();
                repairNotificationContentIfBlank("rebind");
            }
        }, hadWindow ? 80L : 0L);
    }

    private void detachIfHidden() {
        if (view != null && view.getMode() == IslandView.MODE_HIDDEN) detachOverlay();
    }

    /**
     * v3.3: taps now carry both fractions (the notification card needs y to hit its action row).
     * The existing timer/music/charging handling keeps using x only.
     */
    private void handleTap(float fracX, float fracY) {
        int current = view.getMode();
        switch (current) {
            case IslandView.MODE_CHARGE:
                chargingActive = false;
                chargingUntil = 0;
                view.dismiss();
                keepDotUntil = SystemClock.uptimeMillis() + 200;
                break;
            case IslandView.MODE_RINGER:
                ringerActive = false;
                ringerUntil = 0;
                recomputeState(false);
                break;
            case IslandView.MODE_TIMER_COMPACT:
                expandedAt = SystemClock.uptimeMillis();
                ensureWindow(IslandView.MODE_TIMER_EXPANDED, false);
                view.morphTo(IslandView.MODE_TIMER_EXPANDED, true);
                break;
            case IslandView.MODE_MUSIC_COMPACT:
                expandedAt = SystemClock.uptimeMillis();
                ensureWindow(IslandView.MODE_MUSIC_EXPANDED, false);
                view.morphTo(IslandView.MODE_MUSIC_EXPANDED, true);
                break;
            case IslandView.MODE_NOTIF_COMPACT:
                expandedAt = SystemClock.uptimeMillis();
                extendNotifHoldForExpanded();
                ensureWindow(IslandView.MODE_NOTIF_EXPANDED, false);
                view.morphTo(IslandView.MODE_NOTIF_EXPANDED, true);
                break;
            case IslandView.MODE_TIMER_EXPANDED:
                int timerButton = timerButtonAt(fracX, fracY);
                if (timerButton >= 0) {
                    // C: wire the two round buttons to the Clock notification's pause/resume action.
                    fireTimerAction(timerButton);
                    return;
                }
                // v5.1: tapping the card body (not a button) opens the source Clock/timer app.
                openTimerSourceApp();
                view.collapseToCompact(IslandView.MODE_TIMER_COMPACT);
                break;
            case IslandView.MODE_MUSIC_EXPANDED:
                MediaController controller = mediaController;
                if (controller != null) {
                    int button = musicButtonAt(fracX, fracY);
                    if (button >= 0) {
                        MediaController.TransportControls tc = controller.getTransportControls();
                        if (button == 1) tc.skipToPrevious();
                        else if (button == 2) {
                            if (music.playing) tc.pause(); else tc.play();
                        } else if (button == 3) tc.skipToNext();
                        // 0 (app icon) and 4 (heart) have no action yet: swallow the tap instead of
                        // collapsing the card.
                        return;
                    }
                }
                // v5.1: tap anywhere on the expanded music card except its button row opens the
                // source music app; then leave a compact playing pill behind.
                String musicPkg = music.packageName;
                if ((musicPkg == null || musicPkg.isEmpty()) && controller != null) {
                    try { musicPkg = controller.getPackageName(); } catch (Throwable ignored) {}
                }
                boolean opened = launchApp(musicPkg);
                if (!opened) {
                    // The preview has no associated app; a real player may also expose no launcher.
                    android.widget.Toast.makeText(this,
                            demoMode == 3 ? "预览模式没有关联应用，请用真实音乐 App 测试"
                                    : "无法打开对应的音乐应用",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                view.collapseToCompact(IslandView.MODE_MUSIC_COMPACT);
                break;
            case IslandView.MODE_NOTIF_EXPANDED:
                int action = view.notificationActionAt(fracX, fracY);
                if (action >= 0 && action < notif.actionCount
                        && notif.actionIntents[action] != null) {
                    sendPendingIntent(notif.actionIntents[action]);
                    // v8.29: any notification action (e.g. app-store "全部更新") closes the card
                    // immediately instead of sitting there for the rest of the 4 s hold.
                    dismissNotificationIsland();
                    return;
                }
                if (notif.contentIntent != null) {
                    // Tapping the notification body opens the app and dismisses the island.
                    Log.d(TAG, "notif body tap contentIntent pkg=" + notif.packageName);
                    boolean bodyOpened = sendPendingIntent(notif.contentIntent);
                    if (!bodyOpened) bodyOpened = launchApp(notif.packageName);
                    if (bodyOpened) {
                        dismissNotificationIsland();
                    } else {
                        android.widget.Toast.makeText(this, "无法打开通知应用",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                } else if (launchApp(notif.packageName)) {
                    dismissNotificationIsland();
                } else {
                    view.collapseToCompact(IslandView.MODE_NOTIF_COMPACT);
                }
                break;
            default:
                break;
        }
    }

    /** v8.6: overlay editor for a notification RemoteInput (direct reply). */
    private void showNotificationReplyInput() {
        boolean forced = notif.replyInputs == null || notif.replyInputs.length == 0;
        if (forced) {
            if (notif.forceReplyActionIndex < 0) return;
        } else if (notif.replyActionIndex < 0 || wm == null) {
            return;
        }
        if (wm == null) return;
        if (!Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "回复需要悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        hideNotificationReplyInput();
        replyIsForced = forced;
        if (forced) openNotificationAppForForcedReply();
        float density = IslandSpec.DENSITY > 0f ? IslandSpec.DENSITY : 3f;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - (int) (32 * density),
                (int) (IslandSpec.expandedW() * 0.90f));
        width = Math.max(width, (int) (240 * density));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C22);
        bg.setCornerRadius(18 * density);
        bg.setStroke((int) Math.max(1f, density), 0xFF3A3A45);
        row.setBackground(bg);
        row.setPadding((int) (14 * density), (int) (6 * density),
                (int) (8 * density), (int) (6 * density));

        final EditText edit = new EditText(this);
        edit.setHint(forced ? "回复（填入应用）" : "回复…");
        edit.setTextColor(0xFFFFFFFF);
        edit.setHintTextColor(0xFF9A9AA6);
        edit.setTextSize(15);
        edit.setBackground(null);
        edit.setSingleLine(false);
        edit.setMaxLines(4);
        edit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(edit, editLp);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(0xFF9A9AA6);
        cancel.setPadding((int) (8 * density), (int) (10 * density),
                (int) (8 * density), (int) (10 * density));
        cancel.setOnClickListener(v -> hideNotificationReplyInput());
        row.addView(cancel);

        TextView send = new TextView(this);
        send.setText("发送");
        send.setTextSize(15);
        send.setTextColor(0xFF6ECB8B);
        send.setPadding((int) (10 * density), (int) (10 * density),
                (int) (10 * density), (int) (10 * density));
        send.setOnClickListener(v -> sendNotificationReply(edit.getText().toString()));
        row.addView(send);
        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendNotificationReply(edit.getText().toString());
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams rlp = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        rlp.gravity = Gravity.TOP | Gravity.LEFT;
        rlp.x = (screenW - width) / 2;
        int cardBottom = (int) (IslandSpec.cardTop()
                + (view != null ? view.notificationExpandedHeight() : IslandSpec.notifExpandedMinH()));
        rlp.y = cardBottom + (int) (10 * density);
        rlp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try {
            wm.addView(row, rlp);
        } catch (Throwable t) {
            Log.w(TAG, "reply overlay add failed", t);
            android.widget.Toast.makeText(this, "无法打开回复框", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        replyInputView = row;
        replyInputEdit = edit;
        edit.requestFocus();
        edit.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && replyInputEdit != null) {
                imm.showSoftInput(replyInputEdit, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120L);
    }

    private void openNotificationAppForForcedReply() {
        if (notif.contentIntent != null) {
            try {
                sendPendingIntent(notif.contentIntent);
                return;
            } catch (Throwable ignored) {}
        }
        launchApp(notif.packageName);
    }

    private void sendForcedReply(String text) {
        boolean filled = false;
        IslandAccessibilityService a11y = IslandAccessibilityService.get();
        if (a11y != null) {
            try {
                filled = a11y.forceReply(notif.packageName, text);
            } catch (Throwable t) {
                Log.w(TAG, "forced reply failed", t);
            }
        }
        if (!filled) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("回复", text));
                android.widget.Toast.makeText(this,
                        a11y == null
                                ? "已复制回复，请到应用中粘贴发送（可开启增强模式自动填入）"
                                : "已复制回复，未能自动填入，请在应用中粘贴发送",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        } else {
            android.widget.Toast.makeText(this, "已填入应用回复框", android.widget.Toast.LENGTH_SHORT).show();
        }
        hideNotificationReplyInput();
        dismissNotificationIsland();
    }

    private void sendNotificationReply(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return;
        if (replyIsForced) {
            sendForcedReply(text);
            return;
        }
        if (notif.replyInputs == null || notif.replyInputs.length == 0
                || notif.replyActionIndex < 0
                || notif.replyActionIndex >= notif.actionCount
                || notif.actionIntents[notif.replyActionIndex] == null) {
            return;
        }
        Bundle results = new Bundle();
        for (RemoteInput input : notif.replyInputs) {
            if (input == null) continue;
            results.putCharSequence(input.getResultKey(), text);
        }
        Intent intent = new Intent();
        RemoteInput.addResultsToIntent(notif.replyInputs, intent, results);
        try {
            notif.actionIntents[notif.replyActionIndex].send(this, 0, intent);
        } catch (Throwable t) {
            Log.w(TAG, "send reply failed", t);
            android.widget.Toast.makeText(this, "发送失败", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        hideNotificationReplyInput();
        dismissNotificationIsland();
    }

    private void hideNotificationReplyInput() {
        if (replyInputEdit != null) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(replyInputEdit.getWindowToken(), 0);
            } catch (Throwable ignored) {}
        }
        if (replyInputView != null && wm != null) {
            try {
                wm.removeView(replyInputView);
            } catch (Throwable ignored) {}
        }
        replyInputView = null;
        replyInputEdit = null;
    }

    /** C: send the Clock notification action that matches the button semantics (else no-op). */
    private void fireTimerAction(int buttonIndex) {
        if (timer.actionCount <= 0) return;
        String[] keywords = buttonIndex == 0
                // grey circle: resume / start
                ? new String[]{"继续", "开始", "恢复", "resume", "start", "play"}
                // orange circle: pause / stop
                : new String[]{"暂停", "停止", "pause", "stop"};
        for (int i = 0; i < timer.actionCount; i++) {
            PendingIntent intent = timer.actionIntents[i];
            if (intent == null) continue;
            String title = timer.actionTitles[i] == null
                    ? "" : timer.actionTitles[i].toLowerCase(Locale.US);
            for (String keyword : keywords) {
                if (title.contains(keyword)) {
                    sendPendingIntent(intent);
                    return;
                }
            }
        }
        // No title matched the button semantics: do nothing (never fire a random action).
    }

    /** S8: map the tap to the same five button centres IslandView draws, or -1 if none. */
    private static int musicButtonAt(float fracX, float fracY) {
        // v5.1: only the actual control row counts as a button — tapping the title/cover/progress
        // area must fall through to "open the source app".
        if (fracY < 0.66f || fracY > 0.86f) return -1;
        float[] xs = IslandView.MUSIC_BUTTON_X;
        if (xs == null) return -1;
        for (int i = 0; i < xs.length; i++) {
            if (Math.abs(fracX - xs[i]) <= MUSIC_BUTTON_HIT_HALF) return i;
        }
        return -1;
    }

    /** F-05: the two round (decorative) timer-card button centres, shared with the drawing. */
    private static int timerButtonAt(float fracX, float fracY) {
        // v5.1: only the two round buttons; the rest of the timer card opens the Clock app.
        if (fracY < 0.20f || fracY > 0.80f) return -1;
        float[] xs = IslandView.TIMER_BUTTON_X;
        if (xs == null) return -1;
        for (int i = 0; i < xs.length; i++) {
            if (Math.abs(fracX - xs[i]) <= TIMER_BUTTON_HIT_HALF) return i;
        }
        return -1;
    }

    /**
     * v5.1: open the app that owns the expanded card when the user taps its body (not a
     * transport/action button). Returns false when the package is unknown or has no launcher.
     */
    private boolean launchApp(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return false;
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "open app failed: " + pkg, t);
            return false;
        }
    }

    /** v5.1: timer/clock card body → source Clock app, else the system timer screen. */
    private void openTimerSourceApp() {
        if (launchApp(timer.packageName)) return;
        try {
            Intent intent = new Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "open timer app failed", t);
        }
    }

    private void collapseExpanded() {
        if (view == null) return;
        if (view.getMode() == IslandView.MODE_TIMER_EXPANDED) {
            view.collapseToCompact(IslandView.MODE_TIMER_COMPACT);
        } else if (view.getMode() == IslandView.MODE_MUSIC_EXPANDED) {
            view.collapseToCompact(IslandView.MODE_MUSIC_COMPACT);
        } else if (view.getMode() == IslandView.MODE_NOTIF_EXPANDED) {
            // v8.11: the notification island never returns to a compact pill; swiping it away
            // dismisses the notification card instead of showing a small capsule.
            dismissNotificationIsland();
        }
    }

    // ================================================================== demos
    private void startChargingDemo() {
        if (view == null) return;
        demoMode = 1;
        demoUntil = SystemClock.uptimeMillis() + IslandSpec.CHARGE_HOLD_MS + 1500;
        chargingActive = true;
        chargingUntil = SystemClock.uptimeMillis() + IslandSpec.CHARGE_HOLD_MS;
        charging.charging = true;
        // C/B: the preview also shows the REAL battery percentage (the old fake constant is gone).
        if (!readBatterySnapshot()) {
            // Last resort when neither the sticky broadcast nor the capacity property answers:
            // keep the last real value instead of inventing one.
            charging.preciseLevel = charging.level;
        }
        charging.fast = false;
        charging.superFast = false;
        lastBatteryPollAt = SystemClock.uptimeMillis();
        syncModelsToView();
        recomputeState(false);
    }

    private void startTimerDemo() {
        if (view == null) return;
        // S3: remember the real timer (if one is running) so endDemo() can restore it.
        savedTimer = timerKey != null ? copyTimer(timer) : null;
        demoMode = 2;
        demoUntil = SystemClock.uptimeMillis() + 60000;
        timerActive = true;
        timer.title = "秒表";
        timer.countDown = false;
        timer.running = true;
        timer.when = System.currentTimeMillis() - 1000;
        timer.packageName = "";
        timer.tick(System.currentTimeMillis());
        syncModelsToView();
        recomputeState(false);
    }

    private void startMusicDemo() {
        if (view == null) return;
        demoMode = 3;
        demoUntil = SystemClock.uptimeMillis() + 60000;
        musicActive = true;
        music.title = "#BrooklynBloodPop!(Explicit)";
        music.artist = "Syko";
        music.playing = true;
        music.durationMs = 145_000;
        music.positionMs = 50_000;
        music.positionAtUptime = SystemClock.elapsedRealtime();
        music.packageName = "";
        music.appIcon = null;
        musicIconPackage = "";
        musicIconRequestSeq++;
        // S13/P-05: the previous preview cover and any real-art thumbnail are no longer drawn.
        discardArtworkThumb();
        discardDemoArtwork();
        music.artwork = null;
        music.accent = IslandSpec.MUSIC_GLOW_DEFAULT;
        syncModelsToView();
        recomputeState(false);
        // S6: build the cover + accent colour on the worker thread, then post the result back.
        createDemoArtworkAsync();
    }

    /**
     * S3: leave the preview and return to whatever the real sources report, instead of blanking
     * the island until the next system callback (a paused song used to never come back).
     */
    private void endDemo() {
        endDemo(false);
    }

    private void endDemo(boolean instant) {
        if (destroyed) return;
        demoMode = 0;
        demoUntil = 0;
        chargingActive = false;
        chargingUntil = 0;
        timerActive = false;
        musicActive = false;
        if (timerKey != null) {
            // The clock notification is still posted: restore the pre-demo values and keep it up.
            timerActive = true;
            if (savedTimer != null) copyTimerInto(timer, savedTimer);
        } else {
            timer.when = 0;
            timer.running = false;
            timer.packageName = "";
        }
        savedTimer = null;
        if (mediaController != null) {
            // Re-derive title / cover / playing / position from the live media session.
            applyMediaState();
        } else {
            discardArtworkThumb();
            discardDemoArtwork();
            music.playing = false;
            music.artwork = null;
            music.accent = IslandSpec.MUSIC_GLOW_DEFAULT;
            music.packageName = "";
            syncModelsToView();
        }
        syncModelsToView();
        recomputeState(instant);
    }

    private static IslandData.Timer copyTimer(IslandData.Timer src) {
        IslandData.Timer copy = new IslandData.Timer();
        copyTimerInto(copy, src);
        return copy;
    }

    private static void copyTimerInto(IslandData.Timer dst, IslandData.Timer src) {
        dst.title = src.title;
        dst.digits = src.digits;
        dst.running = src.running;
        dst.packageName = src.packageName;
        dst.countDown = src.countDown;
        dst.when = src.when;
    }

    private Bitmap createDemoArtwork() {
        int size = 512;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // left half: portrait-like grey gradient; right half: red/white split (as in the reference)
        p.setShader(new LinearGradient(0, 0, size * 0.55f, size,
                new int[]{0xFFB9B4AE, 0xFF6E6A66, 0xFF3B3836}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size * 0.55f, size, p);
        p.setShader(null);
        p.setColor(0xFFF3EFE8);
        c.drawRect(size * 0.55f, 0, size, size, p);
        p.setColor(0xFFB5121B);
        c.drawRect(size * 0.40f, 0, size * 0.62f, size, p);
        p.setColor(0xFFD9232E);
        c.drawRect(size * 0.62f, 0, size * 0.70f, size, p);
        p.setShader(new LinearGradient(size * 0.70f, 0, size, size,
                new int[]{0xFFE02530, 0xFF8E0C12}, null, Shader.TileMode.CLAMP));
        c.drawRect(size * 0.70f, 0, size, size, p);
        p.setShader(null);
        // face-ish blob on the left half so it reads like a portrait at small sizes
        p.setColor(0xFF2A2422);
        c.drawCircle(size * 0.27f, size * 0.35f, size * 0.16f, p);
        p.setColor(0xFFC9B8AC);
        c.drawCircle(size * 0.27f, size * 0.40f, size * 0.11f, p);
        p.setColor(0xFF1E1A18);
        c.drawCircle(size * 0.27f, size * 0.32f, size * 0.12f, p);
        return bmp;
    }

    // =================================================================== tick
    private void tick() {
        if (destroyed || view == null) return;
        long now = SystemClock.uptimeMillis();
        // Watchdog: the pre-size/120 ms start callback must never leave the unlock pending forever.
        if (unlockStartPending && now - unlockPendingSince > 700L) {
            beginUnlockAnimation(unlockSeq);
        }
        pollVolume();
        pollKeyguard();
        if (demoMode != 0 && demoUntil > 0 && now > demoUntil) endDemo();

        // R-03: watchdog — the system may remove our window without telling us (a11y host killed,
        // overlay permission revoked, ROM cleanup); `attached` alone would then lie forever.
        // Three consecutive 300 ms ticks (~900 ms) without a window token rule out the first-frame
        // gap right after addView().
        if (attached && view.getWindowToken() == null) {
            if (windowLostCount < WINDOW_LOST_TICKS) windowLostCount++;
            if (windowLostCount >= WINDOW_LOST_TICKS && view.getParent() == null) {
                windowLostCount = 0;
                Log.w(TAG, "overlay window lost, re-attaching");
                attached = false;
                windowOnA11y = false;
                int want = desiredMode();
                if (want != IslandView.MODE_HIDDEN) attachOverlay(want);
            }
        } else {
            windowLostCount = 0;
        }

        // v3.9: an addView failure (e.g. while switching from the app overlay to the accessibility
        // overlay) leaves `attached=false` with a visible state and no watchdog that would notice.
        // Retry every ~1.2 s until the window is back.
        if (!attached && !foregroundFailed && view != null
                && desiredMode() != IslandView.MODE_HIDDEN) {
            reattachTries++;
            if (reattachTries >= 4) {
                reattachTries = 0;
                int want = desiredMode();
                Log.w(TAG, "overlay not attached, retrying");
                attachOverlay(want);
                if (attached) {
                    view.refreshFrame();
                    view.resumeRendering();
                    repairNotificationContentIfBlank("reattach");
                    recomputeState(false);
                }
            }
        } else {
            reattachTries = 0;
        }
        repairNotificationContentIfBlank("tick");

        boolean invalidate = false;
        int mode = view.getMode();
        if (demoMode == 1) {
            // v3.3 B: the preview still evolves normal -> fast -> super fast (labels/colours), but
            // the percentage is now the REAL battery value refreshed at most once per second.
            if (chargingUntil > 0) {
                long elapsed = now - chargingUntil + IslandSpec.CHARGE_HOLD_MS;
                if (elapsed > 3500) {
                    if (now - lastBatteryPollAt >= BATTERY_POLL_INTERVAL_MS) {
                        lastBatteryPollAt = now;
                        refreshRealChargingLevel();
                    }
                    boolean superFast = elapsed > 5800;
                    boolean fast = !superFast;
                    IslandData.Charging vc = view.charging();
                    if (vc.fast != fast || vc.superFast != superFast
                            || vc.level != charging.level
                            || vc.preciseLevel != charging.preciseLevel) {
                        charging.fast = fast;
                        charging.superFast = superFast;
                        vc.fast = fast;
                        vc.superFast = superFast;
                        vc.level = charging.level;
                        vc.preciseLevel = charging.preciseLevel;
                        invalidate = true;
                    }
                }
            }
        } else if (demoMode == 2) {
            if (timer.running) {
                // S5: only touch the text (and the screen) when the seconds actually changed.
                String before = view.timer().digits;
                timer.tick(System.currentTimeMillis());
                if (!timer.digits.equals(before)) {
                    view.timer().digits = timer.digits;
                    invalidate = true;
                }
            }
        } else {
            if (chargingActive && chargingUntil > now
                    && now - lastBatteryPollAt >= BATTERY_POLL_INTERVAL_MS) {
                // S5/S10: battery binder properties are polled at most once per second;
                // updateChargingData() invalidates itself only when a rendered value changed.
                updateChargingData(-1, 0);
            }
            if (timerActive && timer.running) {
                String before = view.timer().digits;
                timer.tick(System.currentTimeMillis());
                if (!timer.digits.equals(before)) {
                    view.timer().digits = timer.digits;
                    // The view no longer redraws a static pill every frame, so the service has to
                    // ask for the one frame that shows the new second.
                    if (mode == IslandView.MODE_TIMER_COMPACT
                            || mode == IslandView.MODE_TIMER_EXPANDED) {
                        invalidate = true;
                    }
                }
            }
        }
        if (view.isExpanded() && view.getMode() != IslandView.MODE_NOTIF_EXPANDED
                && now - expandedAt > IslandSpec.EXPANDED_TIMEOUT_MS) {
            collapseExpanded();
        }
        if (chargingActive && now >= chargingUntil) {
            endChargingNow();
        }
        // v3.3 A: hide the notification pill after its 6 s hold (expanded cards are left alone and
        // keep the hold extended; the normal expand timeout collapses them first).
        if (notifActive && !notifForceExpanded && !view.isExpanded()
                && notifUntil > 0 && now >= notifUntil) {
            dismissNotificationIsland();
        }
        if (ringerActive && now >= ringerUntil) {
            ringerActive = false;
            recomputeState(false);
            invalidate = true;
        }
        if (volumeActive && now >= volumeUntil) {
            volumeActive = false;
            recomputeState(false);
            invalidate = true;
        }
        if (unlockRestingUntil > 0 && now >= unlockRestingUntil) {
            unlockRestingUntil = 0;
            recomputeState(false);
            invalidate = true;
        }
        if (invalidate && view.isVisible()) view.invalidate();
    }

    // =================================================================== static
    public static void start(Context ctx, String action) {
        Intent i = new Intent(ctx, IslandService.class);
        if (action != null) i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot start island service now: " + e.getMessage());
        }
    }

    public static boolean isRunning() { return instance != null; }
}

