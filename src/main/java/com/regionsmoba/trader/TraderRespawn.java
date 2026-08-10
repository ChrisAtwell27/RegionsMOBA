package com.regionsmoba.trader;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.team.BiomeTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;

import java.util.Map;

/**
 * Per docs/src-md/gameplay/timeline.md: "Traders are re-spawned at their
 * registered points" on match end. We despawn the existing trader entity (by
 * UUID lookup) and spawn a fresh Villager/Piglin at the registered position.
 *
 * Plains, ocean, mountain → Villager; nether → Piglin (matches /regions
 * spawntrader behaviour).
 *
 * Updates RegionsConfig.traders[team] with the new UUID + position so the
 * registry stays in sync for the next match.
 */
public final class TraderRespawn {

    private TraderRespawn() {}

    public static int respawnAll(MinecraftServer server) {
        if (server == null) return 0;
        int respawned = 0;
        for (Map.Entry<String, TraderRef> e : RegionsConfig.get().traders.entrySet()) {
            BiomeTeam team = BiomeTeam.fromId(e.getKey()).orElse(null);
            if (team == null) continue;
            if (respawnOne(server, team)) respawned++;
        }
        if (respawned > 0) RegionsConfig.save();
        return respawned;
    }

    /** Respawn the named team's trader. Returns true if it was registered + respawn succeeded. */
    public static boolean respawnOne(MinecraftServer server, BiomeTeam team) {
        TraderRef ref = RegionsConfig.get().traders.get(team.id());
        if (ref == null) return false;
        BlockPosData pos = ref.pos();
        ServerLevel level = server.getLevel(pos.dimensionKey());
        if (level == null) {
            RegionsMOBA.LOGGER.warn("Trader respawn: dimension {} not loaded", pos.dimensionOrDefault());
            return false;
        }
        // Despawn the previous entity if it still exists.
        Entity prev = level.getEntity(ref.entityUuid());
        if (prev == null) {
            // Try other dimensions in case it wandered.
            for (ServerLevel any : server.getAllLevels()) {
                Entity e = any.getEntity(ref.entityUuid());
                if (e != null) {
                    e.discard();
                    break;
                }
            }
        } else {
            prev.discard();
        }
        EntityType<?> type = (team == BiomeTeam.NETHER) ? EntityType.PIGLIN : EntityType.VILLAGER;
        Entity spawned = type.spawn(level, pos.toBlockPos(), MobSpawnType.COMMAND);
        if (spawned == null) {
            RegionsMOBA.LOGGER.warn("Trader respawn: failed to spawn {} for {}",
                    type.builtInRegistryHolder().key().location(), team.id());
            return false;
        }
        RegionsConfig.get().traders.put(team.id(), new TraderRef(spawned.getUUID(), pos));
        RegionsMOBA.LOGGER.info("Respawned {} trader at {}", team.id(), pos);
        return true;
    }
}
