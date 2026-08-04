package com.regionsmoba.classes.impl;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mountain Berserker — gains hearts on enemy kills, takes a -5-heart hit on death,
 * loses kit-granted heart bonus on respawn until earned back.
 *
 * Stub: API surface exists so death/respawn handlers compile. Behavior is no-op
 * until the ability is implemented.
 */
public final class BerserkerAbility {

    private BerserkerAbility() {}

    /** Called from {@code ServerLivingEntityEvents.AFTER_DEATH} for every living-entity death. */
    public static void onEnemyDeath(LivingEntity victim, DamageSource source) {
        // TODO: credit Berserker killer with a heart stack.
    }

    /** Called when a Berserker themselves dies, before life decrement. */
    public static void onBerserkerDeath(ServerPlayer berserker) {
        // TODO: -5 heart penalty on the running Berserker stack.
    }

    /** Called from AFTER_RESPAWN to re-apply Berserker passive on the new player object. */
    public static void reapply(ServerPlayer player) {
        // TODO: re-apply attribute modifiers based on current heart-stack count.
    }

    public static void clearAll() {
        // TODO: drop all per-player heart-stack state.
    }
}
