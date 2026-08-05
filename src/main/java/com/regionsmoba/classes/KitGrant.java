package com.regionsmoba.classes;

import com.regionsmoba.classes.impl.BardAbility;
import com.regionsmoba.classes.impl.BerserkerAbility;
import com.regionsmoba.classes.impl.BloodmageAbility;
import com.regionsmoba.classes.impl.LumberjackAbility;
import com.regionsmoba.classes.impl.MinerAbility;
import com.regionsmoba.classes.impl.TinkererAbility;
import com.regionsmoba.classes.impl.WarriorAbility;
import com.regionsmoba.classes.impl.WizardAbility;
import com.regionsmoba.team.BiomeClass;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

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

    public static void grant(ServerPlayer player, BiomeClass biomeClass) {
        player.getInventory().clearContent();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (slot.isArmor()) player.setItemSlot(slot, ItemStack.EMPTY);
        }

        // A kit grant is also a state reset: cooldowns and any in-flight ability
        // effects belong to the previous life / previous class.
        Cooldowns.get().clearForPlayer(player.getUUID());
        WarriorAbility.clearForPlayer(player.getUUID());
        MinerAbility.clearForPlayer(player.getUUID());
        BardAbility.clearForPlayer(player.getUUID());
        TinkererAbility.clearForPlayer(player.getUUID());
        WizardAbility.clearForPlayer(player.getUUID());
        // A Bloodmage curse is a debuff on the *victim*, so this clears one the
        // player is carrying, not one they cast.
        BloodmageAbility.clearCurse(player);
        LumberjackAbility.clearForPlayer(player.getUUID());
        DamageModifiers.apply(player, biomeClass);
        // Berserker hearts are the exception: the banked pool survives class
        // changes and deaths, so applyStack re-applies it (or strips the max-HP
        // modifier when the player is no longer a Berserker) rather than resetting.
        BerserkerAbility.applyStack(player, biomeClass);

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
