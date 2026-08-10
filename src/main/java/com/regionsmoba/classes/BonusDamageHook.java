package com.regionsmoba.classes;

import com.regionsmoba.classes.impl.WarriorAbility;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.Vec3;

/**
 * Applies BONUS damage on top of the resolved hit, after vanilla damage has
 * landed. We re-call hurtServer with a small extra amount; a thread-local
 * guard prevents recursion.
 *
 * Covered bonuses:
 *   Berserker armor-difference scaling — bonus = damageDealt × diff/200
 *   Spy Backstab — +1 when attacker is behind victim (victim look · vec-to-attacker < -0.2)
 *   Lumberjack +0.5 axe — +1 HP when attacker is melee with an AxeItem
 *   Archer +1 bow — +1 HP when source.directEntity is an arrow owned by the Archer
 *   Warrior Frenzy +1 melee while active — +1 HP melee bonus on top of the passive +1
 *   Warrior Frenzy +25% damage taken — +25% incoming damage while active
 */
public final class BonusDamageHook {

    private static final ThreadLocal<Boolean> reentry = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BonusDamageHook() {}

    public static void apply(LivingEntity victim, DamageSource source, float damageTaken) {
        if (reentry.get()) return;
        if (damageTaken <= 0) return;
        if (!(victim.level() instanceof ServerLevel level)) return;

        float bonus = 0f;

        if (source.getEntity() instanceof ServerPlayer attacker && attacker != victim) {
            MatchPlayerState as = TeamAssignments.get().state(attacker.getUUID());
            if (as != null && as.biomeClass != null) {
                if (as.biomeClass == BiomeClass.MOUNTAIN_BERSERKER) {
                    bonus += berserkerBonus(attacker, victim, damageTaken);
                }
                if (as.biomeClass == BiomeClass.PLAINS_SPY && victim instanceof Player victimPlayer) {
                    bonus += backstabBonus(attacker, victimPlayer);
                }
                if (as.biomeClass == BiomeClass.PLAINS_LUMBERJACK
                        && source.getDirectEntity() == attacker
                        && attacker.getMainHandItem().getItem() instanceof AxeItem) {
                    bonus += 1.0f;
                }
                if (as.biomeClass == BiomeClass.PLAINS_ARCHER
                        && source.getDirectEntity() instanceof AbstractArrow arrow
                        && arrow.getOwner() == attacker) {
                    bonus += 1.0f;
                }
                if (as.biomeClass == BiomeClass.MOUNTAIN_WARRIOR
                        && source.getDirectEntity() == attacker
                        && WarriorAbility.isFrenzyActive(attacker.getUUID())) {
                    bonus += 1.0f;
                }
            }
        }

        if (victim instanceof ServerPlayer victimPlayer) {
            MatchPlayerState vs = TeamAssignments.get().state(victimPlayer.getUUID());
            if (vs != null && vs.biomeClass == BiomeClass.MOUNTAIN_WARRIOR
                    && WarriorAbility.isFrenzyActive(victimPlayer.getUUID())) {
                bonus += damageTaken * 0.25f;
            }
        }

        if (bonus <= 0) return;

        reentry.set(Boolean.TRUE);
        try {
            victim.hurt(source, bonus);
        } finally {
            reentry.set(Boolean.FALSE);
        }
    }

    private static float berserkerBonus(ServerPlayer attacker, LivingEntity victim, float damageTaken) {
        int attackerArmor = Math.min(20, attacker.getArmorValue());
        int victimArmor = Math.min(20, victim.getArmorValue());
        int diff = victimArmor - attackerArmor;
        if (diff <= 0) return 0;
        return damageTaken * diff / 200f;
    }

    private static float backstabBonus(ServerPlayer attacker, Player victim) {
        Vec3 victimLook = victim.getLookAngle();
        Vec3 toAttacker = attacker.position().subtract(victim.position()).normalize();
        if (victimLook.dot(toAttacker) >= -0.2) return 0;
        return 1.0f;
    }
}
