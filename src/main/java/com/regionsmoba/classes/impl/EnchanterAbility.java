package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nether Enchanter — Intensifier (team XP buff stand-in).
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Intensifier (right-click lapis lazuli) — teammates within 10 blocks get
 *                                            1.25x XP for 30s. 120s cd.
 *
   Passive — the Enchanter earns 2x XP from all sources, and below 7 HP
 *             incoming damage drains XP levels instead of health.
 *
 * The XP multipliers are real now: {@link com.regionsmoba.mixin.PlayerExperienceMixin}
 * funnels every XP award through {@link #scaleExperience}. Intensifier keeps a
 * timed buff window per teammate rather than an effect, so the 1.25x applies to
 * the actual XP rather than standing in for it.
 *
 * Enchantment Boost (30% chance of +1 enchant level at a table) is still
 * deferred — it needs a mixin into the enchanting menu's roll, which is a
 * different surface from anything else here.
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
        MinecraftServer server = enchanter.level().getServer();
        long until = (server != null ? server.getTickCount() : 0L) + DURATION_TICKS;
        for (Player p : teammates) {
            p.addEffect(new MobEffectInstance(HERO_OF_THE_VILLAGE, DURATION_TICKS, 0, true, false, true));
            INTENSIFIED.put(p.getUUID(), until);
        }
        Cooldowns.get().set(enchanter, "enchanter:intensifier", COOLDOWN_SECONDS);
        tell(enchanter, "Intensifier — buffed " + teammates.size() + " teammate(s)", ChatFormatting.AQUA);
        return true;
    }

    // ---- XP scaling ----

    public static final double ENCHANTER_XP_MULTIPLIER = 2.0;
    public static final double INTENSIFIER_XP_MULTIPLIER = 1.25;

    /** Players currently inside an Intensifier window, keyed to its end tick. */
    private static final Map<UUID, Long> INTENSIFIED = new HashMap<>();

    /**
     * Applies the Enchanter's own 2x and any active Intensifier 1.25x. Called
     * from {@link com.regionsmoba.mixin.PlayerExperienceMixin} for every XP
     * award, so it must stay cheap and must not touch world state.
     */
    public static int scaleExperience(ServerPlayer player, int amount) {
        if (amount <= 0 || !MatchManager.get().isActive()) return amount;

        double multiplier = 1.0;
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        if (state != null && state.biomeClass == BiomeClass.NETHER_ENCHANTER) {
            multiplier *= ENCHANTER_XP_MULTIPLIER;
        }

        Long until = INTENSIFIED.get(player.getUUID());
        if (until != null) {
            MinecraftServer server = player.level().getServer();
            long now = server != null ? server.getTickCount() : 0L;
            if (now < until) {
                multiplier *= INTENSIFIER_XP_MULTIPLIER;
            } else {
                INTENSIFIED.remove(player.getUUID());
            }
        }
        return multiplier == 1.0 ? amount : (int) Math.round(amount * multiplier);
    }

    // ---- XP shield ----

    /** At or below this health, damage drains XP instead. */
    public static final float SHIELD_HP_THRESHOLD = 7.0f;
    /** XP points burned per point of damage absorbed. */
    public static final int XP_PER_DAMAGE = 5;

    /**
     * Returns false to cancel the damage when the Enchanter is at or below 7 HP
     * and has XP to spend, draining the pool instead. Once the pool is empty the
     * damage lands normally, so this delays death rather than preventing it.
     */
    public static boolean absorbWithExperience(ServerPlayer enchanter, float amount) {
        if (enchanter.getHealth() > SHIELD_HP_THRESHOLD) return false;
        int cost = Math.max(1, Math.round(amount) * XP_PER_DAMAGE);
        if (enchanter.totalExperience < cost) return false;
        enchanter.giveExperiencePoints(-cost);
        tell(enchanter, "XP shield absorbed " + Math.round(amount) + " damage.", ChatFormatting.GREEN);
        return true;
    }

    public static void clearForPlayer(UUID player) {
        INTENSIFIED.remove(player);
    }

    public static void clearAll() {
        INTENSIFIED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
