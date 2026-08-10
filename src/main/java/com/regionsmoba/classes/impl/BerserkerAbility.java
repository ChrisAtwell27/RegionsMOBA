package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mountain Berserker — heart stacking + Unbreakable Will.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   Heart Stacking
 *     - Killing enemies grants +1 max heart, up to 15 hearts total.
 *     - From 15 → 20, only melee kills count, each granting +½ heart
 *       (10 melee kills to cap).
 *     - Dying removes 5 hearts.
 *     - Hearts persist across class change and disconnection. Dying on a
 *       non-Berserker class still removes 5 Berserker hearts.
 *     - Enemy deaths inside the mountain biome (any cause, any team) count as
 *       half-heart kills toward the Berserker's pool.
 *   Unbreakable Will — right-click the ingot. 20s Speed I, full knockback and
 *     movement-impairing-effect immunity. 65-second cooldown.
 *
 * The stack is stored as bonus HP above the vanilla 20 and applied as a
 * transient MAX_HEALTH modifier. It is only applied while the player is
 * actually playing Berserker — the pool itself survives a class switch, but a
 * Farmer does not walk around with 40 HP. {@link #applyStack} is the single
 * entry point for both (called on every kit grant).
 *
 * The armor-difference damage bonus is not here: it needs the resolved damage
 * number, so it lives in {@link com.regionsmoba.classes.BonusDamageHook}.
 */
public final class BerserkerAbility {

    public static final double BASE_MAX_HP = 20.0;
    /** Bonus HP ceiling for whole-heart (any-kill) gains — 15 hearts total. */
    public static final double FULL_HEART_BONUS_CAP = 10.0;
    /** Absolute bonus HP ceiling — 20 hearts total. */
    public static final double BONUS_CAP = 20.0;
    public static final double FULL_HEART_HP = 2.0;
    public static final double HALF_HEART_HP = 1.0;
    /**
     * Death penalty. The doc says "removes 5 hearts (25 HP)" — those disagree
     * (5 hearts is 10 HP, and every other heart/HP pair in the same doc uses
     * 2 HP per heart). Implemented as 5 hearts.
     */
    public static final double DEATH_PENALTY_HP = 10.0;

    public static final int WILL_DURATION_TICKS = 20 * 20;
    public static final int WILL_COOLDOWN_SECONDS = 65;

    private static final String WILL_COOLDOWN_ID = "berserker:will";
    private static final ResourceLocation STACK_ID = ResourceLocation.parse("regionsmoba:berserker_hearts");
    private static final ResourceLocation WILL_KNOCKBACK_ID = ResourceLocation.parse("regionsmoba:berserker_will_knockback");

    private static final Holder<MobEffect> SPEED = MobEffects.MOVEMENT_SPEED;
    /** Movement-impairing effects Unbreakable Will suppresses for its duration. */
    private static final List<Holder<MobEffect>> IMPAIRING = List.of(
            MobEffects.MOVEMENT_SLOWDOWN,
            MobEffects.LEVITATION,
            MobEffects.SLOW_FALLING,
            MobEffects.BLINDNESS,
            MobEffects.CONFUSION);

    /** Bonus HP above {@link #BASE_MAX_HP}, keyed by player. Survives class change and relog. */
    private static final Map<UUID, Double> STACK = new HashMap<>();
    /** Server tick at which each active Unbreakable Will ends. */
    private static final Map<UUID, Long> WILL_UNTIL = new HashMap<>();

    private BerserkerAbility() {}

    // ---- Heart stacking ----

    /** Called from {@code ServerLivingEntityEvents.AFTER_DEATH} for every living-entity death. */
    public static void onEnemyDeath(LivingEntity victim, DamageSource source) {
        if (!MatchManager.get().isActive()) return;
        if (!(victim instanceof Player victimPlayer)) return;

        // Path 1 — a Berserker landed the kill.
        UUID credited = null;
        if (source.getEntity() instanceof ServerPlayer killer && killer != victim
                && classOf(killer) == BiomeClass.MOUNTAIN_BERSERKER) {
            boolean melee = source.getDirectEntity() == killer;
            grantKill(killer, melee);
            credited = killer.getUUID();
        }

        // Path 2 — the death happened inside the mountain biome, any cause, any
        // team. Every Berserker on the map banks a half heart. A Berserker who
        // already took kill credit above is skipped so one death is one payout.
        if (!diedInsideMountain(victimPlayer)) return;
        MinecraftServer server = victimPlayer.level().getServer();
        if (server == null) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            if (id.equals(credited)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || p == victim) continue;
            if (classOf(p) != BiomeClass.MOUNTAIN_BERSERKER) continue;
            addBonus(p, HALF_HEART_HP, "mountain biome death");
        }
    }

    /**
     * Grants kill credit. Below 15 hearts any kill banks a whole heart; past
     * that only melee kills count, and for a half heart each.
     */
    private static void grantKill(ServerPlayer berserker, boolean melee) {
        double bonus = STACK.getOrDefault(berserker.getUUID(), 0.0);
        if (bonus < FULL_HEART_BONUS_CAP) {
            addBonus(berserker, FULL_HEART_HP, "kill");
        } else if (melee) {
            addBonus(berserker, HALF_HEART_HP, "melee kill");
        }
    }

    /**
     * Called for every player death, whatever class they were on — the penalty
     * follows the Berserker pool, not the current class.
     */
    public static void onPlayerDeath(ServerPlayer player) {
        Double bonus = STACK.get(player.getUUID());
        if (bonus == null || bonus <= 0) return;
        double next = Math.max(0, bonus - DEATH_PENALTY_HP);
        STACK.put(player.getUUID(), next);
        // The respawned player is a fresh ServerPlayer instance; DeathHandler
        // re-grants the kit, which re-applies the (now smaller) stack.
        tell(player, "Berserker hearts: " + hearts(next) + " bonus heart(s) — death cost "
                + hearts(DEATH_PENALTY_HP) + ".", ChatFormatting.DARK_RED);
    }

    private static void addBonus(ServerPlayer player, double amount, String reason) {
        double bonus = STACK.getOrDefault(player.getUUID(), 0.0);
        double next = Math.min(BONUS_CAP, bonus + amount);
        if (next == bonus) return;
        STACK.put(player.getUUID(), next);
        applyModifier(player, next);
        player.setHealth(Math.min(player.getHealth() + (float) (next - bonus), player.getMaxHealth()));
        tell(player, "Berserker " + reason + " — " + hearts(next) + " bonus heart(s).", ChatFormatting.RED);
    }

    /**
     * Applies or removes the max-HP modifier to match the player's current class.
     * Called from every kit grant, so a class switch away from Berserker drops
     * the bonus HP while leaving the banked pool intact.
     */
    public static void applyStack(ServerPlayer player, BiomeClass biomeClass) {
        if (biomeClass != BiomeClass.MOUNTAIN_BERSERKER) {
            clearStack(player);
            return;
        }
        applyModifier(player, STACK.getOrDefault(player.getUUID(), 0.0));
    }

    /** Called from AFTER_RESPAWN to re-apply the Berserker passive on the new player object. */
    public static void reapply(ServerPlayer player) {
        applyStack(player, BiomeClass.MOUNTAIN_BERSERKER);
    }

    private static void applyModifier(ServerPlayer player, double bonus) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        attr.removeModifier(STACK_ID);
        if (bonus > 0) {
            attr.addOrUpdateTransientModifier(
                    new AttributeModifier(STACK_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Strips the live max-HP modifier without touching the banked pool. */
    public static void clearStack(ServerPlayer player) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) attr.removeModifier(STACK_ID);
        clearWill(player);
    }

    // ---- Unbreakable Will ----

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.UNBREAKABLE_WILL)) return false;
        if (!Cooldowns.get().ready(player, WILL_COOLDOWN_ID)) {
            tell(player, "Unbreakable Will on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, WILL_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        MinecraftServer server = player.level().getServer();
        long now = server != null ? server.getTickCount() : 0L;

        WILL_UNTIL.put(player.getUUID(), now + WILL_DURATION_TICKS);
        Cooldowns.get().set(player, WILL_COOLDOWN_ID, WILL_COOLDOWN_SECONDS);
        player.addEffect(new MobEffectInstance(SPEED, WILL_DURATION_TICKS, 0, true, false, true));
        applyKnockbackImmunity(player, true);
        stripImpairing(player);
        tell(player, "Unbreakable Will — unmovable for 20s.", ChatFormatting.GOLD);
        return true;
    }

    /**
     * Expires Unbreakable Will and re-strips impairing effects each tick, so an
     * effect applied mid-window doesn't stick.
     */
    public static void tick(MinecraftServer server, long globalTick) {
        if (WILL_UNTIL.isEmpty() || server == null) return;
        Iterator<Map.Entry<UUID, Long>> it = WILL_UNTIL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.isRemoved()) {
                it.remove();
                continue;
            }
            if (globalTick >= entry.getValue()) {
                it.remove();
                applyKnockbackImmunity(player, false);
                tell(player, "Unbreakable Will ended.", ChatFormatting.GRAY);
                continue;
            }
            stripImpairing(player);
        }
    }

    private static void stripImpairing(ServerPlayer player) {
        for (Holder<MobEffect> effect : IMPAIRING) {
            if (player.hasEffect(effect)) player.removeEffect(effect);
        }
    }

    private static void applyKnockbackImmunity(ServerPlayer player, boolean on) {
        AttributeInstance attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (attr == null) return;
        attr.removeModifier(WILL_KNOCKBACK_ID);
        if (on) {
            attr.addOrUpdateTransientModifier(
                    new AttributeModifier(WILL_KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void clearWill(ServerPlayer player) {
        WILL_UNTIL.remove(player.getUUID());
        applyKnockbackImmunity(player, false);
    }

    public static void clearAll() {
        STACK.clear();
        WILL_UNTIL.clear();
    }

    // ---- helpers ----

    private static boolean diedInsideMountain(Player victim) {
        Area bounds = RegionsConfig.get().biomeBounds(BiomeTeam.MOUNTAIN);
        if (bounds == null || !bounds.isComplete()) return false;
        if (!bounds.dimension().equals(victim.level().dimension().location().toString())) return false;
        return bounds.contains(victim.getX(), victim.getY(), victim.getZ());
    }

    private static BiomeClass classOf(ServerPlayer player) {
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        return state == null ? null : state.biomeClass;
    }

    /** Bonus HP rendered as hearts for player-facing messages. */
    private static String hearts(double bonusHp) {
        double h = bonusHp / 2.0;
        return h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h);
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
