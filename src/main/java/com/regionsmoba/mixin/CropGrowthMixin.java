package com.regionsmoba.mixin;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.NetherColdWater;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per docs/src-md/gameplay/cold-season.md (Plains entry):
 *   "Crops stop growing unless within 1–2 blocks of a heat source."
 *
 * Cancels CropBlock.randomTick when the match is active in COLD phase, the
 * crop is inside the registered Plains biome bounds, and there's no heat
 * source within 2 blocks (re-uses NetherColdWater.hasHeatNearby for the
 * predicate).
 *
 * Wheat / carrot / potato / beetroot all extend CropBlock so this single
 * mixin covers them. Sweet berries (SweetBerryBushBlock) are a separate class
 * and aren't covered yet — polish.
 */
@Mixin(CropBlock.class)
public class CropGrowthMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void regionsmoba$blockColdGrowth(BlockState state, ServerLevel level, BlockPos pos,
                                             RandomSource random, CallbackInfo ci) {
        if (!MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        Area plains = RegionsConfig.get().biomeBounds(BiomeTeam.PLAINS);
        if (plains == null || !plains.isComplete()) return;
        if (!plains.dimension().equals(level.dimension().location().toString())) return;
        if (!plains.contains(pos)) return;
        if (NetherColdWater.hasHeatNearby(level, pos)) return;
        ci.cancel();
    }
}
