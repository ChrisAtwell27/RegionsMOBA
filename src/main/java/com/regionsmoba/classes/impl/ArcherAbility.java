package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.ItemTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Plains Archer — Arrow of Infinity.
 *
 * Per docs/src-md/classes/plains-classes.md: "Never runs out of arrows while the
 * Arrow of Infinity is in inventory, regardless of which bow is held."
 *
 * Implemented as a per-tick top-up rather than the Infinity enchantment, because
 * Infinity only applies to the bow carrying it and the doc explicitly covers any
 * bow. Vanilla's ammo search picks the first matching arrow stack, and the named
 * Arrow of Infinity is a plain {@code Items.ARROW} underneath, so it is that
 * stack that gets consumed — refilling it each tick makes the quiver bottomless.
 *
 * Dropping the Arrow of Infinity stops the refill, which is exactly the doc's
 * "while ... in inventory" condition.
 *
 * The +1 bow damage passive lives in {@link com.regionsmoba.classes.BonusDamageHook},
 * and the Punch I bow is applied through the ClassKits enchantment table. Rain of
 * Arrows and Poison Shot are not implemented yet.
 */
public final class ArcherAbility {

    /** Refill target. Any value above 1 works; a full stack keeps the HUD steady. */
    public static final int QUIVER_SIZE = 64;

    private ArcherAbility() {}

    public static void tick(ServerPlayer archer) {
        Inventory inventory = archer.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!ItemTags.hasName(stack, ClassKits.Names.ARROW_OF_INFINITY)) continue;
            if (stack.getCount() < QUIVER_SIZE) stack.setCount(QUIVER_SIZE);
            return;
        }
    }

    public static void clearAll() {}
}
