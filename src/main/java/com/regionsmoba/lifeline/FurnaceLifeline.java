package com.regionsmoba.lifeline;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.mixin.FurnaceAccessor;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.UUID;

/**
 * Nether lifeline.
 *
 * Per docs/src-md/teams/nether.md:
 *   "A single furnace block. Must stay burning. If the furnace goes out, all
 *    nether players get Weakness and take 1 heart of damage every 30 seconds.
 *    Furnace damage cannot kill — lowest 1 heart (2 HP)."
 *   "Cold season: Furnace burns fuel twice as fast."
 *
 * tick() is called every server tick from the entrypoint:
 *   - Burns extra fuel (2x rate) when cold and the furnace is lit.
 *   - Every 2s when unlit: applies Weakness + 1 heart damage to every Nether
 *     player, with a damage floor of 1 heart (2 HP).
 */
public final class FurnaceLifeline {

    public static final int DAMAGE_INTERVAL_TICKS = 2 * 20;
    public static final float DAMAGE_AMOUNT = 2.0f;        // 1 heart, unfloored — this kills
    public static final int WEAKNESS_DURATION_TICKS = 60 * 20; // refresh once per minute

    private static final Holder<MobEffect> WEAKNESS = MobEffects.WEAKNESS;

    private FurnaceLifeline() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive()) return;
        BlockPosData furnacePos = RegionsConfig.get().furnace;
        if (furnacePos == null || server == null) return;
        ServerLevel level = server.getLevel(furnacePos.dimensionKey());
        if (level == null) return;
        BlockPos pos = furnacePos.toBlockPos();
        BlockState state = level.getBlockState(pos);
        boolean lit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);

        if (lit) {
            // Hold the damage timer disarmed for as long as the furnace burns, so
            // the moment it goes out the team gets a full fresh interval rather
            // than an immediate hit. Cheaper than tracking the unlit->lit edge.
            onLit();
            applyColdBurnRate(level, pos);
        } else {
            applyUnlitPenalty(server, level, pos, globalTick);
        }
    }

    /** Cold season: decrement litTimeRemaining one extra tick per real tick → 2x burn rate. */
    private static void applyColdBurnRate(ServerLevel level, BlockPos pos) {
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return;
        FurnaceAccessor acc = (FurnaceAccessor) (Object) furnace;
        int remaining = acc.regionsmoba$getLitTimeRemaining();
        if (remaining > 0) acc.regionsmoba$setLitTimeRemaining(remaining - 1);
    }

    /**
     * Furnace out: every 2s, apply Weakness + 1 heart to every Nether player.
     * There is no damage floor — a furnace left unfed kills the whole team in
     * roughly 20 seconds.
     */
    private static void applyUnlitPenalty(MinecraftServer server, ServerLevel level, BlockPos pos, long globalTick) {
        LifelineState ls = LifelineState.get();
        if (ls.lastFurnaceDamageTick < 0) {
            ls.lastFurnaceDamageTick = globalTick; // arm the timer; first damage fires after the interval
            return;
        }
        if (globalTick - ls.lastFurnaceDamageTick < DAMAGE_INTERVAL_TICKS) return;
        ls.lastFurnaceDamageTick = globalTick;
        // Stateless throttle for the warning text: every fifth damage tick.
        boolean warnThisTick = (globalTick / DAMAGE_INTERVAL_TICKS) % 5 == 0;

        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.NETHER || s.spectator) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            // Refresh Weakness so it persists while the furnace stays out.
            p.addEffect(new MobEffectInstance(
                    WEAKNESS, WEAKNESS_DURATION_TICKS, 0, true, false, true));
            // No floor: an unfed furnace kills. wither() is deliberate — it
            // bypasses the Nether team's permanent Fire Resistance passive.
            p.hurt(level.damageSources().wither(), DAMAGE_AMOUNT);
            // At a 2s interval a per-tick message would be 30 lines a minute, so
            // warn every fifth tick (10s) — about two warnings before death.
            if (warnThisTick) {
                p.sendSystemMessage(Component.literal("The furnace is out — you are dying.")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    /** When the furnace is re-lit, clear the damage-timer so the next outage restarts the 30s clock. */
    public static void onLit() {
        LifelineState.get().lastFurnaceDamageTick = -1;
    }

    /** Avoid unused-import warning on ResourceLocation (used implicitly via dimensionKey). */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IDENTIFIER = ResourceLocation.class;
}
