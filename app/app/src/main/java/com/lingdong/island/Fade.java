package com.lingdong.island;

import android.os.SystemClock;
import android.view.animation.Interpolator;

/**
 * Time driven alpha fade (cubic-bezier), used for the content layer only, per F:\ANIMATION.md §3-5.
 * Geometry never uses this class - it is spring driven.
 */
public final class Fade {

    private float from;
    private float to;
    private float value;
    private long startMs;
    private long durationMs;
    private Interpolator easing;

    public Fade(float initial) {
        snap(initial);
    }

    public void snap(float v) {
        value = v;
        from = v;
        to = v;
        startMs = 0L;
        durationMs = 0L;
        easing = null;
    }

    public void start(float fromValue, float target, long duration, long delayMs, Interpolator easing) {
        this.from = fromValue;
        this.to = target;
        this.value = fromValue;
        this.durationMs = Math.max(1L, duration);
        this.startMs = SystemClock.uptimeMillis() + Math.max(0L, delayMs);
        this.easing = easing;
    }

    public float value() { return value; }

    /** The current target; used to detect a content layer that was left at alpha 0. */
    public float target() { return to; }

    public boolean settled() {
        return startMs == 0L || SystemClock.uptimeMillis() >= startMs + durationMs;
    }

    /** @return true while the fade is still changing */
    public boolean update(long nowMs) {
        if (startMs == 0L || nowMs < startMs) return false;
        long elapsed = nowMs - startMs;
        if (elapsed >= durationMs) {
            value = to;
            return false;
        }
        float p = elapsed / (float) durationMs;
        float e = easing == null ? p : easing.getInterpolation(p);
        value = from + (to - from) * e;
        return true;
    }
}
