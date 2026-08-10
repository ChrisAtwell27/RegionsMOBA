package com.regionsmoba.classes.impl;

import com.mojang.datafixers.util.Pair;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.repair.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plains Spy — Vanish and Flee.
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Vanish — sneaking while still for ~2 seconds turns the Spy fully invisible,
 *     armor and held item included. They may move ~3 blocks before it breaks.
 *     Any action (breaking a block, using a bow, eating) unvanishes instantly,
 *     as does being hit or right-clicked by an enemy.
 *   Flee — right-click to spawn a decoy at the Spy's position and gain 6
 *     seconds of full invisibility. The clone wanders and punches nearby
 *     enemies. 40-second cooldown. Cannot be used within 20 blocks of an enemy
 *     lifeline block (Ocean's Conduit, Nether's Furnace, Plains' Composter);
 *     Mountain has none, so the mountain biome is unrestricted.
 *
 * Hiding the armor is done by sending every *other* client an empty
 * {@link ClientboundSetEquipmentPacket} for the Spy, rather than by moving the
 * real items anywhere. The server-side inventory is never touched, so there is
 * no way for a crash, disconnect, or death mid-vanish to eat someone's gear.
 */
public final class SpyAbility {

    public static final int VANISH_CHARGE_TICKS = 2 * 20;
    public static final double VANISH_MOVE_ALLOWANCE = 3.0;

    public static final int FLEE_INVISIBILITY_TICKS = 6 * 20;
    public static final int FLEE_COOLDOWN_SECONDS = 40;
    public static final double FLEE_LIFELINE_CLEARANCE = 20.0;
    public static final int DECOY_LIFETIME_TICKS = 6 * 20;

    private static final String FLEE_COOLDOWN_ID = "spy:flee";

    private static final Holder<MobEffect> INVISIBILITY = MobEffects.INVISIBILITY;

    private static final class VanishState {
        /** Where the Spy was when the sneak charge started. */
        Vec3 anchor;
        long stillSince = -1;
        boolean vanished;
    }

    private static final Map<UUID, VanishState> VANISH = new HashMap<>();
    /** Decoy entity id → tick at which it despawns. */
    private static final Map<UUID, Long> DECOYS = new HashMap<>();

    private SpyAbility() {}

    public static boolean isVanished(UUID player) {
        VanishState state = VANISH.get(player);
        return state != null && state.vanished;
    }

    // ---- Vanish ----

    /**
     * Drives the sneak-charge, the movement allowance, and the equipment
     * masking. Called once per tick for every Spy.
     */
    public static void tick(ServerPlayer spy, long globalTick) {
        VanishState state = VANISH.computeIfAbsent(spy.getUUID(), k -> new VanishState());

        if (!spy.isShiftKeyDown()) {
            if (state.vanished) unvanish(spy, "stopped sneaking");
            state.stillSince = -1;
            state.anchor = null;
            return;
        }

        if (state.anchor == null) {
            state.anchor = spy.position();
            state.stillSince = globalTick;
            return;
        }

        double drift = spy.position().distanceTo(state.anchor);
        if (state.vanished) {
            // Already hidden: the Spy may drift up to the allowance before the
            // vanish breaks.
            if (drift > VANISH_MOVE_ALLOWANCE) {
                unvanish(spy, "moved too far");
            } else {
                refreshInvisibility(spy);
            }
            return;
        }

        // Still charging: any real movement restarts the timer.
        if (drift > 0.15) {
            state.anchor = spy.position();
            state.stillSince = globalTick;
            return;
        }
        if (globalTick - state.stillSince >= VANISH_CHARGE_TICKS) {
            vanish(spy, state);
        }
    }

    /**
     * Refresh window for the vanish invisibility. Deliberately short and
     * re-applied every tick rather than infinite: an infinite effect would
     * survive a disconnect in the player's saved data, leaving a Spy who logged
     * out mid-vanish permanently invisible on their next login.
     */
    public static final int VANISH_REFRESH_TICKS = 40;

    private static void vanish(ServerPlayer spy, VanishState state) {
        state.vanished = true;
        state.anchor = spy.position();
        refreshInvisibility(spy);
        maskEquipment(spy, true);
        tell(spy, "Vanished.", ChatFormatting.DARK_GRAY);
    }

    private static void refreshInvisibility(ServerPlayer spy) {
        spy.addEffect(new MobEffectInstance(INVISIBILITY, VANISH_REFRESH_TICKS, 0, true, false, false));
    }

    /** Breaks the vanish. Safe to call when the Spy is not vanished. */
    public static void unvanish(ServerPlayer spy, String reason) {
        VanishState state = VANISH.get(spy.getUUID());
        if (state == null || !state.vanished) return;
        state.vanished = false;
        state.stillSince = -1;
        state.anchor = null;
        spy.removeEffect(INVISIBILITY);
        maskEquipment(spy, false);
        tell(spy, "Revealed — " + reason + ".", ChatFormatting.GRAY);
    }

    /**
     * Shows or hides the Spy's gear for everyone else. The Spy's own client is
     * skipped so they still see what they are holding.
     */
    private static void maskEquipment(ServerPlayer spy, boolean hide) {
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack shown = hide ? ItemStack.EMPTY : spy.getItemBySlot(slot);
            slots.add(Pair.of(slot, shown.copy()));
        }
        MinecraftServer server = spy.level().getServer();
        if (server == null) return;
        ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(spy.getId(), slots);
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == spy) continue;
            other.connection.send(packet);
        }
    }

    // ---- Flee ----

    /**
     * Flee has no ability item, so it is cast by right-clicking while holding
     * the kit's golden sword. Gating on the sword matters: a bare right-click
     * trigger would swallow every attempt to eat or drink whenever Flee happened
     * to be off cooldown. A sword does nothing on right-click in vanilla, so
     * claiming that click costs the Spy nothing.
     */
    public static boolean tryRightClick(ServerPlayer spy) {
        if (!spy.getMainHandItem().is(Items.GOLDEN_SWORD)) return false;
        if (!Cooldowns.get().ready(spy, FLEE_COOLDOWN_ID)) {
            tell(spy, "Flee on cooldown ("
                    + Cooldowns.get().remainingSeconds(spy, FLEE_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return false; // don't swallow the click — the Spy still needs their items
        }
        ServerLevel level = spy.serverLevel();
        if (nearLifeline(level, spy.blockPosition())) {
            tell(spy, "Too close to an enemy lifeline to Flee.", ChatFormatting.RED);
            return false;
        }

        spawnDecoy(level, spy);
        spy.addEffect(new MobEffectInstance(INVISIBILITY, FLEE_INVISIBILITY_TICKS, 0, true, false, false));
        maskEquipment(spy, true);

        VanishState state = VANISH.computeIfAbsent(spy.getUUID(), k -> new VanishState());
        state.vanished = true;
        state.anchor = spy.position();

        Cooldowns.get().set(spy, FLEE_COOLDOWN_ID, FLEE_COOLDOWN_SECONDS);
        tell(spy, "Flee — decoy deployed.", ChatFormatting.DARK_GRAY);
        return true;
    }

    private static void spawnDecoy(ServerLevel level, ServerPlayer spy) {
        MinecraftServer server = level.getServer();
        Entity decoy = EntityType.ZOMBIE.spawn(level, spy.blockPosition(), MobSpawnType.MOB_SUMMONED);
        if (decoy == null) return;
        decoy.setCustomName(Component.literal(spy.getGameProfile().getName()));
        decoy.setCustomNameVisible(true);
        // Tracked so match-end teardown despawns it even if the timer never runs.
        ModEntities.track(decoy);
        long now = server != null ? server.getTickCount() : 0L;
        DECOYS.put(decoy.getUUID(), now + DECOY_LIFETIME_TICKS);
    }

    /** Expires decoys and the Flee invisibility window. */
    public static void tickGlobal(MinecraftServer server, long globalTick) {
        if (DECOYS.isEmpty() || server == null) return;
        DECOYS.entrySet().removeIf(entry -> {
            if (globalTick < entry.getValue()) return false;
            for (ServerLevel level : server.getAllLevels()) {
                Entity decoy = level.getEntity(entry.getKey());
                if (decoy != null) {
                    decoy.discard();
                    break;
                }
            }
            return true;
        });
    }

    private static boolean nearLifeline(ServerLevel level, BlockPos pos) {
        RegionsConfig config = RegionsConfig.get();
        return tooClose(level, pos, config.conduit)
                || tooClose(level, pos, config.furnace)
                || tooClose(level, pos, config.composter);
    }

    private static boolean tooClose(ServerLevel level, BlockPos pos, BlockPosData lifeline) {
        if (lifeline == null) return false;
        if (!lifeline.dimensionOrDefault().equals(level.dimension().location().toString())) return false;
        return lifeline.toBlockPos().distSqr(pos) <= FLEE_LIFELINE_CLEARANCE * FLEE_LIFELINE_CLEARANCE;
    }

    public static void clearForPlayer(UUID player) {
        VANISH.remove(player);
    }

    public static void clearAll() {
        VANISH.clear();
        DECOYS.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
