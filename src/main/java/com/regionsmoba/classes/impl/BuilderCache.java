package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.BlockPosData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mountain Builder — Replication Cache.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   Place the composter to start replicating. Insert a block to set what it
 *   makes. Left-click views the contents, shift+left-click replaces the current
 *   item (deleting what has accumulated), right-click collects a stack, and
 *   shift+right-click collects everything. It produces 10 blocks every 3
 *   seconds up to 960 held, and breaking it costs a 45-second cooldown.
 *
 * DEVIATION: inserting a block type is bound to shift + right-click holding
 * that block, not left-click. Left-click on a block is the mining input — the
 * repo's swing hook cannot tell "left-click to insert" from "left-click to
 * break the cache", and stealing mining input on a placed block would make the
 * cache impossible to remove. View-contents is folded into the same
 * shift+right-click feedback message.
 */
public final class BuilderCache {

    public static final int PRODUCE_INTERVAL_TICKS = 60; // 3 seconds
    public static final int PRODUCE_AMOUNT = 10;
    public static final int MAX_HELD = 960; // 15 stacks
    public static final int BREAK_COOLDOWN_SECONDS = 45;

    private static final String REPLACE_COOLDOWN_ID = "builder:cache";

    public static final Block CACHE_BLOCK = Blocks.COMPOSTER;

    private static final class Cache {
        final UUID owner;
        final BlockPosData pos;
        Item replicating;
        int held;
        long nextProduceTick;

        Cache(UUID owner, BlockPosData pos, long startTick) {
            this.owner = owner;
            this.pos = pos;
            this.nextProduceTick = startTick + PRODUCE_INTERVAL_TICKS;
        }
    }

    /** One cache per Builder. */
    private static final Map<UUID, Cache> CACHES = new HashMap<>();

    private BuilderCache() {}

    public static boolean tryRightClick(ServerPlayer builder, ItemStack stack, BlockHitResult hit) {
        if (hit == null) return false;
        Cache cache = CACHES.get(builder.getUUID());

        if (cache != null && cache.pos.toBlockPos().equals(hit.getBlockPos())) {
            return builder.isShiftKeyDown()
                    ? insertOrCollectAll(builder, cache, stack)
                    : collectStack(builder, cache);
        }

        if (!ItemTags.hasName(stack, ClassKits.Names.REPLICATION_CACHE)) return false;
        return place(builder, hit);
    }

    private static boolean place(ServerPlayer builder, BlockHitResult hit) {
        if (CACHES.containsKey(builder.getUUID())) {
            tell(builder, "Your Replication Cache is already placed.", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(builder, REPLACE_COOLDOWN_ID)) {
            tell(builder, "Replication Cache on cooldown ("
                    + Cooldowns.get().remainingSeconds(builder, REPLACE_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        ServerLevel level = builder.serverLevel();
        BlockPos target = hit.getBlockPos().relative(hit.getDirection());
        if (!level.getBlockState(target).canBeReplaced()) {
            tell(builder, "No room to place the Replication Cache there.", ChatFormatting.GRAY);
            return true;
        }
        MinecraftServer server = level.getServer();
        long now = server != null ? server.getTickCount() : 0L;

        level.setBlock(target, CACHE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        CACHES.put(builder.getUUID(), new Cache(builder.getUUID(), BlockPosData.of(level, target), now));
        // The named composter is kept, like the Bard's Buffbox — breaking the
        // placed block drops a plain composter, so consuming it would destroy
        // the Builder's only cache.
        tell(builder, "Replication Cache placed — shift+right-click with a block to load it.",
                ChatFormatting.GREEN);
        return true;
    }

    /** Shift + right-click: load a new block type, or collect everything held. */
    private static boolean insertOrCollectAll(ServerPlayer builder, Cache cache, ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem && isReplicable(blockItem.getBlock())) {
            if (cache.replicating != blockItem.getBlock().asItem() && cache.held > 0) {
                tell(builder, "Cache reloaded — " + cache.held + " stored block(s) discarded.",
                        ChatFormatting.YELLOW);
            }
            cache.replicating = blockItem.getBlock().asItem();
            cache.held = 0;
            tell(builder, "Replicating " + stack.getHoverName().getString() + ".", ChatFormatting.GREEN);
            return true;
        }
        if (cache.held <= 0) {
            tell(builder, "Cache is empty" + describeLoad(cache) + ".", ChatFormatting.GRAY);
            return true;
        }
        int collected = giveUpTo(builder, cache, cache.held);
        tell(builder, "Collected " + collected + " block(s).", ChatFormatting.GREEN);
        return true;
    }

    /** Plain right-click: collect a single stack. */
    private static boolean collectStack(ServerPlayer builder, Cache cache) {
        if (cache.held <= 0) {
            tell(builder, "Cache is empty" + describeLoad(cache) + ".", ChatFormatting.GRAY);
            return true;
        }
        int collected = giveUpTo(builder, cache, Math.min(64, cache.held));
        tell(builder, "Collected " + collected + " block(s) — " + cache.held + " left.",
                ChatFormatting.GREEN);
        return true;
    }

    private static int giveUpTo(ServerPlayer builder, Cache cache, int amount) {
        if (cache.replicating == null) return 0;
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(64, remaining);
            ItemStack give = new ItemStack(cache.replicating, batch);
            if (!builder.getInventory().add(give)) builder.drop(give, false);
            remaining -= batch;
        }
        cache.held -= amount;
        return amount;
    }

    private static String describeLoad(Cache cache) {
        return cache.replicating == null ? " and unloaded" : "";
    }

    public static void tick(MinecraftServer server, long globalTick) {
        if (CACHES.isEmpty() || server == null) return;
        for (Cache cache : CACHES.values()) {
            if (cache.replicating == null || cache.held >= MAX_HELD) continue;
            if (globalTick < cache.nextProduceTick) continue;
            cache.held = Math.min(MAX_HELD, cache.held + PRODUCE_AMOUNT);
            cache.nextProduceTick = globalTick + PRODUCE_INTERVAL_TICKS;
        }
    }

    /** De-registers a broken cache and starts the owner's re-place cooldown. */
    public static void onBroken(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        var it = CACHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Cache> entry = it.next();
            Cache cache = entry.getValue();
            if (!cache.pos.toBlockPos().equals(pos)) continue;
            if (!cache.pos.dimensionOrDefault().equals(dimension)) continue;
            it.remove();
            ServerPlayer owner = level.getServer() == null
                    ? null : level.getServer().getPlayerList().getPlayer(cache.owner);
            if (owner != null) {
                Cooldowns.get().set(owner, REPLACE_COOLDOWN_ID, BREAK_COOLDOWN_SECONDS);
                tell(owner, "Replication Cache broken — " + BREAK_COOLDOWN_SECONDS + "s cooldown.",
                        breaker.getUUID().equals(cache.owner) ? ChatFormatting.GRAY : ChatFormatting.RED);
            }
            return;
        }
    }

    /** Doc list: glass, planks, stone, stone bricks, wool, cobble, dirt, grass, concrete, terracotta, deepslate. */
    private static boolean isReplicable(Block block) {
        return block.defaultBlockState().is(BlockTags.PLANKS)
                || block.defaultBlockState().is(BlockTags.WOOL)
                || block.defaultBlockState().is(BlockTags.TERRACOTTA)
                || block == Blocks.GLASS
                || block.defaultBlockState().is(BlockTags.IMPERMEABLE) // stained glass
                || block == Blocks.STONE
                || block == Blocks.STONE_BRICKS
                || block == Blocks.COBBLESTONE
                || block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.DEEPSLATE
                || block.getDescriptionId().contains("concrete");
    }

    public static void clearForPlayer(UUID player) {
        CACHES.remove(player);
    }

    public static void clearAll() {
        CACHES.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
