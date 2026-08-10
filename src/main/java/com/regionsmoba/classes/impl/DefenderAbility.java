package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.lifeline.LifelineState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * Ocean Defender — Conduit Regen + Conduit HP Scaling.
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Conduit Regen — Regen I while within 50 blocks of the friendly Conduit.
 *   Conduit HP Scaling — bonus max HP = floor((200 - conduitHP) / 20), capped
 *                        at +10 hearts (+20 HP), only while within 50 blocks.
 *
 * Implementation: per-second tick. If in range, refresh Regen I (40 ticks) and
 * apply/update a transient MAX_HEALTH attribute modifier. Otherwise clear the
 * modifier so the Defender drops back to base 20 HP.
 *
 * Guardian's Warp (lime dye teleport) and Alert Item (prismarine shard place
 * + alert) are deferred — they need additional plumbing (safe point near
 * conduit + tracked alert blocks).
 */
public final class DefenderAbility {

    public static final double RANGE_BLOCKS = 50.0;
    public static final int REGEN_DURATION_TICKS = 40;
    public static final double MAX_BONUS_HP = 20.0;

    private static final ResourceLocation HP_MOD_ID = ResourceLocation.parse("regionsmoba:defender_conduit_hp");
    private static final Holder<MobEffect> REGEN = MobEffects.REGENERATION;

    private DefenderAbility() {}

    public static void tick(ServerPlayer player, long globalTick) {
        if (globalTick % 20 != 0) return; // every second
        BlockPosData conduit = RegionsConfig.get().conduit;
        if (conduit == null) {
            clearBonusHp(player);
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(conduit.dimensionOrDefault())) {
            clearBonusHp(player);
            return;
        }
        double dx = player.getX() - (conduit.x() + 0.5);
        double dy = player.getY() - (conduit.y() + 0.5);
        double dz = player.getZ() - (conduit.z() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > RANGE_BLOCKS * RANGE_BLOCKS) {
            clearBonusHp(player);
            return;
        }
        // In range — refresh Regen I and apply HP scaling.
        player.addEffect(new MobEffectInstance(REGEN, REGEN_DURATION_TICKS, 0, true, false, true));
        int currentConduitHp = LifelineState.get().conduitHp;
        double bonus = Math.min(MAX_BONUS_HP, Math.max(0, (LifelineState.CONDUIT_MAX_HP - currentConduitHp) / 20 * 2.0));
        applyBonusHp(player, bonus);
    }

    private static void applyBonusHp(ServerPlayer player, double amount) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        attr.removeModifier(HP_MOD_ID);
        if (amount > 0) {
            attr.addOrUpdateTransientModifier(
                    new AttributeModifier(HP_MOD_ID, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    public static void clearBonusHp(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) attr.removeModifier(HP_MOD_ID);
    }

    // ---- Guardian's Warp ----

    public static final int WARP_COOLDOWN_SECONDS = 20;

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.GUARDIANS_WARP)) return false;
        if (!Cooldowns.get().ready(player, "defender:warp")) {
            tell(player, "Guardian's Warp on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, "defender:warp") + "s)", ChatFormatting.GRAY);
            return true;
        }
        BlockPosData conduit = RegionsConfig.get().conduit;
        if (conduit == null) {
            tell(player, "No conduit registered.", ChatFormatting.RED);
            return true;
        }
        ServerLevel level = player.level().getServer() != null
                ? player.level().getServer().getLevel(conduit.dimensionKey()) : null;
        if (level == null) {
            tell(player, "Conduit dimension not loaded.", ChatFormatting.RED);
            return true;
        }
        // Safe point = 3 blocks above the conduit (avoids landing in water-locked geometry).
        double x = conduit.x() + 0.5;
        double y = conduit.y() + 3;
        double z = conduit.z() + 0.5;
        player.teleportTo(level, x, y, z, Set.<RelativeMovement>of(), player.getYRot(), player.getXRot());
        Cooldowns.get().set(player, "defender:warp", WARP_COOLDOWN_SECONDS);
        tell(player, "Guardian's Warp — to the conduit.", ChatFormatting.AQUA);
        return true;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
