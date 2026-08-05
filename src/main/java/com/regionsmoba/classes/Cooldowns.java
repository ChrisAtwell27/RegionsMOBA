package com.regionsmoba.classes;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player, per-ability cooldown tracker. Keyed by (player UUID, ability id).
 * Stores the server tick at which the ability is ready again.
 *
 * Singleton. Reset wholesale on match start/end via {@link #clearAll()} and
 * per-player via {@link #clearForPlayer(UUID)}.
 */
public final class Cooldowns {

    private static Cooldowns instance;

    private final Map<UUID, Map<String, Long>> readyAtTick = new HashMap<>();
    private long currentTick;

    private Cooldowns() {}

    public static Cooldowns get() {
        if (instance == null) instance = new Cooldowns();
        return instance;
    }

    /** Updates the internal tick clock. Called from {@link AbilityHooks#tick}. */
    public void tick(long tick) {
        this.currentTick = tick;
    }

    public boolean isReady(UUID player, String abilityId) {
        Map<String, Long> map = readyAtTick.get(player);
        if (map == null) return true;
        Long ready = map.get(abilityId);
        return ready == null || ready <= currentTick;
    }

    /** Returns ticks remaining, or 0 if ready. */
    public long remaining(UUID player, String abilityId) {
        Map<String, Long> map = readyAtTick.get(player);
        if (map == null) return 0;
        Long ready = map.get(abilityId);
        if (ready == null) return 0;
        long diff = ready - currentTick;
        return Math.max(0, diff);
    }

    public void start(UUID player, String abilityId, int durationTicks) {
        readyAtTick.computeIfAbsent(player, k -> new HashMap<>())
                .put(abilityId, currentTick + durationTicks);
    }

    // ---- Player/seconds convenience wrappers ----
    //
    // Ability implementations think in whole seconds and always have the
    // ServerPlayer in hand; the core map above stays UUID/tick-based so match
    // teardown can clear entries for players who are already offline.

    /** True when the ability is off cooldown for this player. */
    public boolean ready(ServerPlayer player, String abilityId) {
        return isReady(player.getUUID(), abilityId);
    }

    /** Whole seconds remaining, rounded up so a sub-second remainder never displays as "0s". */
    public long remainingSeconds(ServerPlayer player, String abilityId) {
        long ticks = remaining(player.getUUID(), abilityId);
        return (ticks + 19) / 20;
    }

    /** Put the ability on cooldown for the given number of seconds. */
    public void set(ServerPlayer player, String abilityId, int durationSeconds) {
        start(player.getUUID(), abilityId, durationSeconds * 20);
    }

    public void clearForPlayer(UUID player) {
        readyAtTick.remove(player);
    }

    public void clearAll() {
        readyAtTick.clear();
    }
}
