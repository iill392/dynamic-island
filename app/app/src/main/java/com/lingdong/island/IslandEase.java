package com.lingdong.island;

import android.view.animation.Interpolator;

/**
 * cubic-bezier easings used for the content alpha fades, per F:\ANIMATION.md §3.
 *
 * Solver: Newton-Raphson with 8 iterations (epsilon 1e-5), falling back to 24 bisection steps.
 * This is accurate enough that any control points can be used (fitted reference table error 5e-5).
 */
public final class IslandEase {

    /** pill fade-out / content fade-out (fitted from the reference implementation) */
    public static final Interpolator STANDARD = cubicBezier(0.40f, 0.00f, 0.20f, 1.00f);
    /** content fade-in */
    public static final Interpolator CONTENT = cubicBezier(0.20f, 0.00f, 0.30f, 1.00f);

    private IslandEase() {}

    public static Interpolator cubicBezier(final float x1, final float y1,
                                           final float x2, final float y2) {
        return new Interpolator() {
            @Override public float getInterpolation(float input) {
                if (input <= 0f) return 0f;
                if (input >= 1f) return 1f;
                return solve(input, x1, y1, x2, y2);
            }
        };
    }

    private static float solve(float x, float x1, float y1, float x2, float y2) {
        // Newton-Raphson: find t with bezierX(t) = x, then return bezierY(t)
        float t = x;
        for (int i = 0; i < 8; i++) {
            float cx = bezierX(t, x1, x2) - x;
            if (Math.abs(cx) < 1e-5f) return bezierY(t, y1, y2);
            float dx = bezierDX(t, x1, x2);
            if (Math.abs(dx) < 1e-6f) break;
            t -= cx / dx;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
        }
        // fallback: bisection, 24 iterations
        float lo = 0f, hi = 1f;
        t = x;
        for (int i = 0; i < 24; i++) {
            float cx = bezierX(t, x1, x2);
            if (Math.abs(cx - x) < 1e-5f) break;
            if (cx < x) lo = t;
            else hi = t;
            t = (lo + hi) / 2f;
        }
        return bezierY(t, y1, y2);
    }

    private static float bezierX(float t, float x1, float x2) {
        float u = 1f - t;
        return 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t;
    }

    private static float bezierY(float t, float y1, float y2) {
        float u = 1f - t;
        return 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t;
    }

    private static float bezierDX(float t, float x1, float x2) {
        float u = 1f - t;
        return 3f * u * u * x1 + 6f * u * t * (x2 - x1) + 3f * t * t * (1f - x2);
    }
}
