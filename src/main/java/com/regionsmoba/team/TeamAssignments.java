package com.regionsmoba.team;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-match team and class state for every match player. Singleton, in-memory only,
 * reset on match end / server stop.
 *
 * Capacity rule (per docs/src-md/getting-started/joining-a-game.md):
 *   "No biome can hold more than 1/4 of total players. Overflow is redistributed."
 *
 * "Overflow is redistributed" is interpreted as: a join attempt to a full team is
 * rejected with a clear message — the player picks again. Total players is the
 * total joiner count fixed at match start; cap = ceil(total / 4) so 4-player
 * matches give 1 per team and round-up matches let an extra player into a team.
 */
public final class TeamAssignments {

    private static final TeamAssignments INSTANCE = new TeamAssignments();

    public static TeamAssignments get() {
        return INSTANCE;
    }

    private final Map<UUID, MatchPlayerState> states = new HashMap<>();
    private int totalPlayers;

    private TeamAssignments() {}

    /** Initialise tracking for a new match's joiner set. */
    public void reset(java.util.Collection<UUID> joiners) {
        states.clear();
        for (UUID id : joiners) states.put(id, new MatchPlayerState());
        totalPlayers = joiners.size();
    }

    /**
     * Ensures one player has match state, without disturbing anyone else.
     * Distinct from {@link #reset(java.util.Collection)}, which clears everything.
     * No-op if the player already has state.
     *
     * Keeps {@code totalPlayers} in step so it never diverges from
     * {@code states.size()} — {@code teamCap()} is derived from it.
     */
    public void join(UUID player) {
        if (states.putIfAbsent(player, new MatchPlayerState()) == null) {
            totalPlayers++;
        }
    }

    public void clearAll() {
        states.clear();
        totalPlayers = 0;
    }

    public MatchPlayerState state(UUID player) {
        return states.get(player);
    }

    public boolean isInMatch(UUID player) {
        return states.containsKey(player);
    }

    /** Current member count for each team. */
    public Map<BiomeTeam, Integer> teamCounts() {
        Map<BiomeTeam, Integer> out = new EnumMap<>(BiomeTeam.class);
        for (BiomeTeam t : BiomeTeam.values()) out.put(t, 0);
        for (MatchPlayerState s : states.values()) {
            if (s.team != null) out.merge(s.team, 1, Integer::sum);
        }
        return out;
    }

    /** Per-team cap = ceil(totalPlayers / 4). Caller is expected to enforce on join. */
    public int teamCap() {
        if (totalPlayers <= 0) return Integer.MAX_VALUE;
        return (totalPlayers + 3) / 4;
    }

    public int totalPlayers() {
        return totalPlayers;
    }

    /** Result of a team-assignment attempt. */
    public enum AssignResult {
        OK,
        UNKNOWN_PLAYER,
        TEAM_FULL,
        ALREADY_ASSIGNED
    }

    public AssignResult assignTeam(UUID player, BiomeTeam team) {
        MatchPlayerState s = states.get(player);
        if (s == null) return AssignResult.UNKNOWN_PLAYER;
        if (s.team != null) return AssignResult.ALREADY_ASSIGNED;
        int current = teamCounts().getOrDefault(team, 0);
        if (current >= teamCap()) return AssignResult.TEAM_FULL;
        s.team = team;
        return AssignResult.OK;
    }

    public boolean assignClass(UUID player, BiomeClass biomeClass) {
        MatchPlayerState s = states.get(player);
        if (s == null || s.team == null) return false;
        if (biomeClass.team() != s.team) return false;
        s.biomeClass = biomeClass;
        return true;
    }
}
