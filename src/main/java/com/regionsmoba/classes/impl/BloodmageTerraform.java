package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Nether Bloodmage — Bloodcursed Terraform.
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Right-click the Crimson Hyphae to place the curse field. For 30 seconds the
 *   surrounding 8-block radius inflicts enemies inside with Wither II (5s),
 *   Blindness I (10s) and Hunger III (10s). Enemies already inside at placement
 *   are immune for 10 seconds, which prevents using it as a flash trap.
 *   120-second cooldown. It cannot be placed within 35 blocks of any lifeline
 *   block — Ocean's Conduit, Nether's Furnace, or Plains' Composter, the
 *   friendly Furnace included, so it can't be stacked on your own lifeline.
 *   Damage and debuffs are PVP-gated; placing outside a PVP window still burns
 *   the cooldown.
 *
 * The doc's "reskinned as nether blocks" is cosmetic and is NOT implemented —
 * the field is marked with particles instead. Everything that affects play (the
 * radius, the debuffs, the grace period, the placement restriction) is here.
 */
public final class BloodmageTerraform {

    public static final double RADIUS = 8.0;
    public static final int DURATION_TICKS = 30 * 20;
    public static final int COOLDOWN_SECONDS = 120;
    public static final int GRACE_TICKS = 10 * 20;
    public static final double LIFELINE_CLEARANCE = 35.0;

    public static final int WITHER_TICKS = 5 * 20;
    public static final int WITHER_AMPLIFIER = 1; // Wither II
    public static final int BLINDNESS_TICKS = 10 * 20;
    public static final int HUNGER_TICKS = 10 * 20;
    public static final int HUNGER_AMPLIFIER = 2; // Hunger III

    private static final String COOLDOWN_ID = "bloodmage:terraform";

    private static final Holder<MobEffect> WITHER = MobEffects.WITHER;
    private static final Holder<MobEffect> BLINDNESS = MobEffects.BLINDNESS;
    private static final Holder<MobEffect> HUNGER = MobEffects.HUNGER;

    private static final class Field {
        final BlockPosData centre;
        final BiomeTeam owner;
        final long endTick;
        final long graceUntil;
        /** Enemies standing in the field when it was placed — immune during grace. */
        final Set<UUID> grandfathered = new HashSet<>();

        Field(BlockPosData centre, BiomeTeam owner, long startTick) {
            this.centre = centre;
            this.owner = owner;
            this.endTick = startTick + DURATION_TICKS;
            this.graceUntil = startTick + GRACE_TICKS;
        }
    }

    private static final List<Field> FIELDS = new ArrayList<>();

    private BloodmageTerraform() {}

    public static boolean tryRightClick(ServerPlayer bloodmage, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.BLOODCURSED_TERRAFORM)) return false;
        if (!Cooldowns.get().ready(bloodmage, COOLDOWN_ID)) {
            tell(bloodmage, "Bloodcursed Terraform on cooldown ("
                    + Cooldowns.get().remainingSeconds(bloodmage, COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        MatchPlayerState self = TeamAssignments.get().state(bloodmage.getUUID());
        if (self == null || self.team == null) return true;

        ServerLevel level = bloodmage.level();
        BlockPos centre = bloodmage.blockPosition();
        if (nearLifeline(level, centre)) {
            tell(bloodmage, "Too close to a lifeline block (needs "
                    + (int) LIFELINE_CLEARANCE + " blocks clearance).", ChatFormatting.RED);
            return true;
        }

        MinecraftServer server = level.getServer();
        long now = server != null ? server.getTickCount() : 0L;
        Field field = new Field(BlockPosData.of(level, centre), self.team, now);

        // Grandfather everyone already standing inside, so the field can't be
        // dropped on someone's head as an instant trap.
        for (Player p : playersInside(level, centre)) {
            field.grandfathered.add(p.getUUID());
        }
        FIELDS.add(field);

        Cooldowns.get().set(bloodmage, COOLDOWN_ID, COOLDOWN_SECONDS);
        if (!PvpManager.isPvpAllowed()) {
            // Doc is explicit: the cooldown is consumed even out of combat.
            tell(bloodmage, "Curse field placed, but it is inert outside a combat phase.",
                    ChatFormatting.YELLOW);
        } else {
            tell(bloodmage, "Bloodcursed Terraform — 30s curse field.", ChatFormatting.DARK_RED);
        }
        return true;
    }

    /** Applies the field debuffs once a second and expires elapsed fields. */
    public static void tick(MinecraftServer server, long globalTick) {
        if (FIELDS.isEmpty() || server == null) return;

        Iterator<Field> it = FIELDS.iterator();
        while (it.hasNext()) {
            Field field = it.next();
            if (globalTick >= field.endTick) {
                it.remove();
                continue;
            }
            ServerLevel level = server.getLevel(field.centre.dimensionKey());
            if (level == null) continue;
            BlockPos centre = field.centre.toBlockPos();
            if (!level.hasChunkAt(centre)) continue;

            if (globalTick % 20 != 0) continue;
            markField(level, centre);
            if (!PvpManager.isPvpAllowed()) continue;

            boolean inGrace = globalTick < field.graceUntil;
            for (Player p : playersInside(level, centre)) {
                MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
                if (s == null || s.team == null || s.spectator || s.team == field.owner) continue;
                if (inGrace && field.grandfathered.contains(p.getUUID())) continue;
                p.addEffect(new MobEffectInstance(WITHER, WITHER_TICKS, WITHER_AMPLIFIER, true, false, true));
                p.addEffect(new MobEffectInstance(BLINDNESS, BLINDNESS_TICKS, 0, true, false, true));
                p.addEffect(new MobEffectInstance(HUNGER, HUNGER_TICKS, HUNGER_AMPLIFIER, true, false, true));
            }
        }
    }

    private static void markField(ServerLevel level, BlockPos centre) {
        level.sendParticles(ParticleTypes.SOUL,
                centre.getX() + 0.5, centre.getY() + 1.0, centre.getZ() + 0.5,
                40, RADIUS / 2, 1.5, RADIUS / 2, 0.0);
    }

    private static List<Player> playersInside(ServerLevel level, BlockPos centre) {
        AABB box = new AABB(centre).inflate(RADIUS);
        return level.getEntitiesOfClass(Player.class, box, p -> !p.isSpectator());
    }

    /**
     * True when any registered lifeline block is within {@link #LIFELINE_CLEARANCE}.
     * The friendly Nether Furnace counts too, per the doc's anti-trap-stacking rule.
     */
    private static boolean nearLifeline(ServerLevel level, BlockPos pos) {
        RegionsConfig config = RegionsConfig.get();
        return tooClose(level, pos, config.conduit)
                || tooClose(level, pos, config.furnace)
                || tooClose(level, pos, config.composter);
    }

    private static boolean tooClose(ServerLevel level, BlockPos pos, BlockPosData lifeline) {
        if (lifeline == null) return false;
        if (!lifeline.dimensionOrDefault().equals(level.dimension().identifier().toString())) return false;
        return lifeline.toBlockPos().distSqr(pos) <= LIFELINE_CLEARANCE * LIFELINE_CLEARANCE;
    }

    public static void clearAll() {
        FIELDS.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
