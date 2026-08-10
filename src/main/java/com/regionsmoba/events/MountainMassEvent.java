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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Mountain mass event — single Warden at the registered warden spawn point with
 * 100 HP (reduced from vanilla 500). Per docs/src-md/gameplay/mass-events.md it
 * never aggros Mountain players (handled by MassEventTargeting) and attacks any
 * non-Mountain player in range.
 */
public final class MountainMassEvent {

    public static final float WARDEN_HP = 100.0f;

    private MountainMassEvent() {}

    public static void spawn(MinecraftServer server) {
        if (server == null) return;
        BlockPosData spawn = RegionsConfig.get().wardenSpawn;
        if (spawn == null) {
            RegionsMOBA.LOGGER.warn("Mountain mass event: no warden spawn registered");
            return;
        }
        ServerLevel level = server.getLevel(spawn.dimensionKey());
        if (level == null) return;
        Entity e = EntityType.WARDEN.spawn(level, spawn.toBlockPos(), MobSpawnType.EVENT);
        if (e == null) return;
        ModEntities.track(e);
        MassEventTargeting.get().tag(e, BiomeTeam.MOUNTAIN);
        if (e instanceof LivingEntity living) {
            AttributeInstance maxHp = living.getAttribute(Attributes.MAX_HEALTH);
            if (maxHp != null) maxHp.setBaseValue(WARDEN_HP);
            living.setHealth(WARDEN_HP);
        }
        RegionsMOBA.LOGGER.info("Mountain mass event: Warden spawned with {} HP", WARDEN_HP);
    }
}
