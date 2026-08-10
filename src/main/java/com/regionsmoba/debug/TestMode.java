package com.regionsmoba.debug;

/**
 * Suppresses automatic match end so one operator can play indefinitely.
 * Toggled by /regions debug testmode.
 *
 * In-memory and global — this gates a match-wide condition, not a per-player
 * permission (contrast {@link com.regionsmoba.protection.BuildMode}). Cleared on
 * every match start, match end and server stop so it can never survive a restart
 * or leak into a scored match.
 */
public final class TestMode {

    private static boolean active;

    private TestMode() {}

    public static boolean isActive() {
        return active;
    }

    public static void set(boolean on) {
        active = on;
    }

    public static void clear() {
        active = false;
    }
}
