package com.regionsmoba.lifeline;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.economy.QuotaMath;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;

import java.util.UUID;

/**
 * Plains lifeline: an emerald quota that must be paid into the composter before
 * each cold season begins. Failure costs every living Plains player one life,
 * mirroring the Mountain Blood Tribute penalty.
 */
public final class PlainsQuota {

    private PlainsQuota() {}

    /** Non-spectator Plains players currently in the match. */
    public static int livingMembers() {
        int n = 0;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s != null && s.team == BiomeTeam.PLAINS && !s.spectator) n++;
        }
        return n;
    }

    /**
     * Quota for the season currently being worked toward. Display uses this live
     * projection; the authoritative value is recomputed at the check.
     */
    public static int currentQuota() {
        LifelineState ls = LifelineState.get();
        return QuotaMath.quotaFor(
                RegionsConfig.get().plainsQuotaBase,
                livingMembers(),
                ls.plainsQuotaSeason + 1);
    }

    public static int paid() {
        return LifelineState.get().plainsQuotaPaid;
    }

    /** Records a payment. Returns emeralds actually consumed. */
    public static int pay(int held) {
        LifelineState ls = LifelineState.get();
        int take = QuotaMath.depositAmount(held, ls.plainsQuotaPaid, currentQuota());
        ls.plainsQuotaPaid += take;
        return take;
    }
}
