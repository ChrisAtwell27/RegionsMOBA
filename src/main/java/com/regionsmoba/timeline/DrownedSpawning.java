package com.regionsmoba.timeline;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Per docs/src-md/gameplay/cold-season.md (Ocean entry):
 *   "Drowned spawn aggressively in the ocean biome's water and attack any
 *    non-ocean player."
 *
 * Slice 17 implementation: every 5 seconds during cold, sample a random water
 * position inside the registered Ocean biome bounds. If under MAX_LIVE drowned
 * are tracked, spawn one and target it at the nearest non-Ocean player within
 * 32 blocks (if any). Each spawned drowned is tagged via {@link ModEntities}
 * so it despawns at match end.
 */
public final class DrownedSpawning {

    public static final int TICK_INTERVAL = 5 * 20;
    public static final int MAX_LIVE = 12;
    public static final int SAMPLES_PER_TICK = 6;
    public static final double TARGET_RANGE = 32.0;

    private static final Random RNG = new Random();
    /** Tracked drowned UUIDs so we cap concurrent count. */
    private static final java.util.Set<UUID> live = new java.util.HashSet<>();

    private DrownedSpawning() {}

    public static void clearAll() {
        live.clear();
    }

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % TICK_INTERVAL != 0) return;
        if (server == null || !MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        Area bounds = RegionsConfig.get().biomeBounds(BiomeTeam.OCEAN);
        if (bounds == null || !bounds.isComplete()) return;

        ServerLevel level = server.getLevel(bounds.high().dimensionKey());
        if (level == null) return;

        // Drop dead UUIDs.
        live.removeIf(id -> level.getEntity(id) == null);
        if (live.size() >= MAX_LIVE) return;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < SAMPLES_PER_TICK; attempt++) {
            int x = bounds.low().x() + RNG.nextInt(Math.max(1, bounds.high().x() - bounds.low().x() + 1));
            int y = bounds.low().y() + RNG.nextInt(Math.max(1, bounds.high().y() - bounds.low().y() + 1));
            int z = bounds.low().z() + RNG.nextInt(Math.max(1, bounds.high().z() - bounds.low().z() + 1));
            cursor.set(x, y, z);
            if (!level.hasChunkAt(cursor)) continue;
            if (!level.getBlockState(cursor).is(Blocks.WATER)) continue;
            spawnDrowned(level, cursor.immutable());
            return;
        }
    }

    private static void spawnDrowned(ServerLevel level, BlockPos pos) {
        Entity e = EntityType.DROWNED.spawn(level, pos, MobSpawnType.NATURAL);
        if (e == null) return;
        ModEntities.track(e);
        live.add(e.getUUID());
        if (e instanceof Drowned drowned) {
            Player target = nearestNonOcean(drowned, TARGET_RANGE);
            if (target instanceof LivingEntity living) drowned.setTarget(living);
        }
        RegionsMOBA.LOGGER.debug("Drowned spawned at {} ({} live)", pos, live.size());
    }

    private static Player nearestNonOcean(Mob mob, double range) {
        AABB box = new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range);
        List<Player> nearby = mob.level().getEntitiesOfClass(Player.class, box, p -> {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team != null && s.team != BiomeTeam.OCEAN && !s.spectator;
        });
        Player closest = null;
        double bestSq = range * range;
        for (Player p : nearby) {
            double dsq = p.distanceToSqr(mob);
            if (dsq < bestSq) {
                bestSq = dsq;
                closest = p;
            }
        }
        return closest;
    }

    /** Target-filter helper: drowned spawned by us never target Ocean players. Called from MassEventTargeting-style tick. */
    public static boolean isOursAndShouldNotTarget(Entity entity, BiomeTeam targetTeam) {
        if (!live.contains(entity.getUUID())) return false;
        return targetTeam == BiomeTeam.OCEAN;
    }
}
