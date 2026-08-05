package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Plains Farmer — Feast (team food refill) + Famine (enemy hunger drain).
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Feast — right-click golden carrot. 30s cd. Refills hunger to 20 + 4 saturation
 *           on Farmer + all teammates within 13 blocks; cleanses Hunger.
 *   Famine — right-click dead bush. 90s cd. Hunger XX (amplifier 19) for 30s on
 *            every enemy within 13 blocks. PVP-gated.
 *
 * Harvest passive (instant crop regrow + chance drops) and Food Synergy
 * (extra heal on eat) are deferred — they need block-break / eat hooks
 * not yet wired.
 */
public final class FarmerAbility {

    public static final double RADIUS = 13.0;
    public static final int FEAST_COOLDOWN_SECONDS = 30;
    public static final int FAMINE_COOLDOWN_SECONDS = 90;
    public static final int FAMINE_DURATION_TICKS = 30 * 20;
    public static final int FAMINE_AMPLIFIER = 19;

    private static final Holder<MobEffect> HUNGER = MobEffects.HUNGER;

    private FarmerAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (ItemTags.hasName(stack, ClassKits.Names.FEAST)) {
            return castFeast(player);
        }
        if (ItemTags.hasName(stack, ClassKits.Names.FAMINE)) {
            return castFamine(player);
        }
        return false;
    }

    private static boolean castFeast(ServerPlayer farmer) {
        if (!Cooldowns.get().ready(farmer, "farmer:feast")) {
            tell(farmer, "Feast on cooldown ("
                    + Cooldowns.get().remainingSeconds(farmer, "farmer:feast") + "s)", ChatFormatting.GRAY);
            return true;
        }
        MatchPlayerState selfState = TeamAssignments.get().state(farmer.getUUID());
        if (selfState == null) return true;

        AABB box = new AABB(
                farmer.getX() - RADIUS, farmer.getY() - RADIUS, farmer.getZ() - RADIUS,
                farmer.getX() + RADIUS, farmer.getY() + RADIUS, farmer.getZ() + RADIUS);
        List<Player> nearby = farmer.level().getEntitiesOfClass(Player.class, box, p -> {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team == selfState.team;
        });
        if (!nearby.contains(farmer)) nearby.add(farmer);
        for (Player p : nearby) {
            FoodData food = p.getFoodData();
            food.setFoodLevel(20);
            food.setSaturation(food.getSaturationLevel() + 4.0f);
            p.removeEffect(HUNGER);
        }
        Cooldowns.get().set(farmer, "farmer:feast", FEAST_COOLDOWN_SECONDS);
        tell(farmer, "Feast — refilled " + nearby.size() + " teammate(s)", ChatFormatting.GOLD);
        return true;
    }

    private static boolean castFamine(ServerPlayer farmer) {
        if (!Cooldowns.get().ready(farmer, "farmer:famine")) {
            tell(farmer, "Famine on cooldown ("
                    + Cooldowns.get().remainingSeconds(farmer, "farmer:famine") + "s)", ChatFormatting.GRAY);
            return true;
        }
        if (!PvpManager.isPvpAllowed()) {
            tell(farmer, "Famine requires combat phase.", ChatFormatting.YELLOW);
            return true;
        }
        MatchPlayerState selfState = TeamAssignments.get().state(farmer.getUUID());
        if (selfState == null) return true;

        AABB box = new AABB(
                farmer.getX() - RADIUS, farmer.getY() - RADIUS, farmer.getZ() - RADIUS,
                farmer.getX() + RADIUS, farmer.getY() + RADIUS, farmer.getZ() + RADIUS);
        List<Player> nearby = farmer.level().getEntitiesOfClass(Player.class, box, p -> {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team != null && s.team != selfState.team;
        });
        for (Player enemy : nearby) {
            enemy.addEffect(new MobEffectInstance(
                    HUNGER, FAMINE_DURATION_TICKS, FAMINE_AMPLIFIER, true, false, true));
        }
        Cooldowns.get().set(farmer, "farmer:famine", FAMINE_COOLDOWN_SECONDS);
        tell(farmer, "Famine — drained " + nearby.size() + " enemy(ies)", ChatFormatting.DARK_GREEN);
        return true;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
