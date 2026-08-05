package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.ItemTags;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Plains Scout — Grapple.
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Right-click the grapple to cast. The hook attaches to the first solid block
 *   it hits. Right-click again to reel in, launching the Scout toward the anchor.
 *   Launch strength scales with vertical distance.
 *   While the grapple is held in main hand, all fall damage is halved.
 *   Combat tag: 5s after a hit, grapple disabled.
 *
 * Slice 13 MVP: single right-click does both cast + reel in one shot — raycasts
 * forward up to 32 blocks, sets delta movement toward the anchor. Vertical
 * boost added when the anchor is above. Combat tag enforced via a Cooldown key.
 *
 * Fall damage halving is approximated by applying Slow Falling for 1s when the
 * grapple is the held item (handled in tick).
 */
public final class ScoutAbility {

    public static final double MAX_RANGE = 32.0;
    public static final double BASE_STRENGTH = 1.4;
    public static final double VERTICAL_BONUS = 0.06; // per block above

    public static final int COMBAT_TAG_SECONDS = 5;

    private ScoutAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.SCOUT_GRAPPLE)) return false;

        if (player.isOnFire() || player.hasEffect(MobEffects.SLOWNESS)) {
            tell(player, "Grapple unavailable while on fire or slowed.", ChatFormatting.RED);
            return true;
        }
        if (!com.regionsmoba.classes.Cooldowns.get().ready(player, "scout:combat_tag")) {
            tell(player, "Grapple unavailable — combat tag", ChatFormatting.GRAY);
            return true;
        }

        ServerLevel level = player.level();
        Vec3 from = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        Vec3 to = from.add(dir.scale(MAX_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            tell(player, "Grapple missed.", ChatFormatting.GRAY);
            return true;
        }
        Vec3 anchor = hit.getLocation();
        Vec3 toward = anchor.subtract(player.position()).normalize();
        double verticalDelta = anchor.y - player.getY();
        double strength = BASE_STRENGTH + Math.max(0, verticalDelta) * VERTICAL_BONUS;
        Vec3 push = toward.scale(strength).add(0, Math.max(0.4, verticalDelta * 0.05), 0);
        player.setDeltaMovement(push);
        player.hurtMarked = true;
        // Grant a brief Slow Falling so the launch doesn't double as fall damage.
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, true, false, true));
        tell(player, "Grapple!", ChatFormatting.AQUA);
        return true;
    }

    /** Called from AbilityHooks AFTER_DAMAGE when a Scout takes a hit — arms the combat tag. */
    public static void onScoutHit(ServerPlayer scout) {
        com.regionsmoba.classes.Cooldowns.get().set(scout, "scout:combat_tag", COMBAT_TAG_SECONDS);
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
