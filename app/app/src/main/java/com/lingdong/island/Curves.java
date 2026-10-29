package com.lingdong.island;

import android.view.animation.Interpolator;
import java.util.ArrayList;
import java.util.List;

/** Small animation toolbox: spring step-response curves and cubic-bezier curves. */
public final class Curves {

    private Curves() {}

    /** Cubic bezier interpolator identical to PathInterpolator semantics. */
    public static Interpolator bezier(final float x1, final float y1, final float x2, final float y2) {
        final float[] xs = new float[101];
        final float[] ys = new float[101];
        for (int i = 0; i <= 100; i++) {
            float t = i / 100f;
            xs[i] = bez(t, x1, x2);
            ys[i] = bez(t, y1, y2);
        }
        return new Interpolator() {
            @Override public float getInterpolation(float input) {
                if (input <= 0f) return 0f;
                if (input >= 1f) return 1f;
                // find segment by x
                int lo = 0, hi = 100;
                while (lo < hi - 1) {
                    int mid = (lo + hi) >>> 1;
                    if (xs[mid] <= input) lo = mid; else hi = mid;
                }
                float span = xs[hi] - xs[lo];
                float f = span <= 0f ? 0f : (input - xs[lo]) / span;
                return ys[lo] + f * (ys[hi] - ys[lo]);
            }
        };
    }

    private static float bez(float t, float p1, float p2) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    /**
     * Analytic step response of a damped harmonic oscillator, normalised so that it reaches
     * exactly 1 at the end of the animation:
     *     x(t) = 1 - e^(-z*w*t) * (cos(wd*t) + z/sqrt(1-z^2) * sin(wd*t))
     * with zeta = damping ratio, omega = natural angular frequency (rad/s).
     * zeta &lt; 1 produces the visible overshoot/bounce measured in the reference videos.
     */
    public static Interpolator spring(final float zeta, final float omega, final long durationMs) {
        final float dur = durationMs / 1000f;
        final float wd = omega * (float) Math.sqrt(Math.max(0f, 1f - zeta * zeta));
        final float endValue = step(zeta, omega, wd, dur);
        return new Interpolator() {
            @Override public float getInterpolation(float input) {
                float v = step(zeta, omega, wd, input * dur);
                // normalise so the curve ends exactly at 1.0 (removes the tiny residual)
                float tail = 1f - endValue;
                if (tail != 0f) v = v + input * tail;
                return v;
            }
        };
    }

    private static float step(float zeta, float omega, float wd, float t) {
        if (t <= 0f) return 0f;
        if (zeta >= 1f) {
            // critically damped
            return 1f - (float) (Math.exp(-omega * t) * (1f + omega * t));
        }
        double e = Math.exp(-zeta * omega * t);
        double c = Math.cos(wd * t);
        double s = Math.sin(wd * t);
        return (float) (1d - e * (c + zeta / Math.sqrt(1d - zeta * zeta) * s));
    }

    /** Fast-out decaying curve used for the charging "shrink to dot" (monotonic, measured). */
    public static Interpolator decay() {
        return new Interpolator() {
            @Override public float getInterpolation(float input) {
                return (1f - (float) Math.exp(-2.2f * input)) / (1f - (float) Math.exp(-2.2f));
            }
        };
    }

    /** Convenience list builder. */
    public static List<Interpolator> none() { return new ArrayList<>(); }
}
