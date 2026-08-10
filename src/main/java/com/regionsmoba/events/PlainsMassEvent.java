package com.regionsmoba.events;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.team.BiomeTeam;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;

import java.util.Random;

/**
 * Plains mass event — 3 independent Pillager squads spawn inside the Plains
 * biome. Per docs/src-md/gameplay/mass-events.md they patrol independently and
 * attack any non-Plains player; vanilla Pillager AI handles the patrol loop.
 *
 * Each squad: one Pillager spawned with PATROL_LEADER, two followers spawned
 * with PATROL. The leader's patrol AI gathers nearby PATROL pillagers into a
 * squad. We tag each spawned mob via MassEventTargeting so it never aggros a
 * Plains player.
 */
public final class PlainsMassEvent {

    public static final int SQUAD_COUNT = 3;
    public static final int PER_SQUAD = 3;

    private PlainsMassEvent() {}

    public static void spawn(MinecraftServer server) {
        if (server == null) return;
        Area bounds = RegionsConfig.get().biomeBounds(BiomeTeam.PLAINS);
        if (bounds == null || !bounds.isComplete()) {
            RegionsMOBA.LOGGER.warn("Plains mass event: no biome bounds registered");
            return;
        }
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimKey =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(bounds.dimension()));
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) return;

        Random rng = new Random();
        for (int s = 0; s < SQUAD_COUNT; s++) {
            BlockPos centre = randomGroundIn(level, bounds, rng);
            for (int i = 0; i < PER_SQUAD; i++) {
                BlockPos p = centre.offset(rng.nextInt(5) - 2, 0, rng.nextInt(5) - 2);
                MobSpawnType reason = (i == 0) ? MobSpawnType.PATROL : MobSpawnType.PATROL;
                Entity e = EntityType.PILLAGER.spawn(level, p, reason);
                if (e == null) continue;
                ModEntities.track(e);
                MassEventTargeting.get().tag(e, BiomeTeam.PLAINS);
            }
        }
        RegionsMOBA.LOGGER.info("Plains mass event: {} pillager squads spawned", SQUAD_COUNT);
    }

    private static BlockPos randomGroundIn(ServerLevel level, Area bounds, Random rng) {
        int xRange = bounds.high().x() - bounds.low().x();
        int zRange = bounds.high().z() - bounds.low().z();
        int x = bounds.low().x() + rng.nextInt(Math.max(1, xRange + 1));
        int z = bounds.low().z() + rng.nextInt(Math.max(1, zRange + 1));
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
