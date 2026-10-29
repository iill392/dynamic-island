package com.lingdong.island;

/**
 * Single-axis damped spring, as specified by the IslandKit animation manual:
 *
 *     x'' = -k (x - target) - c x'      with c = 2*zeta*sqrt(k)
 *
 * Semi-implicit Euler, one step per frame, dt clamped to 32 ms by the caller.
 * The manual's "visually settled" gate is |dx| &lt; 5 px &amp;&amp; |v| &lt; 120 px/s; when the gate is
 * hit the spring snaps exactly onto the target so the card does not keep a residual offset.
 */
public final class Spring {

    public float value;
    public float velocity;
    public float target;

    /** damping ratio (only controls how bouncy it is) */
    public float zeta = 0.58f;
    /** stiffness (only controls how fast it is; overshoot percentage is unchanged) */
    public float stiffness = 110f;

    /** visual settle gate (manual §2.5: 5 px / 120 px·s⁻¹) */
    public float posEps = 5f;
    public float velEps = 120f;

    public Spring() {}

    public Spring(float initial, float zeta, float stiffness) {
        this.value = initial;
        this.target = initial;
        this.zeta = zeta;
        this.stiffness = stiffness;
    }

    public void configure(float zeta, float stiffness) {
        this.zeta = zeta;
        this.stiffness = stiffness;
    }

    public void snap(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    public void setTarget(float t) { target = t; }

    public boolean settled() {
        return Math.abs(target - value) < posEps && Math.abs(velocity) < velEps;
    }

    /** Advance one frame. The caller clamps dt to [0, 0.032] as the manual requires. */
    public boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0f;
            return false;
        }
        // A zero-length frame must not teleport the spring onto its target; just report that the
        // spring is still in motion. (Only a real settle may snap the value.)
        if (dt <= 0f) return true;
        float c = 2f * zeta * (float) Math.sqrt(stiffness);
        float accel = -stiffness * (value - target) - c * velocity;
        velocity += accel * dt;
        value += velocity * dt;
        if (settled()) {
            value = target;
            velocity = 0f;
            return false;
        }
        return true;
    }
}
