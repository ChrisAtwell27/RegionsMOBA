package com.regionsmoba.chamber;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Trial chamber waves. Per docs/src-md/gameplay/trial-chamber.md:
 *   "Every 10 minutes, trial spawners activate a large enemy wave."
 *   "Every registered mob spawn point releases enemies at the start of a wave."
 *   "Kills are tracked per team for the duration of the wave."
 *   "The wave ends when every spawned enemy is dead or despawned."
 *   "At the end of a wave, the team with the most enemy kills wins the loot."
 *   "Ties split the loot evenly between tied teams."
 *
 * Loot: Strength I + Speed I for 10 min on every living winning-team member,
 * plus a stack of emeralds dropped inside the chamber. Slice 8 ships this MVP
 * loot table; the rotating drop table is a polish pass.
 *
 * /regions debug chamber wave start|end pokes this directly. waves on|off
 * suspends the 10-min cadence (debug toggle).
 */
public final class TrialChamber {

    public static final int WAVE_INTERVAL_TICKS = 10 * 60 * 20;
    public static final int MOBS_PER_SPAWN = 3;
    public static final int LOOT_POTION_DURATION_TICKS = 10 * 60 * 20;
    public static final int EMERALD_STACK = 16;

    private static final TrialChamber INSTANCE = new TrialChamber();

    public static TrialChamber get() {
        return INSTANCE;
    }

    private static final Holder<MobEffect> STRENGTH = MobEffects.DAMAGE_BOOST;
    private static final Holder<MobEffect> SPEED = MobEffects.MOVEMENT_SPEED;

    private boolean cadenceEnabled = true;
    private boolean waveActive;
    private long nextWaveTick = -1;
    private final Set<UUID> waveMobs = new HashSet<>();
    private final Map<BiomeTeam, Integer> killCounts = new EnumMap<>(BiomeTeam.class);

    private TrialChamber() {}

    public void resetAll() {
        cadenceEnabled = true;
        waveActive = false;
        nextWaveTick = -1;
        waveMobs.clear();
        killCounts.clear();
    }

    public void onMatchStart(long matchStartTick) {
        nextWaveTick = matchStartTick + WAVE_INTERVAL_TICKS;
    }

    public boolean waveActive() {
        return waveActive;
    }

    public void setCadenceEnabled(boolean enabled) {
        cadenceEnabled = enabled;
    }

    public boolean cadenceEnabled() {
        return cadenceEnabled;
    }

    /** Server tick — schedule waves and detect wave-end. */
    public void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive() || server == null) return;
        if (!waveActive && cadenceEnabled && nextWaveTick > 0 && globalTick >= nextWaveTick) {
            startWave(server);
        }
        if (waveActive && allWaveMobsDead(server)) {
            endWave(server);
            nextWaveTick = globalTick + WAVE_INTERVAL_TICKS;
        }
    }

    /** Death event hook — wave-mob deaths advance kill counts; remove the mob from the active set. */
    public void onLivingDeath(LivingEntity entity, Player attacker) {
        if (!waveActive) return;
        UUID id = entity.getUUID();
        if (!waveMobs.remove(id)) return;
        if (attacker == null) return;
        MatchPlayerState s = TeamAssignments.get().state(attacker.getUUID());
        if (s == null || s.team == null) return;
        killCounts.merge(s.team, 1, Integer::sum);
    }

    public void startWave(MinecraftServer server) {
        if (server == null) return;
        Area chamber = RegionsConfig.get().chamberBounds;
        if (chamber == null || !chamber.isComplete()) {
            RegionsMOBA.LOGGER.warn("Chamber wave: no chamber bounds registered");
            return;
        }
        if (RegionsConfig.get().chamberSpawns.isEmpty()) {
            RegionsMOBA.LOGGER.warn("Chamber wave: no chamber spawn points registered");
            return;
        }

        waveActive = true;
        waveMobs.clear();
        killCounts.clear();
        for (BiomeTeam t : BiomeTeam.values()) killCounts.put(t, 0);

        for (BlockPosData spawn : RegionsConfig.get().chamberSpawns) {
            ServerLevel spawnLevel = server.getLevel(spawn.dimensionKey());
            if (spawnLevel == null) continue;
            for (int i = 0; i < MOBS_PER_SPAWN; i++) {
                Entity e = EntityType.ZOMBIE.spawn(spawnLevel, spawn.toBlockPos(), MobSpawnType.SPAWNER);
                if (e == null) continue;
                ModEntities.track(e);
                waveMobs.add(e.getUUID());
            }
        }
        broadcastToMatch(server, Component.literal("Trial wave started — fight in the chamber!")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        RegionsMOBA.LOGGER.info("Trial wave started — {} mobs", waveMobs.size());
    }

    public void endWave(MinecraftServer server) {
        if (!waveActive || server == null) return;
        waveActive = false;
        waveMobs.clear();

        // Determine winner(s).
        int top = killCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<BiomeTeam> winners = new HashSet<>();
        if (top > 0) {
            for (Map.Entry<BiomeTeam, Integer> e : killCounts.entrySet()) {
                if (e.getValue() == top) winners.add(e.getKey());
            }
        }

        if (winners.isEmpty()) {
            broadcastToMatch(server, Component.literal("Trial wave ended — no kills, no loot.")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            String winnerNames = winners.stream().map(BiomeTeam::displayName).reduce((a, b) -> a + ", " + b).orElse("");
            broadcastToMatch(server, Component.literal(
                    "Trial wave won by " + winnerNames + " (" + top + " kills)")
                    .withStyle(ChatFormatting.GOLD));
            distributeLoot(server, winners);
        }
        RegionsMOBA.LOGGER.info("Trial wave ended; winners={} kills={}", winners, killCounts);
    }

    private void distributeLoot(MinecraftServer server, Set<BiomeTeam> winners) {
        // Potion buffs to every living winning-team player.
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.spectator || !winners.contains(s.team)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.addEffect(new MobEffectInstance(STRENGTH, LOOT_POTION_DURATION_TICKS, 0, true, false, true));
            p.addEffect(new MobEffectInstance(SPEED, LOOT_POTION_DURATION_TICKS, 0, true, false, true));
        }
        // Emerald drop split across winners — each winning team gets EMERALD_STACK.
        Area chamber = RegionsConfig.get().chamberBounds;
        if (chamber == null || !chamber.isComplete()) return;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(chamber.dimension())));
        if (level == null) return;
        Random rng = new Random();
        for (int n = 0; n < winners.size(); n++) {
            int x = chamber.low().x() + rng.nextInt(Math.max(1, chamber.high().x() - chamber.low().x() + 1));
            int z = chamber.low().z() + rng.nextInt(Math.max(1, chamber.high().z() - chamber.low().z() + 1));
            int y = chamber.low().y() + rng.nextInt(Math.max(1, chamber.high().y() - chamber.low().y() + 1));
            ItemStack stack = new ItemStack(Items.EMERALD, EMERALD_STACK);
            net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                    level, x + 0.5, y + 0.5, z + 0.5, stack);
            level.addFreshEntity(drop);
            // Note: the ItemEntity is left untracked — match-end despawn handles all dropped items.
        }
    }

    private boolean allWaveMobsDead(MinecraftServer server) {
        if (waveMobs.isEmpty()) return true;
        for (UUID id : waveMobs) {
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(id);
                if (e != null && e.isAlive()) return false;
            }
        }
        return true;
    }

    private static void broadcastToMatch(MinecraftServer server, Component msg) {
        if (server == null) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
