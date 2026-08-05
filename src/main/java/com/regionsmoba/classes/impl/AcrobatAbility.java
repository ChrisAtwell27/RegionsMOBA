package com.regionsmoba.classes.impl;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.food.FoodData;

/**
 * Mountain Acrobat — fall immunity + hunger floor.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   "Takes no fall damage at any height."
 *   "Hunger cannot drop below 3.5 bars (7 half-units)."
 *
 * Double jump is a polish pass — needs client packet handling for jump-while-airborne.
 */
public final class AcrobatAbility {

    public static final int HUNGER_FLOOR_HALF_UNITS = 7;

    private AcrobatAbility() {}

    public static boolean cancelsFallDamage(DamageSource source) {
        return source.is(DamageTypes.FALL);
    }

    /** Pin food level to 7+ each tick. Hunger damage becomes impossible; sprint always available. */
    public static void tick(ServerPlayer player) {
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() < HUNGER_FLOOR_HALF_UNITS) {
            food.setFoodLevel(HUNGER_FLOOR_HALF_UNITS);
        }
    }
}
