package com.regionsmoba.events;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.team.BiomeTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Nether mass event — single Wither at the registered wither spawn point.
 * Default HP, stationary (pinned by MassEventTargeting), only targets non-Nether
 * players. Vanilla AI handles wither-skull projectiles toward the picked target.
 */
public final class NetherMassEvent {

    private NetherMassEvent() {}

    public static void spawn(MinecraftServer server) {
        if (server == null) return;
        BlockPosData spawn = RegionsConfig.get().witherSpawn;
        if (spawn == null) {
            RegionsMOBA.LOGGER.warn("Nether mass event: no wither spawn registered");
            return;
        }
        ServerLevel level = server.getLevel(spawn.dimensionKey());
        if (level == null) return;
        Entity e = EntityType.WITHER.spawn(level, spawn.toBlockPos(), MobSpawnType.EVENT);
        if (e == null) return;
        ModEntities.track(e);
        MassEventTargeting.get().tag(e, BiomeTeam.NETHER);
        MassEventTargeting.get().pin(e, level, spawn.toBlockPos());
        RegionsMOBA.LOGGER.info("Nether mass event: Wither spawned");
    }
}
