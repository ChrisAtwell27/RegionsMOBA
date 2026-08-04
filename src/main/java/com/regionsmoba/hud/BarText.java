package com.regionsmoba.hud;

import java.util.Locale;

/**
 * Pure formatting and normalisation for the lifeline boss bars. Free of Minecraft
 * types so it can be unit-tested without bootstrapping a server.
 */
public final class BarText {

    private static final int TICKS_PER_SECOND = 20;

    private BarText() {}

    /** Renders a tick count as mm:ss, clamping negatives to zero. */
    public static String mmss(int ticks) {
        int total = Math.max(0, ticks) / TICKS_PER_SECOND;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    /** Clamped current/max, returning 0 when max is non-positive. */
    public static float progress(float current, float max) {
        if (max <= 0.0f) return 0.0f;
        float p = current / max;
        if (p < 0.0f) return 0.0f;
        if (p > 1.0f) return 1.0f;
        return p;
    }

    /**
     * Furnace burn time normalised against a fixed window: full whenever more than
     * the window remains, draining across the final window. An urgency gauge, not
     * a fuel gauge — fuel items vary from 1600 to 20000 ticks, so there is no
     * natural full.
     */
    public static float furnaceProgress(int litTicks, int windowTicks) {
        return progress(litTicks, windowTicks);
    }
}
