package com.regionsmoba.classes;

import com.regionsmoba.team.BiomeClass;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Grants a class kit (starting items, armor) to a player.
 *
 * Currently a placeholder: the per-class kit definitions land alongside each
 * ability implementation. For now, any class pick clears the inventory and
 * notifies the player that the kit is pending.
 */
public final class KitGrant {

    private KitGrant() {}

    public static void grant(ServerPlayer player, BiomeClass biomeClass) {
        player.getInventory().clearContent();
        player.sendSystemMessage(Component.literal(
                        "Class kit for " + biomeClass.displayName() + " is not yet implemented.")
                .withStyle(ChatFormatting.GRAY));
    }
}
