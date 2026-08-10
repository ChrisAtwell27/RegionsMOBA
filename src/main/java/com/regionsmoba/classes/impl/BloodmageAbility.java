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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Nether Bloodmage — Poison-on-hit and Corrupt.
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Poison-on-Hit — 25% chance on every melee hit to inflict Poison for 2s.
 *   Corrupt — right-click the fermented spider eye. All enemies within 4 blocks
 *     lose 2 hearts of max HP and take Wither for 5 seconds. 60-second cooldown.
 *
 * Bloodcursed Terraform lives in {@link BloodmageTerraform}.
 *
 * The doc does not say how long Corrupt's max-HP loss lasts. It is implemented
 * as a transient modifier that persists until the victim respawns or changes
 * class (both run through {@code KitGrant}, which calls {@link #clearCurse}),
 * and repeat casts stack. If it was meant to be timed, that is a one-line change
 * to the modifier bookkeeping.
 */
public final class BloodmageAbility {

    public static final float POISON_CHANCE = 0.25f;
    public static final int POISON_TICKS = 2 * 20;

    public static final double CORRUPT_RADIUS = 4.0;
    public static final int CORRUPT_COOLDOWN_SECONDS = 60;
    public static final int CORRUPT_WITHER_TICKS = 5 * 20;
    /** 2 hearts. */
    public static final double CORRUPT_MAX_HP_LOSS = 4.0;

    private static final String CORRUPT_COOLDOWN_ID = "bloodmage:corrupt";
    private static final ResourceLocation CURSE_ID = ResourceLocation.parse("regionsmoba:bloodmage_curse");

    private static final Holder<MobEffect> POISON = MobEffects.POISON;
    private static final Holder<MobEffect> WITHER = MobEffects.WITHER;

    /** Accumulated max-HP loss per cursed victim. */
    private static final Map<UUID, Double> CURSED = new HashMap<>();

    private static final Random RNG = new Random();

    private BloodmageAbility() {}

    /** Called from AFTER_DAMAGE once the hit is known to be a Bloodmage melee swing. */
    public static void onMeleeHit(ServerPlayer bloodmage, Player victim) {
        if (RNG.nextFloat() >= POISON_CHANCE) return;
        victim.addEffect(new MobEffectInstance(POISON, POISON_TICKS, 0, true, false, true));
    }

    public static boolean tryRightClick(ServerPlayer bloodmage, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.CORRUPT)) return false;
        if (!Cooldowns.get().ready(bloodmage, CORRUPT_COOLDOWN_ID)) {
            tell(bloodmage, "Corrupt on cooldown ("
                    + Cooldowns.get().remainingSeconds(bloodmage, CORRUPT_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        if (!PvpManager.isPvpAllowed()) {
            tell(bloodmage, "Corrupt requires a combat phase.", ChatFormatting.YELLOW);
            return true;
        }
        MatchPlayerState self = TeamAssignments.get().state(bloodmage.getUUID());
        if (self == null || self.team == null) return true;

        AABB box = bloodmage.getBoundingBox().inflate(CORRUPT_RADIUS);
        List<Player> enemies = bloodmage.level().getEntitiesOfClass(Player.class, box, p -> {
            if (p == bloodmage) return false;
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team != null && s.team != self.team && !s.spectator;
        });

        for (Player enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(WITHER, CORRUPT_WITHER_TICKS, 0, true, false, true));
            if (enemy instanceof ServerPlayer victim) curse(victim);
        }
        Cooldowns.get().set(bloodmage, CORRUPT_COOLDOWN_ID, CORRUPT_COOLDOWN_SECONDS);
        tell(bloodmage, "Corrupt — " + enemies.size() + " enemy(ies) cursed.", ChatFormatting.DARK_RED);
        return true;
    }

    /** Stacks another 2 hearts of max-HP loss onto the victim. */
    private static void curse(ServerPlayer victim) {
        AttributeInstance attr = victim.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        double total = CURSED.getOrDefault(victim.getUUID(), 0.0) + CORRUPT_MAX_HP_LOSS;
        CURSED.put(victim.getUUID(), total);
        attr.removeModifier(CURSE_ID);
        attr.addOrUpdateTransientModifier(
                new AttributeModifier(CURSE_ID, -total, AttributeModifier.Operation.ADD_VALUE));
        tell(victim, "Cursed — max health reduced.", ChatFormatting.DARK_RED);
    }

    /** Lifts the curse. Called from every kit grant, so death or class change clears it. */
    public static void clearCurse(ServerPlayer player) {
        CURSED.remove(player.getUUID());
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) attr.removeModifier(CURSE_ID);
    }

    public static void clearAll() {
        CURSED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
