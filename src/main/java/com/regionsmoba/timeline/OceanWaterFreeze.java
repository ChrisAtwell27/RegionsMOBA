package com.regionsmoba.timeline;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Per docs/src-md/gameplay/cold-season.md (Ocean entry):
 *   "Water not adjacent to a heat source within 1–2 blocks freezes."
 *
 * Slice 17 implementation: per-second scan of a bounded box around each Ocean
 * player who is inside the Ocean biome bounds. Replaces water with ice on
 * positions that have no heat source within 2 blocks, re-using
 * {@link NetherColdWater#hasHeatNearby}. Map repair restores the water on
 * match end via the snapshot mixin.
 *
 * Bounded scan keeps cost predictable — we don't iterate the whole biome each
 * tick. Players near each other will end up freezing the whole occupied area
 * within a few seconds.
 */
public final class OceanWaterFreeze {

    public static final int TICK_INTERVAL = 20;     // 1s
    public static final int RADIUS_HORIZONTAL = 16;
    public static final int RADIUS_VERTICAL = 4;

    private OceanWaterFreeze() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % TICK_INTERVAL != 0) return;
        if (server == null || !MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        Area oceanBounds = RegionsConfig.get().biomeBounds(BiomeTeam.OCEAN);
        if (oceanBounds == null || !oceanBounds.isComplete()) return;

        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.OCEAN) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (!oceanBounds.dimension().equals(p.level().dimension().location().toString())) continue;
            if (!oceanBounds.contains(p.blockPosition())) continue;
            freezeAround(p, oceanBounds);
        }
    }

    private static void freezeAround(ServerPlayer player, Area bounds) {
        ServerLevel level = player.serverLevel();
        BlockPos centre = player.blockPosition();
        int minX = Math.max(centre.getX() - RADIUS_HORIZONTAL, bounds.low().x());
        int maxX = Math.min(centre.getX() + RADIUS_HORIZONTAL, bounds.high().x());
        int minY = Math.max(centre.getY() - RADIUS_VERTICAL, bounds.low().y());
        int maxY = Math.min(centre.getY() + RADIUS_VERTICAL, bounds.high().y());
        int minZ = Math.max(centre.getZ() - RADIUS_HORIZONTAL, bounds.low().z());
        int maxZ = Math.min(centre.getZ() + RADIUS_HORIZONTAL, bounds.high().z());

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.is(Blocks.WATER)) continue;
                    if (NetherColdWater.hasHeatNearby(level, cursor)) continue;
                    level.setBlock(cursor.immutable(), Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}
