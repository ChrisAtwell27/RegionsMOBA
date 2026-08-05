package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Nether Wizard — five spells on a shared cooldown.
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Left-click the wand (or read the Spellbook) to choose a spell, right-click
 *   to cast. Spells are instantaneous. A global 15-second cooldown blocks every
 *   spell after any cast, on top of each spell's own cooldown.
 *
 *     Inferno       50s  r1  ignites enemies 10s + a fire block at their feet
 *     Void Bolt     50s  r1  Wither II, 5s
 *     Arcane Bolt   30s  r1  7 damage AoE (armor-reduced), counts as a melee kill
 *     Glacial Nova  35s  r2  Slowness III + Mining Fatigue I, 10s
 *     Whirlwind     25s  r3  knocks enemies and the Wizard away; 5s fall immunity
 *
 *   Offensive spells are PVP-gated. Whirlwind stays usable in peace for mobility.
 *
 * Areas are centred on the Wizard — the radii (1–3 blocks) only make sense as a
 * self-centred burst, and it makes Whirlwind read as the panic button it is.
 *
 * Arcane Bolt deals its damage through {@code damageSources().playerAttack(wizard)},
 * whose direct entity is the Wizard. That is exactly the test
 * {@link BerserkerAbility} uses for melee credit, so the doc's "counts as a melee
 * kill" for the Berserker's 15→20 scaling falls out without a special case.
 *
 * Inferno being useless against Nether targets is likewise automatic: the team's
 * permanent Fire Resistance passive eats the burn.
 */
public final class WizardAbility {

    public static final int GLOBAL_COOLDOWN_SECONDS = 15;

    private static final String GLOBAL_COOLDOWN_ID = "wizard:global";

    private enum Spell {
        INFERNO("Inferno", 50, 1.0, ChatFormatting.GOLD),
        VOID_BOLT("Void Bolt", 50, 1.0, ChatFormatting.DARK_PURPLE),
        ARCANE_BOLT("Arcane Bolt", 30, 1.0, ChatFormatting.LIGHT_PURPLE),
        GLACIAL_NOVA("Glacial Nova", 35, 2.0, ChatFormatting.AQUA),
        WHIRLWIND("Whirlwind", 25, 3.0, ChatFormatting.WHITE);

        final String label;
        final int cooldownSeconds;
        final double radius;
        final ChatFormatting colour;

        Spell(String label, int cooldownSeconds, double radius, ChatFormatting colour) {
            this.label = label;
            this.cooldownSeconds = cooldownSeconds;
            this.radius = radius;
            this.colour = colour;
        }

        String cooldownId() {
            return "wizard:" + name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Whirlwind is mobility; everything else needs a PVP window. */
        boolean offensive() {
            return this != WHIRLWIND;
        }
    }

    public static final int INFERNO_BURN_SECONDS = 10;
    public static final int VOID_BOLT_WITHER_TICKS = 5 * 20;
    public static final int VOID_BOLT_WITHER_AMPLIFIER = 1; // Wither II
    public static final float ARCANE_BOLT_DAMAGE = 7.0f;
    public static final int GLACIAL_NOVA_TICKS = 10 * 20;
    public static final int GLACIAL_SLOWNESS_AMPLIFIER = 2; // Slowness III
    public static final int GLACIAL_FATIGUE_AMPLIFIER = 0; // Mining Fatigue I
    public static final double WHIRLWIND_PUSH = 1.2;
    public static final int WHIRLWIND_FALL_IMMUNITY_TICKS = 5 * 20;

    private static final Holder<MobEffect> WITHER = MobEffects.WITHER;
    private static final Holder<MobEffect> SLOWNESS = MobEffects.SLOWNESS;
    private static final Holder<MobEffect> MINING_FATIGUE = MobEffects.MINING_FATIGUE;
    private static final Holder<MobEffect> SLOW_FALLING = MobEffects.SLOW_FALLING;

    /** Selected spell per Wizard. Absent means the first spell. */
    private static final Map<UUID, Spell> SELECTED = new HashMap<>();

    private WizardAbility() {}

    // ---- Selection ----

    /** Left-click the wand to cycle. Driven by the swing hook so air swings count. */
    public static boolean trySwing(ServerPlayer wizard) {
        if (!ItemTags.hasName(wizard.getMainHandItem(), ClassKits.Names.WIZARD_WAND)) return false;
        Spell[] all = Spell.values();
        Spell current = SELECTED.getOrDefault(wizard.getUUID(), all[0]);
        Spell next = all[(current.ordinal() + 1) % all.length];
        SELECTED.put(wizard.getUUID(), next);
        tell(wizard, "Spell: " + next.label + " (" + next.cooldownSeconds + "s)", next.colour);
        return true;
    }

    public static boolean tryRightClick(ServerPlayer wizard, ItemStack stack) {
        if (ItemTags.hasName(stack, ClassKits.Names.WIZARD_SPELLBOOK)) {
            readSpellbook(wizard);
            return true;
        }
        if (!ItemTags.hasName(stack, ClassKits.Names.WIZARD_WAND)) return false;
        return cast(wizard);
    }

    private static void readSpellbook(ServerPlayer wizard) {
        Spell selected = SELECTED.getOrDefault(wizard.getUUID(), Spell.values()[0]);
        tell(wizard, "-- Spellbook --", ChatFormatting.GRAY);
        for (Spell spell : Spell.values()) {
            String marker = spell == selected ? " <" : "";
            tell(wizard, "  " + spell.label + " — " + spell.cooldownSeconds + "s, r"
                    + (int) spell.radius + marker, spell.colour);
        }
    }

    // ---- Casting ----

    private static boolean cast(ServerPlayer wizard) {
        Spell spell = SELECTED.getOrDefault(wizard.getUUID(), Spell.values()[0]);

        if (!Cooldowns.get().ready(wizard, GLOBAL_COOLDOWN_ID)) {
            tell(wizard, "Still channelling ("
                    + Cooldowns.get().remainingSeconds(wizard, GLOBAL_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(wizard, spell.cooldownId())) {
            tell(wizard, spell.label + " on cooldown ("
                    + Cooldowns.get().remainingSeconds(wizard, spell.cooldownId()) + "s)", ChatFormatting.GRAY);
            return true;
        }
        if (spell.offensive() && !PvpManager.isPvpAllowed()) {
            tell(wizard, spell.label + " requires a combat phase.", ChatFormatting.YELLOW);
            return true; // no cooldown burned — the spell never went off
        }

        List<Player> enemies = enemiesWithin(wizard, spell.radius);
        switch (spell) {
            case INFERNO -> castInferno(wizard, enemies);
            case VOID_BOLT -> castVoidBolt(enemies);
            case ARCANE_BOLT -> castArcaneBolt(wizard, enemies);
            case GLACIAL_NOVA -> castGlacialNova(enemies);
            case WHIRLWIND -> castWhirlwind(wizard, enemies);
        }

        Cooldowns.get().set(wizard, spell.cooldownId(), spell.cooldownSeconds);
        Cooldowns.get().set(wizard, GLOBAL_COOLDOWN_ID, GLOBAL_COOLDOWN_SECONDS);
        tell(wizard, spell.label + " — " + enemies.size() + " target(s)", spell.colour);
        return true;
    }

    private static void castInferno(ServerPlayer wizard, List<Player> enemies) {
        ServerLevel level = wizard.level();
        for (Player enemy : enemies) {
            enemy.igniteForSeconds(INFERNO_BURN_SECONDS);
            BlockPos feet = enemy.blockPosition();
            if (level.getBlockState(feet).canBeReplaced()) {
                level.setBlock(feet, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void castVoidBolt(List<Player> enemies) {
        for (Player enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(
                    WITHER, VOID_BOLT_WITHER_TICKS, VOID_BOLT_WITHER_AMPLIFIER, true, false, true));
        }
    }

    private static void castArcaneBolt(ServerPlayer wizard, List<Player> enemies) {
        ServerLevel level = wizard.level();
        for (Player enemy : enemies) {
            enemy.hurtServer(level, level.damageSources().playerAttack(wizard), ARCANE_BOLT_DAMAGE);
        }
    }

    private static void castGlacialNova(List<Player> enemies) {
        for (Player enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(
                    SLOWNESS, GLACIAL_NOVA_TICKS, GLACIAL_SLOWNESS_AMPLIFIER, true, false, true));
            enemy.addEffect(new MobEffectInstance(
                    MINING_FATIGUE, GLACIAL_NOVA_TICKS, GLACIAL_FATIGUE_AMPLIFIER, true, false, true));
        }
    }

    private static void castWhirlwind(ServerPlayer wizard, List<Player> enemies) {
        for (Player enemy : enemies) {
            Vec3 away = enemy.position().subtract(wizard.position()).normalize();
            enemy.setDeltaMovement(away.scale(WHIRLWIND_PUSH).add(0, 0.5, 0));
            enemy.hurtMarked = true;
        }
        // The Wizard is thrown too — backwards, so it doubles as an escape.
        Vec3 selfPush = wizard.getLookAngle().scale(-WHIRLWIND_PUSH).add(0, 0.6, 0);
        wizard.setDeltaMovement(selfPush);
        wizard.hurtMarked = true;
        wizard.addEffect(new MobEffectInstance(
                SLOW_FALLING, WHIRLWIND_FALL_IMMUNITY_TICKS, 0, true, false, true));
    }

    private static List<Player> enemiesWithin(ServerPlayer wizard, double radius) {
        MatchPlayerState self = TeamAssignments.get().state(wizard.getUUID());
        if (self == null || self.team == null) return List.of();
        AABB box = wizard.getBoundingBox().inflate(radius);
        return wizard.level().getEntitiesOfClass(Player.class, box, p -> {
            if (p == wizard) return false;
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            return s != null && s.team != null && s.team != self.team && !s.spectator;
        });
    }

    public static void clearForPlayer(UUID player) {
        SELECTED.remove(player);
    }

    public static void clearAll() {
        SELECTED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
