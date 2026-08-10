package com.regionsmoba.lifeline;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.economy.QuotaMath;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.MatchPhase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.UUID;

/**
 * Plains lifeline: an emerald quota that must be paid into the composter before
 * each cold season begins. Failure costs every living Plains player one life,
 * mirroring the Mountain Blood Tribute penalty.
 */
public final class PlainsQuota {

    /**
     * Resistance I duration for meeting quota — one full phase (18000 ticks), so
     * the buff expires exactly as the next quota check lands. Meeting quota every
     * season therefore keeps it up continuously.
     */
    public static final int QUOTA_REWARD_TICKS = 18000;

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

    /**
     * Evaluates the quota on the WARM -> COLD edge, then resets the counter.
     * Short payment costs every non-spectator Plains player one life.
     */
    public static void onPhaseChange(MinecraftServer server, MatchPhase from, MatchPhase to) {
        if (to != MatchPhase.COLD) return;
        LifelineState ls = LifelineState.get();
        ls.plainsQuotaSeason++;

        int quota = QuotaMath.quotaFor(
                RegionsConfig.get().plainsQuotaBase, livingMembers(), ls.plainsQuotaSeason);
        int paid = ls.plainsQuotaPaid;
        ls.plainsQuotaPaid = 0;

        if (quota <= 0) return;

        boolean met = paid >= quota;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.PLAINS || s.spectator) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (met) {
                p.addEffect(new MobEffectInstance(
                        MobEffects.DAMAGE_RESISTANCE, QUOTA_REWARD_TICKS, 0, true, false, true));
                p.sendSystemMessage(Component.literal(
                                "Emerald Quota met — " + paid + " / " + quota
                                        + ". Resistance I for 15 minutes.")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                Lives.loseLife(p, "Emerald Quota unmet");
            }
        }
    }
}
