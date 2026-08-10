package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.Cooldowns;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ocean Immobilizer — single-target stun (right-click) and AoE slow (left-click).
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Right-click — point at an enemy within 5 blocks. Both the Immobilizer and
 *     the target get Slowness XI + negative Jump Boost (full movement lock) for
 *     2–5 seconds, scaled with the target's armor. The target also gets Mining
 *     Fatigue II + Absorption II for the same duration. The target cannot be
 *     re-immobilized for 15 seconds after release. PVP-gated: outside a PVP
 *     window the ability fails and the cooldown is NOT consumed.
 *   Left-click — Slowness III for 5 seconds to all enemies within 5 blocks.
 *   Cooldown — 30 seconds, shared between both.
 *
 * "Both players can drink potions, throw potions, or eat food during the stun"
 * falls out for free: the lock is movement effects only, never a use-cancel.
 */
public final class ImmobilizerAbility {

    public static final double RANGE = 5.0;
    public static final int COOLDOWN_SECONDS = 30;
    public static final int REIMMOBILIZE_LOCKOUT_SECONDS = 15;

    public static final int STUN_MIN_TICKS = 2 * 20;
    public static final int STUN_MAX_TICKS = 5 * 20;
    /** Slowness XI. */
    public static final int STUN_SLOWNESS_AMPLIFIER = 10;
    /**
     * Jump Boost amplifier 128 wraps to a negative jump-strength modifier — the
     * vanilla trick for pinning a player to the ground without touching gravity.
     */
    public static final int NEGATIVE_JUMP_AMPLIFIER = 128;
    public static final int MINING_FATIGUE_AMPLIFIER = 1;
    public static final int ABSORPTION_AMPLIFIER = 1;

    public static final int AOE_SLOW_TICKS = 5 * 20;
    /** Slowness III. */
    public static final int AOE_SLOW_AMPLIFIER = 2;

    private static final String COOLDOWN_ID = "immobilizer:shared";
    private static final String LOCKOUT_ID = "immobilizer:lockout";

    private static final Holder<MobEffect> SLOWNESS = MobEffects.MOVEMENT_SLOWDOWN;
    private static final Holder<MobEffect> JUMP_BOOST = MobEffects.JUMP;
    private static final Holder<MobEffect> MINING_FATIGUE = MobEffects.DIG_SLOWDOWN;
    private static final Holder<MobEffect> ABSORPTION = MobEffects.ABSORPTION;

    private ImmobilizerAbility() {}

    /**
     * The Immobilizer has no ability item — the stun is a bare right-click while
     * pointing at an enemy. It only claims the click when a valid target is in
     * range, so ordinary item use is untouched everywhere else.
     */
    public static boolean tryRightClick(ServerPlayer immobilizer) {
        ServerPlayer target = targetInSight(immobilizer);
        if (target == null) return false;

        // Every rejection path below returns false rather than claiming the
        // click. The Immobilizer has no ability item, so claiming would swallow
        // the right-click of whatever they happen to be holding — meaning they
        // could never eat or drink while facing an enemy within 5 blocks, which
        // is precisely the situation the doc says they should be able to.
        if (!PvpManager.isPvpAllowedFor(immobilizer, target)) {
            tell(immobilizer, "Not in combat phase.", ChatFormatting.YELLOW);
            return false; // cooldown is not consumed either — per the doc
        }
        if (!Cooldowns.get().ready(immobilizer, COOLDOWN_ID)) {
            tell(immobilizer, "Immobilize on cooldown ("
                    + Cooldowns.get().remainingSeconds(immobilizer, COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return false;
        }
        if (!Cooldowns.get().ready(target, LOCKOUT_ID)) {
            tell(immobilizer, target.getGameProfile().getName() + " cannot be re-immobilized yet ("
                    + Cooldowns.get().remainingSeconds(target, LOCKOUT_ID) + "s)", ChatFormatting.GRAY);
            return false;
        }

        int ticks = stunTicks(target);
        lockMovement(immobilizer, ticks);
        lockMovement(target, ticks);
        target.addEffect(new MobEffectInstance(MINING_FATIGUE, ticks, MINING_FATIGUE_AMPLIFIER, true, false, true));
        target.addEffect(new MobEffectInstance(ABSORPTION, ticks, ABSORPTION_AMPLIFIER, true, false, true));

        Cooldowns.get().set(immobilizer, COOLDOWN_ID, COOLDOWN_SECONDS);
        // The lockout runs from release, so it covers the stun plus 15 seconds.
        Cooldowns.get().start(target.getUUID(), LOCKOUT_ID, ticks + REIMMOBILIZE_LOCKOUT_SECONDS * 20);

        tell(immobilizer, "Immobilized " + target.getGameProfile().getName()
                + " for " + (ticks / 20.0) + "s.", ChatFormatting.AQUA);
        tell(target, "Immobilized!", ChatFormatting.RED);
        return true;
    }

    /** Left-click AoE slow. Driven by the swing packet, so an air swing counts. */
    public static boolean trySwing(ServerPlayer immobilizer) {
        if (!Cooldowns.get().ready(immobilizer, COOLDOWN_ID)) return false;
        MatchPlayerState self = TeamAssignments.get().state(immobilizer.getUUID());
        if (self == null || self.team == null) return false;

        AABB box = immobilizer.getBoundingBox().inflate(RANGE);
        List<Player> enemies = immobilizer.level().getEntitiesOfClass(Player.class, box, p -> {
            if (p == immobilizer) return false;
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team != null && s.team != self.team;
        });
        if (enemies.isEmpty()) return false; // don't burn the shared cooldown on an empty swing

        for (Player enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(
                    SLOWNESS, AOE_SLOW_TICKS, AOE_SLOW_AMPLIFIER, true, false, true));
        }
        Cooldowns.get().set(immobilizer, COOLDOWN_ID, COOLDOWN_SECONDS);
        tell(immobilizer, "Slowed " + enemies.size() + " enemy(ies).", ChatFormatting.AQUA);
        return true;
    }

    /** 2s against an unarmored target, scaling to 5s against a fully-armored one. */
    private static int stunTicks(ServerPlayer target) {
        int armor = Math.min(20, target.getArmorValue());
        return STUN_MIN_TICKS + (STUN_MAX_TICKS - STUN_MIN_TICKS) * armor / 20;
    }

    private static void lockMovement(ServerPlayer player, int ticks) {
        player.addEffect(new MobEffectInstance(SLOWNESS, ticks, STUN_SLOWNESS_AMPLIFIER, true, false, true));
        player.addEffect(new MobEffectInstance(JUMP_BOOST, ticks, NEGATIVE_JUMP_AMPLIFIER, true, false, false));
    }

    /**
     * Nearest enemy within {@link #RANGE} that the Immobilizer is facing. Uses a
     * look-direction cone rather than a raycast so a target behind a fence post
     * or in a doorway still counts.
     */
    private static ServerPlayer targetInSight(ServerPlayer immobilizer) {
        MatchPlayerState self = TeamAssignments.get().state(immobilizer.getUUID());
        if (self == null || self.team == null) return null;

        Vec3 look = immobilizer.getLookAngle();
        AABB box = immobilizer.getBoundingBox().inflate(RANGE);
        ServerPlayer best = null;
        double bestSq = RANGE * RANGE;
        for (Player p : immobilizer.level().getEntitiesOfClass(Player.class, box, p -> p != immobilizer)) {
            if (!(p instanceof ServerPlayer candidate)) continue;
            MatchPlayerState s = TeamAssignments.get().state(candidate.getUUID());
            if (s == null || s.team == null || s.team == self.team) continue;
            Vec3 toTarget = candidate.position().subtract(immobilizer.position()).normalize();
            if (look.dot(toTarget) < 0.65) continue; // roughly a 50-degree cone
            double dsq = candidate.distanceToSqr(immobilizer);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = candidate;
            }
        }
        return best;
    }

    public static void clearAll() {}

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
