package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Plains Lumberjack — logging passive and Brute Force.
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Logging Passive — +0.5 hearts bonus damage with any axe (handled in
 *     {@link com.regionsmoba.classes.BonusDamageHook}), and an 80% chance of an
 *     extra log when mining a log block.
 *   Brute Force — right-click the brick block. For 15 seconds, each axe hit on
 *     an enemy deals bonus durability damage to their armor, scaled by axe tier
 *     (wood/gold 3, stone 6, iron 9, diamond/netherite 12). 45-second cooldown.
 *     It can be toggled on outside a PVP window, but armor damage only ticks
 *     when the axe lands on a PVP-legal target.
 */
public final class LumberjackAbility {

    public static final float EXTRA_LOG_CHANCE = 0.80f;

    public static final int BRUTE_FORCE_DURATION_TICKS = 15 * 20;
    public static final int BRUTE_FORCE_COOLDOWN_SECONDS = 45;

    private static final String COOLDOWN_ID = "lumberjack:brute_force";

    /** Server tick at which each Lumberjack's Brute Force ends. */
    private static final Map<UUID, Long> BRUTE_UNTIL = new HashMap<>();

    private static final Random RNG = new Random();

    private LumberjackAbility() {}

    public static boolean isBruteForceActive(UUID player) {
        return BRUTE_UNTIL.containsKey(player);
    }

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.BRUTE_FORCE)) return false;
        if (isBruteForceActive(player.getUUID())) {
            tell(player, "Brute Force already active.", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(player, COOLDOWN_ID)) {
            tell(player, "Brute Force on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        MinecraftServer server = player.level().getServer();
        long now = server != null ? server.getTickCount() : 0L;

        BRUTE_UNTIL.put(player.getUUID(), now + BRUTE_FORCE_DURATION_TICKS);
        Cooldowns.get().set(player, COOLDOWN_ID, BRUTE_FORCE_COOLDOWN_SECONDS);
        tell(player, "Brute Force — 15s of armor-shredding axe hits.", ChatFormatting.RED);
        return true;
    }

    public static void tick(MinecraftServer server, long globalTick) {
        if (BRUTE_UNTIL.isEmpty() || server == null) return;
        BRUTE_UNTIL.entrySet().removeIf(entry -> {
            if (globalTick < entry.getValue()) return false;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) tell(player, "Brute Force ended.", ChatFormatting.GRAY);
            return true;
        });
    }

    /** 80% chance of one extra log. Called from the block-break hook. */
    public static void onBlockBroken(ServerLevel level, ServerPlayer lumberjack, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS)) return;
        if (RNG.nextFloat() >= EXTRA_LOG_CHANCE) return;
        Block.popResource(level, pos, new ItemStack(state.getBlock().asItem()));
    }

    /**
     * Applies the Brute Force armor-durability penalty. Called from AFTER_DAMAGE
     * once the hit is known to be a Lumberjack melee swing.
     */
    public static void onAxeHit(ServerPlayer attacker, Player victim) {
        if (!isBruteForceActive(attacker.getUUID())) return;
        if (!(attacker.getMainHandItem().getItem() instanceof AxeItem)) return;
        if (!PvpManager.isPvpAllowedFor(attacker, victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        if (!(victim instanceof ServerPlayer victimPlayer)) return;

        int amount = armorDamageFor(attacker.getMainHandItem().getItem());
        if (amount <= 0) return;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (!slot.isArmor()) continue;
            ItemStack piece = victimPlayer.getItemBySlot(slot);
            if (piece.isEmpty() || !piece.isDamageableItem()) continue;
            piece.hurtAndBreak(amount, level, victimPlayer, item -> {});
        }
    }

    /** Bonus armor durability per hit, by axe tier. */
    private static int armorDamageFor(Item axe) {
        if (axe == Items.WOODEN_AXE || axe == Items.GOLDEN_AXE) return 3;
        if (axe == Items.STONE_AXE) return 6;
        if (axe == Items.IRON_AXE) return 9;
        if (axe == Items.DIAMOND_AXE || axe == Items.NETHERITE_AXE) return 12;
        return 0;
    }

    public static void clearForPlayer(UUID player) {
        BRUTE_UNTIL.remove(player);
    }

    public static void clearAll() {
        BRUTE_UNTIL.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
