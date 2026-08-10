package com.regionsmoba.pvp;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
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
        return isPvpAllowed();
    }

    private static boolean isInTrialChamber(Entity entity) {
        Area chamber = RegionsConfig.get().chamberBounds;
        if (chamber == null || !chamber.isComplete()) return false;
        // Chamber is a single dimension; compare the entity's dimension against the chamber's.
        if (!entity.level().dimension().location().toString().equals(chamber.dimension())) return false;
        return chamber.contains(entity.getX(), entity.getY(), entity.getZ());
    }
}
