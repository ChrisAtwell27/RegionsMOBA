package com.regionsmoba.classes;

import com.regionsmoba.classes.impl.BardAbility;
import com.regionsmoba.classes.impl.ArcherAbility;
import com.regionsmoba.classes.impl.BerserkerAbility;
import com.regionsmoba.classes.impl.EnchanterAbility;
import com.regionsmoba.classes.impl.BloodmageAbility;
import com.regionsmoba.classes.impl.BuilderCache;
import com.regionsmoba.classes.impl.DefenderAlertItem;
import com.regionsmoba.classes.impl.LumberjackAbility;
import com.regionsmoba.classes.impl.MinerAbility;
import com.regionsmoba.classes.impl.NeptuneAbility;
import com.regionsmoba.classes.impl.SpyAbility;
import com.regionsmoba.classes.impl.TinkererAbility;
import com.regionsmoba.classes.impl.WarriorAbility;
import com.regionsmoba.classes.impl.WizardAbility;
import com.regionsmoba.team.BiomeClass;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Grants a class kit (starting items, armor) to a player.
 *
 * Called on class pick ({@link com.regionsmoba.lobby.LobbyFlow}), on respawn
 * ({@link com.regionsmoba.death.DeathHandler}) and from the debug command. Each
 * call is a full reset: inventory wiped, cooldowns and transient ability state
 * cleared, then the class kit re-issued from {@link ClassKits}.
 *
 * Kit stacks in {@link ClassKits} are shared templates, so every stack is
 * copied before it reaches the player's inventory. Armor pieces are routed to
 * their equipment slot rather than dropped in the hotbar.
 */
public final class KitGrant {

    private KitGrant() {}

    /**
     * Swaps a player's class in place, mid-match.
     *
     * Unlike {@link #grant}, the inventory is NOT wiped — only the previous
     * class's <em>named</em> kit items are removed. Unnamed kit items (generic
     * tools, plain armor) are left alone: they are fungible, and a spare wooden
     * pickaxe is not a balance problem. Every ability item carries a custom
     * name, so stripping those is enough to stop a player accumulating several
     * classes' abilities at once.
     *
     * Wiping the inventory here instead would delete everything the player had
     * earned, and nobody would ever use the feature.
     */
    public static void switchClass(ServerPlayer player, BiomeClass from, BiomeClass to) {
        if (from != null) stripNamedKitItems(player, from);
        resetAbilityState(player);
        DamageModifiers.apply(player, to);
        BerserkerAbility.applyStack(player, to);
        giveKit(player, to);
    }

    /** Removes every inventory or armor stack whose custom name belongs to this kit. */
    private static void stripNamedKitItems(ServerPlayer player, BiomeClass biomeClass) {
        List<String> names = new ArrayList<>();
        for (ItemStack template : ClassKits.kit(biomeClass)) {
            Component name = template.get(DataComponents.CUSTOM_NAME);
            if (name != null) names.add(name.getString());
        }
        if (names.isEmpty()) return;

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (matchesAnyName(inv.getItem(i), names)) inv.setItem(i, ItemStack.EMPTY);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!slot.isArmor()) continue;
            if (matchesAnyName(player.getItemBySlot(slot), names)) {
                player.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    private static boolean matchesAnyName(ItemStack stack, List<String> names) {
        if (stack.isEmpty()) return false;
        for (String name : names) {
            if (ItemTags.hasName(stack, name)) return true;
        }
        return false;
    }

    public static void grant(ServerPlayer player, BiomeClass biomeClass) {
        player.getInventory().clearContent();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.isArmor()) player.setItemSlot(slot, ItemStack.EMPTY);
        }

        resetAbilityState(player);
        DamageModifiers.apply(player, biomeClass);
        // Berserker hearts are the exception: the banked pool survives class
        // changes and deaths, so applyStack re-applies it (or strips the max-HP
        // modifier when the player is no longer a Berserker) rather than resetting.
        BerserkerAbility.applyStack(player, biomeClass);
        giveKit(player, biomeClass);
    }

    /**
     * Drops cooldowns and every in-flight ability effect. Shared by {@link #grant}
     * and {@link #switchClass} — a Warrior's running Frenzy must not survive onto
     * a Wizard.
     */
    private static void resetAbilityState(ServerPlayer player) {
        Cooldowns.get().clearForPlayer(player.getUUID());
        WarriorAbility.clearForPlayer(player.getUUID());
        MinerAbility.clearForPlayer(player.getUUID());
        BardAbility.clearForPlayer(player.getUUID());
        TinkererAbility.clearForPlayer(player.getUUID());
        WizardAbility.clearForPlayer(player.getUUID());
        // Respawn or class change replaces the ServerPlayer, so drop the vanish
        // bookkeeping rather than leaving a masked-equipment entry behind.
        SpyAbility.clearForPlayer(player.getUUID());
        NeptuneAbility.clearForPlayer(player.getUUID());
        DefenderAlertItem.clearForPlayer(player.getUUID());
        BuilderCache.clearForPlayer(player.getUUID());
        ArcherAbility.clearForPlayer(player.getUUID());
        EnchanterAbility.clearForPlayer(player.getUUID());
        // A Bloodmage curse is a debuff on the *victim*, so this clears one the
        // player is carrying, not one they cast.
        BloodmageAbility.clearCurse(player);
        LumberjackAbility.clearForPlayer(player.getUUID());
    }

    /** Issues a class kit. Templates in {@link ClassKits} are shared, so each is copied. */
    private static void giveKit(ServerPlayer player, BiomeClass biomeClass) {
        List<ItemStack> kit = ClassKits.kit(biomeClass);
        for (ItemStack template : kit) {
            ItemStack copy = template.copy();
            ClassKits.applyEnchantments(copy, biomeClass, player.registryAccess());
            EquipmentSlot slot = player.getEquipmentSlotForItem(copy);
            if (slot.isArmor() && player.getItemBySlot(slot).isEmpty()) {
                player.setItemSlot(slot, copy);
            } else {
                player.getInventory().add(copy);
            }
        }
        player.inventoryMenu.broadcastChanges();
    }
}
