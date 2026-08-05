package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/**
 * Ocean Healer — team regen (right-click) + focused heal (left-click).
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Right-click blood bag — Regen III for 3 s on up to 3 lowest-HP allies in 6 blocks.
 *                           Other Healers in range get only 1.5 s.
 *                           15-s cooldown.
 *   Left-click on teammate — heal 15 HP + cleanse negative effects on both.
 *                           45-s cooldown.
 *
 * Healer XP (2 XP per healed teammate) is tracked but not granted via the
 * vanilla XP system in slice 9a — that's a polish hook.
 */
public final class HealerAbility {

    public static final int REGEN_COOLDOWN_SECONDS = 15;
    public static final int REGEN_DURATION_ALLY_TICKS = 3 * 20;
    public static final int REGEN_DURATION_HEALER_TICKS = (int) (1.5 * 20);
    public static final double REGEN_RADIUS = 6.0;
    public static final int MAX_REGEN_TARGETS = 3;

    public static final int FOCUSED_HEAL_COOLDOWN_SECONDS = 45;
    public static final float FOCUSED_HEAL_AMOUNT = 15.0f;

    private static final Holder<MobEffect> REGEN = MobEffects.REGENERATION;

    private HealerAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.HEALER_BLOOD_BAG)) return false;
        if (!Cooldowns.get().ready(player, "healer:regen")) {
            player.sendSystemMessage(Component.literal("Team regen on cooldown ("
                            + Cooldowns.get().remainingSeconds(player, "healer:regen") + "s)")
                    .withStyle(ChatFormatting.GRAY));
            return true;
        }

        // Find friendly players within radius, lowest HP first.
        AABB box = new AABB(
                player.getX() - REGEN_RADIUS, player.getY() - REGEN_RADIUS, player.getZ() - REGEN_RADIUS,
                player.getX() + REGEN_RADIUS, player.getY() + REGEN_RADIUS, player.getZ() + REGEN_RADIUS);
        MatchPlayerState selfState = TeamAssignments.get().state(player.getUUID());
        if (selfState == null) return true;
        List<Player> allies = player.level().getEntitiesOfClass(Player.class, box, p -> {
            if (p == player) return false;
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team == selfState.team;
        });
        allies.sort(Comparator.comparingDouble(Player::getHealth));
        int targeted = 0;
        for (Player ally : allies) {
            if (targeted >= MAX_REGEN_TARGETS) break;
            MatchPlayerState s = TeamAssignments.get().state(ally.getUUID());
            int duration = (s != null && s.biomeClass == com.regionsmoba.team.BiomeClass.OCEAN_HEALER)
                    ? REGEN_DURATION_HEALER_TICKS : REGEN_DURATION_ALLY_TICKS;
            ally.addEffect(new MobEffectInstance(REGEN, duration, 2, true, false, true));
            targeted++;
        }
        Cooldowns.get().set(player, "healer:regen", REGEN_COOLDOWN_SECONDS);
        player.sendSystemMessage(Component.literal("Team regen — " + targeted + " teammate(s)")
                .withStyle(ChatFormatting.GREEN));
        return true;
    }

    public static boolean tryLeftClickOnTeammate(ServerPlayer healer, Player target) {
        MatchPlayerState selfState = TeamAssignments.get().state(healer.getUUID());
        MatchPlayerState targetState = TeamAssignments.get().state(target.getUUID());
        if (selfState == null || targetState == null) return false;
        if (selfState.team != targetState.team) return false; // not a teammate — let the normal attack proceed
        if (!Cooldowns.get().ready(healer, "healer:focused")) {
            healer.sendSystemMessage(Component.literal("Focused heal on cooldown ("
                            + Cooldowns.get().remainingSeconds(healer, "healer:focused") + "s)")
                    .withStyle(ChatFormatting.GRAY));
            return true; // still consume the click so we don't damage a teammate
        }
        target.heal(FOCUSED_HEAL_AMOUNT);
        target.removeAllEffects();
        healer.removeAllEffects();
        Cooldowns.get().set(healer, "healer:focused", FOCUSED_HEAL_COOLDOWN_SECONDS);
        healer.sendSystemMessage(Component.literal("Focused heal — " + target.getGameProfile().name())
                .withStyle(ChatFormatting.GREEN));
        return true;
    }
}
