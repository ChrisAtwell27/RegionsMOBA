package com.regionsmoba.deposit;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.classes.impl.MinerAbility;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.MobDeposit;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Runtime cooldown state for ore + block deposits, and the per-mob spawn timer
 * for mob deposits.
 *
 * Per docs/src-md/gameplay/deposits.md:
 *   Ore deposit  — mined, converts to cobblestone, regenerates to original ore
 *                  after the configured cooldown.
 *   Block deposit — mined, breaks fully (drops + leaves air), regenerates to the
 *                   registered block after the cooldown.
 *   Mob deposit   — spawns one mob of the configured type at the configured
 *                   interval; mod-spawned, despawned at match end.
 *
 * Cold-season Mountain rule (docs/src-md/gameplay/cold-season.md):
 *   "Mountain ore deposit cooldowns increase." We apply 2x cooldown to ore
 *   deposits inside the registered Mountain biome bounds while the phase is COLD.
 *
 * In-memory only. Reset on match start / end.
 */
public final class DepositTracker {

    private static final DepositTracker INSTANCE = new DepositTracker();

    public static DepositTracker get() {
        return INSTANCE;
    }

    /** end-tick keyed by deposit position (only for ore + block). */
    private final Map<BlockPosData, Long> oreCooldowns = new HashMap<>();
    private final Map<BlockPosData, Long> blockCooldowns = new HashMap<>();
    /** next-spawn tick keyed by mob deposit position. */
    private final Map<BlockPosData, Long> mobNextSpawn = new HashMap<>();

    private DepositTracker() {}

    public void resetAll() {
        oreCooldowns.clear();
        blockCooldowns.clear();
        mobNextSpawn.clear();
    }

    /**
     * Called when an ore deposit was just mined. Replaces the broken air with
     * cobblestone and schedules regen.
     *
     * {@code miner} is the player who broke it, used only to check for an active
     * Gold Rush; null is accepted for callers with no player context.
     */
    public void onOreMined(ServerLevel level, BlockPos pos, OreDeposit deposit, long globalTick,
                           ServerPlayer miner) {
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        long regenTicks = computeRegenTicks(deposit, level, miner);
        oreCooldowns.put(deposit.pos(), globalTick + regenTicks);
    }

    /** Called when a block deposit was just mined. Block is air (vanilla break left it that way). */
    public void onBlockMined(BlockDeposit deposit, long globalTick) {
        long regenTicks = (long) deposit.regenSeconds() * Timeline.TICKS_PER_SECOND;
        blockCooldowns.put(deposit.pos(), globalTick + regenTicks);
    }

    /**
     * Per-tick regen + mob spawn. Iterates pending cooldowns and tries to restore
     * elapsed deposits; tries each mob deposit's spawn timer.
     */
    public void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive() || server == null) return;

        // Ore regen — restore original ore.
        Iterator<Map.Entry<BlockPosData, Long>> oreIt = oreCooldowns.entrySet().iterator();
        while (oreIt.hasNext()) {
            Map.Entry<BlockPosData, Long> e = oreIt.next();
            if (globalTick < e.getValue()) continue;
            OreDeposit d = findOre(e.getKey());
            if (d == null) {
                oreIt.remove();
                continue;
            }
            ServerLevel lvl = server.getLevel(d.pos().dimensionKey());
            if (lvl == null || !lvl.hasChunkAt(d.pos().toBlockPos())) continue;
            BlockState state = blockStateFor(d.oreId(), Blocks.COBBLESTONE);
            lvl.setBlock(d.pos().toBlockPos(), state, Block.UPDATE_ALL);
            oreIt.remove();
        }

        // Block deposit regen — restore registered block.
        Iterator<Map.Entry<BlockPosData, Long>> blockIt = blockCooldowns.entrySet().iterator();
        while (blockIt.hasNext()) {
            Map.Entry<BlockPosData, Long> e = blockIt.next();
            if (globalTick < e.getValue()) continue;
            BlockDeposit d = findBlock(e.getKey());
            if (d == null) {
                blockIt.remove();
                continue;
            }
            ServerLevel lvl = server.getLevel(d.pos().dimensionKey());
            if (lvl == null || !lvl.hasChunkAt(d.pos().toBlockPos())) continue;
            BlockState state = blockStateFor(d.blockId(), Blocks.AIR);
            lvl.setBlock(d.pos().toBlockPos(), state, Block.UPDATE_ALL);
            blockIt.remove();
        }

        // Mob deposit spawning.
        for (MobDeposit d : RegionsConfig.get().mobDeposits) {
            long next = mobNextSpawn.getOrDefault(d.pos(), -1L);
            if (next < 0) {
                mobNextSpawn.put(d.pos(), globalTick + (long) d.intervalSeconds() * Timeline.TICKS_PER_SECOND);
                continue;
            }
            if (globalTick < next) continue;
            ServerLevel lvl = server.getLevel(d.pos().dimensionKey());
            if (lvl != null && lvl.hasChunkAt(d.pos().toBlockPos())) {
                spawnMob(lvl, d);
            }
            mobNextSpawn.put(d.pos(), globalTick + (long) d.intervalSeconds() * Timeline.TICKS_PER_SECOND);
        }
    }

    private void spawnMob(ServerLevel lvl, MobDeposit d) {
        Identifier id = Identifier.tryParse(d.mobId());
        if (id == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) return;
        BlockPos pos = d.pos().toBlockPos().above();
        Entity spawned = type.spawn(lvl, pos, EntitySpawnReason.SPAWNER);
        if (spawned != null) ModEntities.track(spawned);
    }

    private long computeRegenTicks(OreDeposit deposit, ServerLevel level, ServerPlayer miner) {
        long base = (long) deposit.regenSeconds() * Timeline.TICKS_PER_SECOND;
        // Mountain cold-season scaling: 2x cooldown for ore deposits inside the
        // registered Mountain biome bounds while the phase is COLD. A Miner with
        // Gold Rush up is exempt, per docs/src-md/classes/mountain-classes.md.
        if (Timeline.get().phase() == MatchPhase.COLD && isInsideMountain(deposit.pos(), level)) {
            if (miner != null && MinerAbility.isGoldRushActive(miner.getUUID())) return base;
            return base * 2L;
        }
        return base;
    }

    private boolean isInsideMountain(BlockPosData pos, ServerLevel level) {
        Area mountain = RegionsConfig.get().biomeBounds(BiomeTeam.MOUNTAIN);
        if (mountain == null || !mountain.isComplete()) return false;
        if (!mountain.dimension().equals(level.dimension().identifier().toString())) return false;
        return mountain.contains(pos.toBlockPos());
    }

    private static BlockState blockStateFor(String blockId, Block fallback) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) return fallback.defaultBlockState();
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) {
            RegionsMOBA.LOGGER.warn("Unknown block id in deposit registration: {}", blockId);
            return fallback.defaultBlockState();
        }
        return block.defaultBlockState();
    }

    private static OreDeposit findOre(BlockPosData pos) {
        for (OreDeposit d : RegionsConfig.get().oreDeposits) if (d.pos().equals(pos)) return d;
        return null;
    }

    private static BlockDeposit findBlock(BlockPosData pos) {
        for (BlockDeposit d : RegionsConfig.get().blockDeposits) if (d.pos().equals(pos)) return d;
        return null;
    }

    /** /regions debug deposits regen — restore every pending cooldown immediately. */
    public void regenAllNow(MinecraftServer server) {
        if (server == null) return;
        for (Map.Entry<BlockPosData, Long> e : oreCooldowns.entrySet()) {
            OreDeposit d = findOre(e.getKey());
            if (d == null) continue;
            ServerLevel lvl = server.getLevel(d.pos().dimensionKey());
            if (lvl == null || !lvl.hasChunkAt(d.pos().toBlockPos())) continue;
            lvl.setBlock(d.pos().toBlockPos(), blockStateFor(d.oreId(), Blocks.COBBLESTONE), Block.UPDATE_ALL);
        }
        for (Map.Entry<BlockPosData, Long> e : blockCooldowns.entrySet()) {
            BlockDeposit d = findBlock(e.getKey());
            if (d == null) continue;
            ServerLevel lvl = server.getLevel(d.pos().dimensionKey());
            if (lvl == null || !lvl.hasChunkAt(d.pos().toBlockPos())) continue;
            lvl.setBlock(d.pos().toBlockPos(), blockStateFor(d.blockId(), Blocks.AIR), Block.UPDATE_ALL);
        }
        oreCooldowns.clear();
        blockCooldowns.clear();
    }

    /** /regions debug deposits reset — clear cooldowns; the next mining starts a fresh timer. */
    public void resetCooldowns() {
        oreCooldowns.clear();
        blockCooldowns.clear();
        mobNextSpawn.clear();
    }
}
