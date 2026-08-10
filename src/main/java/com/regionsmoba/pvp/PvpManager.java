package com.regionsmoba.pvp;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Decides whether player-vs-player damage is allowed at a moment in time.
 *
 * Rules from docs/src-md/gameplay/timeline.md and trial-chamber.md:
 *   - Outside the trial chamber: PVP enabled iff phase == COLD or after Cold 2 end.
 *   - Inside the trial chamber: PVP always enabled, regardless of phase.
 *
 * The chamber check uses the target's position so a defender hiding inside the
 * chamber stays vulnerable; if the target is inside, PVP applies.
 */
public final class PvpManager {

    /** Operator override for /regions debug pvp. null = follow timeline; ON/OFF/PERMANENT pin the state. */
    public enum Override { ON, OFF, PERMANENT }

    private static Override override;

    private PvpManager() {}

    public static void setOverride(Override value) {
        override = value;
    }

    public static Override override() {
        return override;
    }

    public static boolean isPvpAllowed() {
        if (override == Override.ON || override == Override.PERMANENT) return true;
        if (override == Override.OFF) return false;
        if (!MatchManager.get().isActive()) return true; // outside a match, vanilla rules
        Timeline t = Timeline.get();
        if (!t.isRunning()) return true;
        return t.phase() == MatchPhase.COLD || t.isPvpPermanent();
    }

    /** Returns true if this player-vs-player damage should be allowed right now. */
    public static boolean isPvpAllowedFor(Player attacker, Player target) {
        if (attacker == target) return true;
        if (isInTrialChamber(target) || isInTrialChamber(attacker)) return true;
        // Cold seasons and permanent PVP open everything, everywhere. Territory
        // governs only the peace — home-field advantage is a warm-phase thing.
        if (isPvpAllowed()) return true;

        long now = tickOf(attacker);

        // Home defender. The region containing the VICTIM decides, not the
        // attacker: that lets a defender shoot inward from just outside their
        // own border, and correctly denies an intruder who swings while
        // standing on the defender's ground.
        BiomeTeam owner = regionOwnerAt(target);
        if (owner != null && teamOf(attacker) == owner) {
            Retaliation.grant(target.getUUID(), attacker.getUUID(), now);
            return true;
        }

        // Struck first: return fire, against that one attacker only.
        return Retaliation.mayStrike(attacker.getUUID(), target.getUUID(), now);
    }

    /** The team whose registered biome bounds contain this player, or null. */
    private static BiomeTeam regionOwnerAt(Player player) {
        String dimension = player.level().dimension().location().toString();
        for (BiomeTeam team : BiomeTeam.values()) {
            Area area = RegionsConfig.get().biomeBounds(team);
            if (area == null || !area.isComplete()) continue;
            if (!area.dimension().equals(dimension)) continue;
            if (area.contains(player.getX(), player.getY(), player.getZ())) return team;
        }
        return null;
    }

    /** Null for anyone not in the match, so non-participants never gain defender rights. */
    private static BiomeTeam teamOf(Player player) {
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        return state == null ? null : state.team;
    }

    private static long tickOf(Player player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0L : server.getTickCount();
    }

    private static boolean isInTrialChamber(Entity entity) {
        Area chamber = RegionsConfig.get().chamberBounds;
        if (chamber == null || !chamber.isComplete()) return false;
        // Chamber is a single dimension; compare the entity's dimension against the chamber's.
        if (!entity.level().dimension().location().toString().equals(chamber.dimension())) return false;
        return chamber.contains(entity.getX(), entity.getY(), entity.getZ());
    }
}
