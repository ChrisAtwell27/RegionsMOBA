package com.regionsmoba.timeline;

import com.regionsmoba.match.MatchManager;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Per-second cold-season effects on match players. For slice 5 this covers the
 * global rule from docs/src-md/gameplay/cold-season.md:
 *   "Any player standing in water takes Slowness I for as long as they remain in
 *    it, regardless of team or biome."
 *
 * Per-biome effects (water freezing, drowned aggression, crops, deposit cooldown
 * scaling, cold-water Nether damage) land in slice 6+.
 */
public final class ColdSeasonEffects {

    /** Refresh once per second; effect duration is 2s so a single missed tick doesn't drop it. */
    public static final int APPLY_INTERVAL_TICKS = 20;
    public static final int EFFECT_DURATION_TICKS = 40;

    private static final Holder<MobEffect> SLOWNESS = MobEffects.MOVEMENT_SLOWDOWN;

    private ColdSeasonEffects() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % APPLY_INTERVAL_TICKS != 0) return;
        if (!MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;

        for (java.util.UUID id : MatchManager.get().matchPlayers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (!p.isInWater()) continue;
            // Re-apply Slowness I; existing instance is replaced.
            p.addEffect(new MobEffectInstance(
                    SLOWNESS, EFFECT_DURATION_TICKS, /* amplifier */ 0,
                    /* ambient */ true, /* visible */ false, /* showIcon */ true));
        }
    }
}
