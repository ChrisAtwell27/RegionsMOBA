package com.regionsmoba.classes.impl;

import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.NetherColdWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Ocean Neptune — Ground Freeze passive (toggleable in spec, always-on here).
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   "While toggled on, walking over water or lava freezes it up to 8 blocks
 *    ahead (11×7 area), or up to 6 blocks diagonally. Does not trigger while
 *    the player is submerged."
 *
 * Slice 15 implementation: per-second tick scans an 11×7 forward area. Replaces
 * water with ice (and lava with magma) on positions that are not adjacent to a
 * heat source — re-using the heat-source predicate from NetherColdWater so cold
 * Nether water and cold ocean water behave consistently. Submerged check skips.
 *
 * The ice produced goes through the snapshot mixin, so map repair restores the
 * water on match end.
 *
 * Toggle (right-click ice block to enable/disable) is a polish pass — slice 15
 * leaves the freeze always-on while held in main hand.
 */
public final class NeptuneGroundFreeze {

    public static final int FORWARD_RANGE = 8;
    public static final int SIDE_RANGE = 5;

    private NeptuneGroundFreeze() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % 10 != 0) return; // 0.5s
        if (server == null || !MatchManager.get().isActive()) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.biomeClass != BiomeClass.OCEAN_NEPTUNE) continue;
            if (p.isUnderWater()) continue; // doc rule
            // Only act when Neptune is holding the trident, to keep the cost low.
            if (!p.getMainHandItem().is(net.minecraft.world.item.Items.TRIDENT)) continue;
            freezeForward(p);
        }
    }

    private static void freezeForward(ServerPlayer neptune) {
        ServerLevel level = neptune.level();
        BlockPos centre = neptune.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SIDE_RANGE; dx <= SIDE_RANGE; dx++) {
            for (int dz = 0; dz <= FORWARD_RANGE; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    BlockState replacement = freezeReplacement(state);
                    if (replacement == null) continue;
                    if (NetherColdWater.hasHeatNearby(level, cursor)) continue;
                    level.setBlock(cursor.immutable(), replacement, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static BlockState freezeReplacement(BlockState state) {
        if (state.is(Blocks.WATER)) return Blocks.ICE.defaultBlockState();
        if (state.is(Blocks.LAVA)) return Blocks.MAGMA_BLOCK.defaultBlockState();
        return null;
    }
}
