package com.regionsmoba.match;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.debug.TestMode;
import com.regionsmoba.events.PermanentLossTracker;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Last-team-standing match-end detector. The docs don't pin down a single end
 * trigger — only /regions abort and the lifeline penalties. We add the natural
 * MOBA condition: when only one team has any non-spectator player remaining
 * (and the match has previously had ≥2 teams active), the surviving team wins
 * and the match ends.
 *
 * Polls every {@link #CHECK_INTERVAL_TICKS} ticks (1s) — cheap, and the few-tick
 * latency between final death and match-end is unnoticeable.
 */
public final class MatchEndConditions {

    public static final int CHECK_INTERVAL_TICKS = 20;

    private MatchEndConditions() {}

    public static void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive() || server == null) return;
        // Solo playtests: never auto-end or declare a winner while test mode is on.
        if (TestMode.isActive()) return;
        if (globalTick % CHECK_INTERVAL_TICKS != 0) return;

        // Only evaluate once at least 2 teams have been assigned at some point —
        // avoids ending the match seconds after start when nobody has picked yet.
        int teamsWithMembersEver = 0;
        for (BiomeTeam t : BiomeTeam.values()) {
            if (PermanentLossTracker.get().startingMembers(t) > 0) teamsWithMembersEver++;
        }
        if (teamsWithMembersEver < 2) return;

        Set<BiomeTeam> alive = EnumSet.noneOf(BiomeTeam.class);
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team == null || s.spectator) continue;
            alive.add(s.team);
        }

        if (alive.size() > 1) return;

        // 0 or 1 team alive → match end.
        if (alive.size() == 1) {
            BiomeTeam winner = alive.iterator().next();
            broadcast(server, Component.literal("Match over — " + winner.displayName() + " wins!")
                    .withStyle(winner.color(), ChatFormatting.BOLD));
            RegionsMOBA.LOGGER.info("Match end: winner={}", winner.id());
        } else {
            broadcast(server, Component.literal("Match over — no survivors.")
                    .withStyle(ChatFormatting.GRAY));
            RegionsMOBA.LOGGER.info("Match end: no survivors");
        }
        MatchManager.get().abort();
    }

    private static void broadcast(MinecraftServer server, Component msg) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
    }
}
