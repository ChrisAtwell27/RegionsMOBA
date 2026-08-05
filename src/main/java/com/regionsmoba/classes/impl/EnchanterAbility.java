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

import java.util.List;

/**
 * Nether Enchanter — Intensifier (team XP buff stand-in).
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Intensifier (right-click lapis lazuli) — teammates within 10 blocks get
 *                                            1.25x XP for 30s. 120s cd.
 *
 * Slice 13 MVP: applies Hero of the Village (vanilla aura buff) to teammates
 * in 10 blocks for 30s — a stand-in for the XP-multiplier hook which needs
 * a mixin into Player.giveExperiencePoints. Polish pass to switch to actual
 * XP scaling.
 *
 * 2x XP passive, XP-as-HP-shield (incoming damage drains XP at ≤7 HP),
 * Enchantment Boost (30% chance +1 enchant level) all need event/mixin work
 * not yet wired — deferred.
 */
public final class EnchanterAbility {

    public static final double RADIUS = 10.0;
    public static final int DURATION_TICKS = 30 * 20;
    public static final int COOLDOWN_SECONDS = 120;

    private static final Holder<MobEffect> HERO_OF_THE_VILLAGE = MobEffects.HERO_OF_THE_VILLAGE;

    private EnchanterAbility() {}

    public static boolean tryRightClick(ServerPlayer enchanter, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.INTENSIFIER)) return false;
        if (!Cooldowns.get().ready(enchanter, "enchanter:intensifier")) {
            tell(enchanter, "Intensifier on cooldown ("
                    + Cooldowns.get().remainingSeconds(enchanter, "enchanter:intensifier") + "s)",
                    ChatFormatting.GRAY);
            return true;
        }
        MatchPlayerState selfState = TeamAssignments.get().state(enchanter.getUUID());
        if (selfState == null) return true;
        AABB box = new AABB(
                enchanter.getX() - RADIUS, enchanter.getY() - RADIUS, enchanter.getZ() - RADIUS,
                enchanter.getX() + RADIUS, enchanter.getY() + RADIUS, enchanter.getZ() + RADIUS);
        List<Player> teammates = enchanter.level().getEntitiesOfClass(Player.class, box, p -> {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team == selfState.team;
        });
        if (!teammates.contains(enchanter)) teammates.add(enchanter);
        for (Player p : teammates) {
            p.addEffect(new MobEffectInstance(HERO_OF_THE_VILLAGE, DURATION_TICKS, 0, true, false, true));
        }
        Cooldowns.get().set(enchanter, "enchanter:intensifier", COOLDOWN_SECONDS);
        tell(enchanter, "Intensifier — buffed " + teammates.size() + " teammate(s)", ChatFormatting.AQUA);
        return true;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
