package com.regionsmoba.repair;

import com.regionsmoba.RegionsMOBA;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-dimension block-state delta. Tracks the FIRST original BlockState observed at
 * each position that mutates while a match is active, plus the BlockEntity NBT if
 * the position had one — so chests, hoppers, signs, banners, etc. restore with
 * their full contents.
 *
 * The docs describe a "snapshot taken at /regions start"; we implement that as
 * delta tracking — same observable behavior, far less memory than copying every
 * loaded chunk.
 *
 * Per the timeline doc this covers player-broken blocks, player-placed blocks,
 * and mob-driven damage (creeper holes, Wither blasts, Warden, Pillager fire) —
 * because every such change goes through Level.setBlock.
 */
public final class WorldSnapshot {

    private record Entry(BlockState state, CompoundTag nbt) {}

    private final Map<BlockPos, Entry> changes = new HashMap<>();
    private boolean restoring;

    /** Snapshot is paused while we replay it — otherwise restore() would re-record its own setBlocks. */
    public boolean isRestoring() {
        return restoring;
    }

    public void recordIfFirst(ServerLevel level, BlockPos pos, BlockState oldState) {
        if (restoring) return;
        if (changes.containsKey(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(level.registryAccess()) : null;
        changes.put(pos, new Entry(oldState, nbt));
    }

    public int recordedCount() {
        return changes.size();
    }

    public RestoreResult restore(ServerLevel level) {
        int count = changes.size();
        if (count == 0) return new RestoreResult(0);
        restoring = true;
        try {
            for (Map.Entry<BlockPos, Entry> e : changes.entrySet()) {
                BlockPos pos = e.getKey();
                Entry entry = e.getValue();
                level.setBlock(pos, entry.state(), Block.UPDATE_ALL);
                if (entry.nbt() != null) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        be.loadWithComponents(entry.nbt(), level.registryAccess());
                        be.setChanged();
                    }
                }
            }
        } finally {
            restoring = false;
            changes.clear();
        }
        RegionsMOBA.LOGGER.info("Map repair: restored {} block(s) in {}", count, level.dimension().location());
        return new RestoreResult(count);
    }

    public void clear() {
        changes.clear();
    }

    public record RestoreResult(int blocksRestored) {}
}
