package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Random;

/**
 * Mountain Builder — Resource Drop.
 *
 * Per docs/src-md/classes/mountain-classes.md right-clicking the book opens a
 * chest of mixed materials with the listed maxes. Slice 9a MVP delivers the
 * roll directly into the player's inventory (overflow drops at feet) instead
 * of opening a custom chest UI — faster to implement, same gameplay value.
 *
 * Replication Cache (composter-block placement + replication) requires custom
 * block-entity tracking; that lands in a follow-up.
 */
public final class BuilderAbility {

    public static final int COOLDOWN_SECONDS = 90;

    private record Roll(Item item, int max) {}

    private static final List<Roll> ROLLS = List.of(
            new Roll(Items.SPRUCE_PLANKS, 70),
            new Roll(Items.COBBLESTONE, 60),
            new Roll(Items.STONE, 50),
            new Roll(Items.STONE_BRICKS, 40),
            new Roll(Items.WHITE_WOOL, 30),
            new Roll(Items.GLASS, 20),
            new Roll(Items.SPRUCE_STAIRS, 20),
            new Roll(Items.SPRUCE_FENCE, 10),
            new Roll(Items.IRON_BARS, 10),
            new Roll(Items.TORCH, 5));

    private static final Random RNG = new Random();

    private BuilderAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.RESOURCE_DROP)) return false;
        if (!Cooldowns.get().ready(player, "builder:drop")) {
            player.sendSystemMessage(Component.literal("Resource Drop on cooldown ("
                            + Cooldowns.get().remainingSeconds(player, "builder:drop") + "s)")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }
        Inventory inv = player.getInventory();
        for (Roll r : ROLLS) {
            int amount = 1 + RNG.nextInt(r.max);
            ItemStack drop = new ItemStack(r.item, amount);
            if (!inv.add(drop)) {
                player.drop(drop, false);
            }
        }
        Cooldowns.get().set(player, "builder:drop", COOLDOWN_SECONDS);
        player.sendSystemMessage(Component.literal("Resource Drop received — 90s cooldown.")
                .withStyle(ChatFormatting.GREEN));
        return true;
    }
}
