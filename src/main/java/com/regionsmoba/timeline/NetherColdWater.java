package com.regionsmoba.timeline;

import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Per docs/src-md/gameplay/cold-season.md:
 *   "Cold water (water not adjacent to a heat source within 1–2 blocks) damages
 *    Nether players on contact — fire-aspect weakness."
 *
 * Implementation: every second during a cold phase, scan each Nether player. If
 * they're touching water that has no heat source within 2 blocks, apply 1 HP of
 * damage. The damage source is "wither" so the team's Fire Resistance passive
 * doesn't accidentally cancel it.
 *
 * Heat sources (per the docs' "1–2 block" rule): lava, fire, soul fire, magma,
 * lit campfire, lit furnace, torch, lantern, jack o'lantern.
 */
public final class NetherColdWater {

    public static final int APPLY_INTERVAL_TICKS = 20;
    public static final float DAMAGE_AMOUNT = 1.0f;
    public static final int HEAT_RADIUS = 2;

    private NetherColdWater() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % APPLY_INTERVAL_TICKS != 0) return;
        if (!MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        if (server == null) return;

        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.NETHER || s.spectator) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isInWater()) continue;
            ServerLevel level = p.serverLevel();
            if (hasHeatNearby(level, p.blockPosition())) continue;
            p.hurt(level.damageSources().wither(), DAMAGE_AMOUNT);
        }
    }

    public static boolean hasHeatNearby(ServerLevel level, BlockPos centre) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -HEAT_RADIUS; dx <= HEAT_RADIUS; dx++) {
            for (int dy = -HEAT_RADIUS; dy <= HEAT_RADIUS; dy++) {
                for (int dz = -HEAT_RADIUS; dz <= HEAT_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (isHeatSource(level.getBlockState(cursor))) return true;
                }
            }
        }
        return false;
    }

    private static boolean isHeatSource(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.JACK_O_LANTERN);
    }
}
