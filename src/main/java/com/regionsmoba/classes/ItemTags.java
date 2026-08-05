package com.regionsmoba.classes;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Builders for kit ItemStacks with the conventions used across all classes:
 *
 *   - Ability items have a custom name set via DataComponents.CUSTOM_NAME.
 *     We identify them at right/left-click time by exact-name match, so any
 *     vanilla item the player picks up that happens to be the same type won't
 *     trigger the ability.
 *
 *   - Kit items are otherwise vanilla — we don't currently mark them as
 *     "undroppable" or "undying"; that's a polish pass.
 */
public final class ItemTags {

    private ItemTags() {}

    public static ItemStack named(Item item, String name) {
        return named(item, name, ChatFormatting.AQUA);
    }

    public static ItemStack named(Item item, String name, ChatFormatting color) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
        return stack;
    }

    public static ItemStack named(Item item, int count, String name) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.AQUA));
        return stack;
    }

    public static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    public static ItemStack tool(Item item) {
        return new ItemStack(item);
    }

    /** Convenience: returns true if the stack's custom name matches exactly. */
    public static boolean hasName(ItemStack stack, String name) {
        if (stack == null || stack.isEmpty()) return false;
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        return custom != null && name.equals(custom.getString());
    }

    /** Apply a single enchantment level to a stack (used for Efficiency I tools, etc.). */
    public static ItemStack withEfficiencyI(ItemStack stack) {
        return stack; // Slice 9a stub: enchantment application requires registry lookup at runtime;
                     // kits ship with vanilla items for now. Polish pass.
    }

    @SuppressWarnings("unused")
    private static final Object KEEP_ENCHANTMENTS = Enchantments.class;
}
