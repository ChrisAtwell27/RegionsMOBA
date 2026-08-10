package com.regionsmoba.lifeline;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Mountain lifeline.
 *
 * Per docs/src-md/teams/mountain.md and docs/src-md/gameplay/cold-season.md:
 *   The Blood Tribute timer runs for the full 15 minutes of each cold season.
 *   Satisfied by:
 *     - Any enemy kill by a mountain player, anywhere on the map.
 *     - Any enemy death anywhere inside the mountain biome, regardless of cause.
 *   NOT satisfied by killing teammates or by mountain player deaths.
 *   On expiry without satisfaction: every living dwarf loses 1 life.
 *
 * Wiring:
 *   - {@link #onPhaseChange} is called by Timeline (via MatchManager) on each
 *     transition. WARM→COLD resets the tribute; COLD→WARM evaluates and applies
 *     the penalty if needed.
 *   - {@link #register} hooks ServerLivingEntityEvents.AFTER_DEATH for satisfaction
 *     tracking (mountain player kills + deaths inside mountain biome).
 */
public final class BloodTributeLifeline {

    private BloodTributeLifeline() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(BloodTributeLifeline::onDeath);
    }

    /** /regions debug bloodtribute — set the satisfied flag manually. */
    public static void debugSatisfy() {
        LifelineState.get().bloodTributeSatisfied = true;
    }

    public static void debugReset() {
        LifelineState.get().bloodTributeSatisfied = false;
    }

    /** /regions debug bloodtribute fail — apply the unsatisfied penalty now without ending the cold season. */
    public static void debugFail(MinecraftServer server) {
        evaluateTribute(server);
    }

    /** Hook called from MatchManager when Timeline reports a phase change. */
    public static void onPhaseChange(Timeline.PhaseChange change, MinecraftServer server) {
        if (change.to() == MatchPhase.COLD) {
            startTribute(server);
        } else {
            // COLD → WARM: evaluate satisfaction and apply penalty if missed.
            evaluateTribute(server);
        }
    }

    private static void startTribute(MinecraftServer server) {
        LifelineState ls = LifelineState.get();
        ls.bloodTributeSatisfied = false;
        ls.bloodTributeStartTick = server != null ? server.getTickCount() : -1;
        broadcastToMountain(server, Component.literal(
                "Cold season: Blood Tribute timer started. Hunt or watch for enemy deaths in your biome.")
                .withStyle(ChatFormatting.DARK_RED));
    }

    private static void evaluateTribute(MinecraftServer server) {
        LifelineState ls = LifelineState.get();
        if (ls.bloodTributeSatisfied) {
            broadcastToMountain(server, Component.literal("Blood Tribute satisfied — no penalty.")
                    .withStyle(ChatFormatting.GREEN));
            ls.bloodTributeStartTick = -1;
            return;
        }
        broadcastToMountain(server, Component.literal(
                "Blood Tribute UNFULFILLED — every dwarf loses 1 life.")
                .withStyle(ChatFormatting.DARK_RED));
        if (server == null) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.MOUNTAIN || s.spectator) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) Lives.loseLife(p, "Blood Tribute unmet");
        }
        ls.bloodTributeStartTick = -1;
    }

    /** Death event hook — checks for tribute satisfaction. */
    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (!MatchManager.get().isActive()) return;
        if (Timeline.get().phase() != MatchPhase.COLD) return;
        if (LifelineState.get().bloodTributeSatisfied) return;
        if (!(entity instanceof Player victim)) return;

        MatchPlayerState victimState = TeamAssignments.get().state(victim.getUUID());
        if (victimState == null || victimState.team == null) return;
        if (victimState.team == BiomeTeam.MOUNTAIN) return; // mountain deaths don't count

        // Path 1 — enemy killed by a mountain player anywhere on the map.
        Player attacker = attackerOf(source);
        if (attacker != null) {
            MatchPlayerState attackerState = TeamAssignments.get().state(attacker.getUUID());
            if (attackerState != null && attackerState.team == BiomeTeam.MOUNTAIN) {
                satisfy("kill by " + attacker.getGameProfile().getName());
                return;
            }
        }

        // Path 2 — enemy died inside the mountain biome, regardless of cause.
        Area mountainBounds = RegionsConfig.get().biomeBounds(BiomeTeam.MOUNTAIN);
        if (mountainBounds != null && mountainBounds.isComplete()
                && mountainBounds.dimension().equals(victim.level().dimension().location().toString())
                && mountainBounds.contains(victim.getX(), victim.getY(), victim.getZ())) {
            satisfy("death inside mountain biome");
        }
    }

    private static void satisfy(String reason) {
        LifelineState.get().bloodTributeSatisfied = true;
        RegionsMOBA.LOGGER.info("Blood Tribute satisfied: {}", reason);
        // Broadcast to mountain on next /regions status check; avoid spamming chat per kill.
    }

    private static Player attackerOf(DamageSource source) {
        if (source.getEntity() instanceof Player p) return p;
        if (source.getDirectEntity() instanceof Player p) return p;
        return null;
    }

    private static void broadcastToMountain(MinecraftServer server, Component msg) {
        if (server == null) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.MOUNTAIN) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
