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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Nether Vampire — HP-steal-on-melee + Blood Sense reveal.
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Passive: 15% HP-steal chance during day, 30% at night.
 *   Blood Sense (right-click music disc) — reveals enemies in 10x8 radius for 3s.
 *
 * The 1.25x melee multiplier and Insidious Dispatch (teleport behind enemy)
 * require damage-modifier and target-detection plumbing that lands in a follow-up.
 */
public final class VampireAbility {

    public static final int BLOOD_SENSE_DURATION_TICKS = 3 * 20;
    public static final int BLOOD_SENSE_COOLDOWN_SECONDS = 3;
    public static final double BLOOD_SENSE_RADIUS_HORIZONTAL = 10.0;
    public static final double BLOOD_SENSE_RADIUS_VERTICAL = 8.0;
    public static final float STEAL_CHANCE_DAY = 0.15f;
    public static final float STEAL_CHANCE_NIGHT = 0.30f;
    public static final float STEAL_AMOUNT = 1.0f;

    private static final Holder<MobEffect> GLOWING = MobEffects.GLOWING;
    private static final Random RNG = new Random();

    private VampireAbility() {}

    public static void onMeleeHit(ServerPlayer attacker, Player victim) {
        ServerLevel level = attacker.serverLevel();
        long dayTime = level.getDayTime() % 24000L;
        boolean night = dayTime >= 13000L && dayTime <= 23000L;
        float chance = night ? STEAL_CHANCE_NIGHT : STEAL_CHANCE_DAY;
        if (RNG.nextFloat() < chance) {
            attacker.heal(STEAL_AMOUNT);
        }
    }

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (ItemTags.hasName(stack, ClassKits.Names.INSIDIOUS_DISPATCH)) {
            return tryInsidiousDispatch(player);
        }
        if (!ItemTags.hasName(stack, ClassKits.Names.BLOOD_SENSE)) return false;
        if (!Cooldowns.get().ready(player, "vampire:blood_sense")) return true;

        AABB box = new AABB(
                player.getX() - BLOOD_SENSE_RADIUS_HORIZONTAL,
                player.getY() - BLOOD_SENSE_RADIUS_VERTICAL,
                player.getZ() - BLOOD_SENSE_RADIUS_HORIZONTAL,
                player.getX() + BLOOD_SENSE_RADIUS_HORIZONTAL,
                player.getY() + BLOOD_SENSE_RADIUS_VERTICAL,
                player.getZ() + BLOOD_SENSE_RADIUS_HORIZONTAL);
        List<Player> nearby = player.level().getEntitiesOfClass(Player.class, box,
                p -> p != player && !p.isInvisible());
        int revealed = 0;
        for (Player p : nearby) {
            // Glowing is visible to everyone; for Vampire-only reveal we'd need
            // packet-only outline. Slice 9a accepts the over-share.
            p.addEffect(new MobEffectInstance(GLOWING, BLOOD_SENSE_DURATION_TICKS, 0, true, false, false));
            revealed++;
        }
        Cooldowns.get().set(player, "vampire:blood_sense", BLOOD_SENSE_COOLDOWN_SECONDS);
        player.sendSystemMessage(Component.literal("Blood Sense — revealed " + revealed + " enemy(ies)")
                .withStyle(ChatFormatting.DARK_RED));
        return true;
    }

    // ---- Insidious Dispatch ----

    public static final int DISPATCH_COOLDOWN_SECONDS = 40;
    public static final double DISPATCH_RANGE = 30.0;
    public static final double FACING_THRESHOLD = -0.2; // target's look · vector-to-attacker < this → not facing

    private static boolean tryInsidiousDispatch(ServerPlayer player) {
        if (!PvpManager.isPvpAllowed()) {
            tell(player, "Insidious Dispatch requires combat phase.", ChatFormatting.YELLOW);
            return true;
        }
        if (!Cooldowns.get().ready(player, "vampire:dispatch")) {
            tell(player, "Insidious Dispatch on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, "vampire:dispatch") + "s)", ChatFormatting.GRAY);
            return true;
        }
        Player target = pickDispatchTarget(player);
        if (target == null) {
            tell(player, "No valid target (need line-of-sight enemy not facing you, ≤30 blocks).", ChatFormatting.GRAY);
            return true; // ability silently fails per docs — but we still consume the click
        }
        Vec3 behind = target.position().subtract(target.getLookAngle().scale(1.2));
        ServerLevel level = player.serverLevel();
        player.teleportTo(level, behind.x, behind.y, behind.z,
                Set.<RelativeMovement>of(), target.getYRot(), player.getXRot());
        Cooldowns.get().set(player, "vampire:dispatch", DISPATCH_COOLDOWN_SECONDS);
        tell(player, "Insidious Dispatch → " + target.getGameProfile().getName(), ChatFormatting.DARK_RED);
        return true;
    }

    private static Player pickDispatchTarget(ServerPlayer vampire) {
        MatchPlayerState selfState = TeamAssignments.get().state(vampire.getUUID());
        if (selfState == null) return null;
        AABB box = new AABB(
                vampire.getX() - DISPATCH_RANGE, vampire.getY() - DISPATCH_RANGE, vampire.getZ() - DISPATCH_RANGE,
                vampire.getX() + DISPATCH_RANGE, vampire.getY() + DISPATCH_RANGE, vampire.getZ() + DISPATCH_RANGE);
        Player closest = null;
        double bestSq = DISPATCH_RANGE * DISPATCH_RANGE;
        for (Player p : vampire.level().getEntitiesOfClass(Player.class, box, p -> p != vampire)) {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            if (s == null || s.team == null || s.team == selfState.team) continue;
            // Target must NOT be facing the vampire.
            Vec3 victimLook = p.getLookAngle();
            Vec3 toAttacker = vampire.position().subtract(p.position()).normalize();
            if (victimLook.dot(toAttacker) >= FACING_THRESHOLD) continue;
            if (!vampire.hasLineOfSight(p)) continue;
            double dsq = p.distanceToSqr(vampire);
            if (dsq < bestSq) {
                bestSq = dsq;
                closest = p;
            }
        }
        return closest;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
