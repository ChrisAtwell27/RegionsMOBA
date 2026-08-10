package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.pvp.PvpManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Mountain Warrior — Frenzy.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   Right-click the blaze powder to activate for 12 seconds. 60-second cooldown.
 *     - +1 additional melee damage (stacks with the passive +1, +2 total).
 *     - Speed I for the duration.
 *     - Heals 2 HP every 4 seconds, up to 6 HP total.
 *     - Takes 25% more damage while active.
 *   Frenzy can only be activated during a Cold Season (PVP on) or after PVP has
 *   gone permanent. Triggering outside a PVP window consumes the cooldown but
 *   only grants the Speed I and heal portions — no damage bonus, no damage penalty.
 *   Killing any enemy during Frenzy reduces the remaining cooldown by 10 seconds
 *   on expiry.
 *
 * The damage bonus and the damage penalty are both read out of
 * {@link #isFrenzyActive(UUID)} by {@link com.regionsmoba.classes.BonusDamageHook};
 * that method deliberately reports false for a non-combat (out-of-PVP-window)
 * Frenzy so the "Speed and heal only" rule falls out for free.
 *
 * The passive flat +1 melee damage is an attribute modifier, applied on kit
 * grant by {@link com.regionsmoba.classes.DamageModifiers}.
 */
public final class WarriorAbility {

    public static final int FRENZY_DURATION_TICKS = 12 * 20;
    public static final int COOLDOWN_SECONDS = 60;
    public static final int HEAL_INTERVAL_TICKS = 4 * 20;
    public static final float HEAL_PER_TICK_STEP = 2.0f;
    public static final float HEAL_TOTAL_CAP = 6.0f;
    public static final int KILL_COOLDOWN_REDUCTION_SECONDS = 10;

    private static final String COOLDOWN_ID = "warrior:frenzy";
    private static final Holder<MobEffect> SPEED = MobEffects.MOVEMENT_SPEED;

    private static final class Frenzy {
        final boolean combat;
        final long endTick;
        long nextHealTick;
        float healed;
        boolean killedDuringFrenzy;

        Frenzy(boolean combat, long startTick) {
            this.combat = combat;
            this.endTick = startTick + FRENZY_DURATION_TICKS;
            this.nextHealTick = startTick + HEAL_INTERVAL_TICKS;
        }
    }

    private static final Map<UUID, Frenzy> ACTIVE = new HashMap<>();

    private WarriorAbility() {}

    /**
     * True only while a <em>combat</em> Frenzy is running. Out-of-PVP-window
     * Frenzies return false so neither the +1 melee bonus nor the +25% damage
     * taken applies, per the doc rule.
     */
    public static boolean isFrenzyActive(UUID player) {
        Frenzy f = ACTIVE.get(player);
        return f != null && f.combat;
    }

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.FRENZY)) return false;
        if (ACTIVE.containsKey(player.getUUID())) {
            tell(player, "Frenzy already active.", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(player, COOLDOWN_ID)) {
            tell(player, "Frenzy on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }

        MinecraftServer server = player.level().getServer();
        long now = server != null ? server.getTickCount() : 0L;
        boolean combat = PvpManager.isPvpAllowed();

        ACTIVE.put(player.getUUID(), new Frenzy(combat, now));
        Cooldowns.get().set(player, COOLDOWN_ID, COOLDOWN_SECONDS);
        player.addEffect(new MobEffectInstance(SPEED, FRENZY_DURATION_TICKS, 0, true, false, true));

        if (combat) {
            tell(player, "Frenzy! +2 melee, Speed I — and 25% more damage taken.", ChatFormatting.RED);
        } else {
            tell(player, "Frenzy — outside a PVP window: Speed I and healing only.", ChatFormatting.YELLOW);
        }
        return true;
    }

    /** Drives the periodic heal and Frenzy expiry. Called every server tick. */
    public static void tick(MinecraftServer server, long globalTick) {
        if (ACTIVE.isEmpty() || server == null) return;
        Iterator<Map.Entry<UUID, Frenzy>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Frenzy> entry = it.next();
            Frenzy f = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.isRemoved()) {
                it.remove();
                continue;
            }
            if (globalTick >= f.endTick) {
                it.remove();
                onExpire(player, f);
                continue;
            }
            if (globalTick >= f.nextHealTick && f.healed < HEAL_TOTAL_CAP) {
                float amount = Math.min(HEAL_PER_TICK_STEP, HEAL_TOTAL_CAP - f.healed);
                player.heal(amount);
                f.healed += amount;
                f.nextHealTick = globalTick + HEAL_INTERVAL_TICKS;
            }
        }
    }

    private static void onExpire(ServerPlayer player, Frenzy f) {
        player.removeEffect(SPEED);
        if (!f.killedDuringFrenzy) {
            tell(player, "Frenzy ended.", ChatFormatting.GRAY);
            return;
        }
        // Kill during Frenzy: shave 10s off whatever cooldown is left.
        long remainingTicks = Cooldowns.get().remaining(player.getUUID(), COOLDOWN_ID);
        long reducedTicks = Math.max(0, remainingTicks - KILL_COOLDOWN_REDUCTION_SECONDS * 20L);
        Cooldowns.get().start(player.getUUID(), COOLDOWN_ID, (int) reducedTicks);
        tell(player, "Frenzy ended — kill credit, cooldown cut by "
                + KILL_COOLDOWN_REDUCTION_SECONDS + "s.", ChatFormatting.GOLD);
    }

    /**
     * Records a kill made while Frenzy is running. Mirrors
     * {@link BerserkerAbility#onEnemyDeath} — fires on every living death, and
     * no-ops unless the killer is a Warrior mid-Frenzy.
     */
    public static void onEnemyDeath(LivingEntity victim, DamageSource source) {
        if (!MatchManager.get().isActive()) return;
        if (!(victim instanceof Player)) return;
        if (!(source.getEntity() instanceof ServerPlayer killer)) return;
        if (killer == victim) return;
        Frenzy f = ACTIVE.get(killer.getUUID());
        if (f != null) f.killedDuringFrenzy = true;
    }

    public static void clearForPlayer(UUID player) {
        ACTIVE.remove(player);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
