package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.Cooldowns;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mountain Acrobat — fall immunity + hunger floor.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   "Takes no fall damage at any height."
 *   "Hunger cannot drop below 3.5 bars (7 half-units)."
 *
 *   "Double-tap jump in mid-air to leap upward. Reaches up to 6 blocks above
 *    the start point. 10-second cooldown."
 *
 * The trigger is a mid-air sneak double-tap rather than a jump double-tap: on
 * 1.21.1 the jump key is invisible to the server (the input packet is only sent
 * while riding a vehicle), whereas every sneak press arrives as a player-command
 * packet. {@link com.regionsmoba.mixin.PlayerSneakMixin} feeds those presses in.
 * A literal double-tap is required here — a single airborne sneak is far too
 * common an input to spend the leap on.
 */
public final class AcrobatAbility {

    public static final int HUNGER_FLOOR_HALF_UNITS = 7;

    public static final int DOUBLE_JUMP_COOLDOWN_SECONDS = 10;
    /** Upward velocity chosen to top out near 6 blocks above the launch point. */
    public static final double DOUBLE_JUMP_VELOCITY = 1.1;

    private static final String COOLDOWN_ID = "acrobat:double_jump";

    /** Two sneak presses inside this many ticks (½ second) count as a double-tap. */
    private static final int DOUBLE_TAP_WINDOW_TICKS = 10;

    /** Server tick of each Acrobat's last airborne sneak press. Cleared on launch. */
    private static final Map<UUID, Integer> LAST_AIRBORNE_SNEAK = new HashMap<>();

    private AcrobatAbility() {}

    /**
     * Called on every sneak-key press. Only the second airborne press inside the
     * double-tap window, with the cooldown up, launches; a grounded press is
     * ordinary sneaking and is left alone.
     */
    public static void onSneakPress(ServerPlayer player) {
        if (player.onGround() || player.isInWater() || player.isFallFlying()) {
            LAST_AIRBORNE_SNEAK.remove(player.getUUID());
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        int now = server.getTickCount();
        Integer previous = LAST_AIRBORNE_SNEAK.put(player.getUUID(), now);
        if (previous == null || now - previous > DOUBLE_TAP_WINDOW_TICKS) return;

        if (!Cooldowns.get().ready(player, COOLDOWN_ID)) return;
        LAST_AIRBORNE_SNEAK.remove(player.getUUID());

        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, DOUBLE_JUMP_VELOCITY, motion.z);
        player.hurtMarked = true;
        player.resetFallDistance();
        Cooldowns.get().set(player, COOLDOWN_ID, DOUBLE_JUMP_COOLDOWN_SECONDS);
        player.sendSystemMessage(Component.literal("Double jump!").withStyle(ChatFormatting.WHITE));
    }

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
