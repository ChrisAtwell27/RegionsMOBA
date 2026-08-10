package com.regionsmoba.classes;

import com.regionsmoba.team.BiomeClass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Per-class melee damage modifiers applied as transient AttributeModifiers on
 * the player's ATTACK_DAMAGE attribute. Applied on kit grant, cleared on class
 * change / death-handler respawn (which calls KitGrant.grant).
 *
 * Covered for slice 11:
 *   Warrior — flat +1 melee damage (passive). Frenzy's additional +1 lands on
 *             top of this when active (slice 9 already drives it via Speed
 *             buff; the +1 portion is a polish pass since it requires the
 *             modifier to be added/removed during the buff).
 *   Vampire — 1.25× melee multiplier (ADD_MULTIPLIED_TOTAL with 0.25).
 *
 * Lumberjack +0.5 axe-only and Berserker armor-difference are NOT here —
 * they need conditional / target-aware logic (axe held check, victim armor
 * lookup) that requires a different mechanism (Berserker handled inline,
 * Lumberjack deferred).
 */
public final class DamageModifiers {

    private static final ResourceLocation WARRIOR_BONUS = ResourceLocation.parse("regionsmoba:warrior_bonus");
    private static final ResourceLocation VAMPIRE_MELEE = ResourceLocation.parse("regionsmoba:vampire_melee");

    private DamageModifiers() {}

    /** Apply the static class melee modifiers for the given class; clears any prior ones. */
    public static void apply(ServerPlayer player, BiomeClass bc) {
        clear(player);
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null || bc == null) return;
        switch (bc) {
            case MOUNTAIN_WARRIOR -> attr.addOrUpdateTransientModifier(
                    new AttributeModifier(WARRIOR_BONUS, 1.0, AttributeModifier.Operation.ADD_VALUE));
            case NETHER_VAMPIRE -> attr.addOrUpdateTransientModifier(
                    new AttributeModifier(VAMPIRE_MELEE, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            default -> {}
        }
    }

    public static void clear(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null) return;
        attr.removeModifier(WARRIOR_BONUS);
        attr.removeModifier(VAMPIRE_MELEE);
    }
}
