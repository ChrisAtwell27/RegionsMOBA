package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Mountain Miner — double-ore passive, Gold Rush, and Blast Furnace drops.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   Double-Ore Passive — every ore mined has a 67% chance to yield two.
 *     Affected ores: Iron, Gold, Coal, Diamond.
 *   Gold Rush — right-click the gold nugget. Lasts 10 seconds, 60-second
 *     cooldown. Ore drops become 100% double with a 67% chance of triple, each
 *     ore has a 20% chance of 1 bonus coal, and Mountain ore deposits ignore the
 *     Cold Season cooldown increase.
 *   Blast Furnace Drops — the first ore mined drops a Blast Furnace, and every
 *     16 ores *received* (not mined) drops another.
 *
 * Bonus drops are popped at the broken block on top of whatever vanilla already
 * dropped, so Fortune and Silk Touch interactions stay untouched.
 */
public final class MinerAbility {

    public static final int GOLD_RUSH_DURATION_TICKS = 10 * 20;
    public static final int GOLD_RUSH_COOLDOWN_SECONDS = 60;

    public static final float DOUBLE_CHANCE = 0.67f;
    public static final float RUSH_TRIPLE_CHANCE = 0.67f;
    public static final float RUSH_BONUS_COAL_CHANCE = 0.20f;

    public static final int ORES_PER_BLAST_FURNACE = 16;

    private static final String COOLDOWN_ID = "miner:gold_rush";

    /** Ore blocks the passive applies to, mapped to the item a bonus drop yields. */
    private static final Map<Block, Item> ORE_DROPS = new HashMap<>();

    static {
        ORE_DROPS.put(Blocks.IRON_ORE, Items.RAW_IRON);
        ORE_DROPS.put(Blocks.DEEPSLATE_IRON_ORE, Items.RAW_IRON);
        ORE_DROPS.put(Blocks.GOLD_ORE, Items.RAW_GOLD);
        ORE_DROPS.put(Blocks.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD);
        ORE_DROPS.put(Blocks.NETHER_GOLD_ORE, Items.GOLD_NUGGET);
        ORE_DROPS.put(Blocks.COAL_ORE, Items.COAL);
        ORE_DROPS.put(Blocks.DEEPSLATE_COAL_ORE, Items.COAL);
        ORE_DROPS.put(Blocks.DIAMOND_ORE, Items.DIAMOND);
        ORE_DROPS.put(Blocks.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND);
    }

    /** Server tick at which each Miner's Gold Rush ends. */
    private static final Map<UUID, Long> RUSH_UNTIL = new HashMap<>();
    /** Running count of ore items received, driving the Blast Furnace milestones. */
    private static final Map<UUID, Integer> ORES_RECEIVED = new HashMap<>();

    private static final Random RNG = new Random();

    private MinerAbility() {}

    /** Read by DepositTracker to waive the Cold Season regen penalty. */
    public static boolean isGoldRushActive(UUID player) {
        return RUSH_UNTIL.containsKey(player);
    }

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.GOLD_RUSH)) return false;
        if (isGoldRushActive(player.getUUID())) {
            tell(player, "Gold Rush already active.", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(player, COOLDOWN_ID)) {
            tell(player, "Gold Rush on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        MinecraftServer server = player.level().getServer();
        long now = server != null ? server.getTickCount() : 0L;

        RUSH_UNTIL.put(player.getUUID(), now + GOLD_RUSH_DURATION_TICKS);
        Cooldowns.get().set(player, COOLDOWN_ID, GOLD_RUSH_COOLDOWN_SECONDS);
        tell(player, "Gold Rush! 10s of doubled ore.", ChatFormatting.GOLD);
        return true;
    }

    public static void tick(MinecraftServer server, long globalTick) {
        if (RUSH_UNTIL.isEmpty() || server == null) return;
        RUSH_UNTIL.entrySet().removeIf(entry -> {
            if (globalTick < entry.getValue()) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) tell(player, "Gold Rush ended.", ChatFormatting.GRAY);
            return true;
        });
    }

    /**
     * Rolls the Miner's bonus drops for a freshly broken block. Called from the
     * block-break hook after vanilla has already dropped its own loot.
     */
    public static void onBlockBroken(ServerLevel level, ServerPlayer miner, BlockPos pos, BlockState state) {
        Item drop = ORE_DROPS.get(state.getBlock());
        if (drop == null) return;

        boolean rush = isGoldRushActive(miner.getUUID());
        int bonus = 0;
        if (rush) {
            bonus = 1; // 100% double while Gold Rush is up
            if (RNG.nextFloat() < RUSH_TRIPLE_CHANCE) bonus = 2;
            if (RNG.nextFloat() < RUSH_BONUS_COAL_CHANCE) {
                Block.popResource(level, pos, new ItemStack(Items.COAL));
            }
        } else if (RNG.nextFloat() < DOUBLE_CHANCE) {
            bonus = 1;
        }

        if (bonus > 0) Block.popResource(level, pos, new ItemStack(drop, bonus));

        // "Ores received" counts everything the Miner walks away with — the
        // vanilla drop plus whatever the passive added.
        awardBlastFurnaces(level, miner, pos, 1 + bonus);
    }

    /**
     * Blast Furnace milestones. The first ore ever received drops one, and every
     * later crossing of a 16-ore boundary drops another.
     */
    private static void awardBlastFurnaces(ServerLevel level, ServerPlayer miner, BlockPos pos, int received) {
        UUID id = miner.getUUID();
        int before = ORES_RECEIVED.getOrDefault(id, 0);
        int after = before + received;
        ORES_RECEIVED.put(id, after);

        int furnaces = 0;
        if (before == 0) furnaces++; // first ore mined
        furnaces += after / ORES_PER_BLAST_FURNACE - before / ORES_PER_BLAST_FURNACE;
        if (furnaces <= 0) return;

        Block.popResource(level, pos, new ItemStack(Items.BLAST_FURNACE, furnaces));
        tell(miner, "Blast Furnace x" + furnaces + " (" + after + " ores received)", ChatFormatting.YELLOW);
    }

    public static void clearForPlayer(UUID player) {
        RUSH_UNTIL.remove(player);
    }

    public static void clearAll() {
        RUSH_UNTIL.clear();
        ORES_RECEIVED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
