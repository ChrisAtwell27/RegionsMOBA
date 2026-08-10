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

import java.util.Map;

/**
 * Ocean mass event — one Elder Guardian at every registered guardian spawn slot.
 * Default HP, stationary (pinned by MassEventTargeting), attacks any non-Ocean
 * player in range using the vanilla laser/thorns attack.
 */
public final class OceanMassEvent {

    private OceanMassEvent() {}

    public static void spawn(MinecraftServer server) {
        if (server == null) return;
        Map<Integer, BlockPosData> spawns = RegionsConfig.get().guardianSpawns;
        if (spawns.isEmpty()) {
            RegionsMOBA.LOGGER.warn("Ocean mass event: no guardian spawns registered");
            return;
        }
        int spawned = 0;
        for (BlockPosData pos : spawns.values()) {
            ServerLevel level = server.getLevel(pos.dimensionKey());
            if (level == null) continue;
            Entity e = EntityType.ELDER_GUARDIAN.spawn(level, pos.toBlockPos(), MobSpawnType.EVENT);
            if (e == null) continue;
            ModEntities.track(e);
            MassEventTargeting.get().tag(e, BiomeTeam.OCEAN);
            MassEventTargeting.get().pin(e, level, pos.toBlockPos());
            spawned++;
        }
        RegionsMOBA.LOGGER.info("Ocean mass event: {} elder guardians spawned", spawned);
    }
}
