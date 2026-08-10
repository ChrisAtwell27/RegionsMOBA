package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ocean Siren — Drain execute.
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Right-click red dye on an enemy in line of sight.
 *   - Target ≤30% max HP → instant death; remaining HP transferred to Siren as healing.
 *   - Target above 30% max HP → Siren takes the shortfall (target HP - 30% threshold)
 *     as TRUE damage (armor + resistance ignored).
 *   60s cd. PVP-gated.
 *
 * The 30% threshold uses MAX_HEALTH (covers Berserker's stack), not vanilla 20.
 *
 * "See enemy health" passive (HP bars above heads) is deferred — needs scoreboard
 * sidebar / packet-only display to be Siren-only.
 */
public final class SirenAbility {

    public static final double RANGE = 32.0;
    public static final int COOLDOWN_SECONDS = 60;
    public static final float EXECUTE_THRESHOLD = 0.30f;

    private SirenAbility() {}

    public static boolean tryRightClick(ServerPlayer siren, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.SIREN_COOLDOWN)) return false;
        if (!Cooldowns.get().ready(siren, "siren:drain")) return true;
        if (!PvpManager.isPvpAllowed()) {
            tell(siren, "Drain requires combat phase.", ChatFormatting.YELLOW);
            return true;
        }
        Player target = lineOfSightEnemy(siren);
        if (target == null) {
            tell(siren, "No line-of-sight enemy in range.", ChatFormatting.GRAY);
            return true;
        }
        float threshold = target.getMaxHealth() * EXECUTE_THRESHOLD;
        ServerLevel level = siren.serverLevel();
        if (target.getHealth() <= threshold) {
            float healed = target.getHealth();
            target.hurt(level.damageSources().magic(), Float.MAX_VALUE);
            siren.heal(healed);
            tell(siren, "Drained " + target.getGameProfile().getName() + " — +" + healed + " HP",
                    ChatFormatting.DARK_RED);
        } else {
            float shortfall = target.getHealth() - threshold;
            // True damage to Siren — bypass armor/resistance via generic source.
            siren.hurt(level.damageSources().magic(), shortfall);
            tell(siren, target.getGameProfile().getName() + " too healthy — backlash " + shortfall + " HP",
                    ChatFormatting.RED);
        }
        Cooldowns.get().set(siren, "siren:drain", COOLDOWN_SECONDS);
        return true;
    }

    private static Player lineOfSightEnemy(ServerPlayer siren) {
        MatchPlayerState selfState = TeamAssignments.get().state(siren.getUUID());
        if (selfState == null) return null;
        AABB box = new AABB(
                siren.getX() - RANGE, siren.getY() - RANGE, siren.getZ() - RANGE,
                siren.getX() + RANGE, siren.getY() + RANGE, siren.getZ() + RANGE);
        Player closest = null;
        double bestDot = 0.85; // tighter than 0.5 so the player must be roughly in front
        Vec3 look = siren.getLookAngle();
        for (Player p : siren.level().getEntitiesOfClass(Player.class, box, p -> p != siren)) {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            if (s == null || s.team == null || s.team == selfState.team) continue;
            Vec3 toTarget = p.position().subtract(siren.position()).normalize();
            double dot = look.dot(toTarget);
            if (dot < bestDot) continue;
            // Cheap line-of-sight: clear path along the look direction
            if (!siren.hasLineOfSight(p)) continue;
            bestDot = dot;
            closest = p;
        }
        return closest;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
