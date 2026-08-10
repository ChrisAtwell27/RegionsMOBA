package com.regionsmoba.pvp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the right to return fire.
 *
 * Territorial PvP lets a region's owners strike intruders during peace while
 * intruders may not strike back. This records the exception: once a defender
 * lands a permitted hit, the victim may fight back — against that defender
 * only, for a rolling window.
 *
 * Pair-scoped and directional on purpose. If one defender opening fire granted
 * the intruder a licence to fight the whole team, home-field advantage would
 * evaporate the moment anyone swung.
 *
 * Deliberately free of Minecraft types so the window logic is unit-testable
 * without bootstrapping a server.
 */
public final class Retaliation {

    /** 15 seconds, refreshed by each further hit. */
    public static final long WINDOW_TICKS = 300L;

    /** victim -> attacker -> tick at which the right lapses. */
    private static final Map<UUID, Map<UUID, Long>> rights = new HashMap<>();

    private Retaliation() {}

    /** Records that {@code victim} may strike {@code attacker} back. */
    public static void grant(UUID victim, UUID attacker, long nowTick) {
        rights.computeIfAbsent(victim, k -> new HashMap<>())
                .put(attacker, nowTick + WINDOW_TICKS);
    }

    /** True if {@code a} currently holds the right to strike {@code b}. */
    public static boolean mayStrike(UUID a, UUID b, long nowTick) {
        Map<UUID, Long> byAttacker = rights.get(a);
        if (byAttacker == null) return false;
        Long expiry = byAttacker.get(b);
        if (expiry == null) return false;
        if (expiry <= nowTick) {
            byAttacker.remove(b); // prune lazily; no tick loop needed
            return false;
        }
        return true;
    }

    public static void clearAll() {
        rights.clear();
    }
}
