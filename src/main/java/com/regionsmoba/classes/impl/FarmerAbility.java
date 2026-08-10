package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Random;

/**
 * Plains Farmer — Feast (team food refill) + Famine (enemy hunger drain).
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Feast — right-click golden carrot. 30s cd. Refills hunger to 20 + 4 saturation
 *           on Farmer + all teammates within 13 blocks; cleanses Hunger.
 *   Famine — right-click dead bush. 90s cd. Hunger XX (amplifier 19) for 30s on
 *            every enemy within 13 blocks. PVP-gated.
 *
 * Harvest passive: crops the Farmer breaks regrow instantly at full age, which
 * is what lets the Farmer bypass the Cold Season crop-freeze. Breaking crops
 * also rolls a 1/100 bonus item and a 1/400 apple, and tall grass rolls its own
 * small drop table.
 *
 * Food Synergy (extra heal on eat) is still deferred — it needs an eat hook that
 * is not wired yet.
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

    // ---- Harvest passive ----

    /** 1/100 per crop break. */
    public static final int RARE_DROP_DENOMINATOR = 100;
    /** 1/400 per crop break. */
    public static final int APPLE_DENOMINATOR = 400;

    private static final List<Item> RARE_CROP_DROPS = List.of(
            Items.RAW_GOLD, Items.RAW_IRON, Items.COAL, Items.BOOK,
            Items.EXPERIENCE_BOTTLE, Items.GOLD_NUGGET, Items.IRON_HOE);

    private static final List<Item> GRASS_DROPS = List.of(
            Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS);

    private static final Random RNG = new Random();

    /**
     * Harvest passive. Called from the block-break hook after vanilla has taken
     * its own drops.
     *
     * The instant regrow is the important half: it puts the crop straight back
     * at full age, which is what lets a Farmer keep harvesting through the Cold
     * Season crop-freeze that {@link com.regionsmoba.mixin.CropGrowthMixin}
     * otherwise enforces.
     */
    public static void onBlockBroken(ServerLevel level, ServerPlayer farmer, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            level.setBlock(pos, crop.getStateForAge(crop.getMaxAge()), Block.UPDATE_ALL);
            if (RNG.nextInt(RARE_DROP_DENOMINATOR) == 0) {
                Item drop = RARE_CROP_DROPS.get(RNG.nextInt(RARE_CROP_DROPS.size()));
                Block.popResource(level, pos, new ItemStack(drop));
            }
            if (RNG.nextInt(APPLE_DENOMINATOR) == 0) {
                Block.popResource(level, pos, new ItemStack(Items.APPLE));
            }
            return;
        }
        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) {
            if (RNG.nextInt(4) != 0) return;
            Item drop = GRASS_DROPS.get(RNG.nextInt(GRASS_DROPS.size()));
            Block.popResource(level, pos, new ItemStack(drop));
        }
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
