package com.regionsmoba.hud;

import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * Owns every boss bar the mod shows. Bars are team-scoped: a player carries the
 * global timer plus their own team's lifeline. Updates run on a 10-tick cadence
 * and only push when a rendered value changed, because every setter sends a
 * packet to every viewer.
 */
public final class LifelineBars {

    private static final int UPDATE_INTERVAL_TICKS = 10;

    private static LifelineBars instance;

    private final ServerBossEvent timer = new ServerBossEvent(
            Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    private String lastTimerName = "";
    private float lastTimerProgress = -1.0f;
    private BossEvent.BossBarColor lastTimerColor;

    private LifelineBars() {}

    public static LifelineBars get() {
        if (instance == null) instance = new LifelineBars();
        return instance;
    }

    /** Adds a player to the timer and to their team's lifeline bar. */
    public void addPlayer(ServerPlayer player, BiomeTeam team) {
        timer.addPlayer(player);
    }

    public void removePlayer(ServerPlayer player) {
        timer.removePlayer(player);
    }

    public void clearAll() {
        timer.removeAllPlayers();
        lastTimerName = "";
        lastTimerProgress = -1.0f;
        lastTimerColor = null;
        instance = null;
    }

    public void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive()) return;
        if (globalTick % UPDATE_INTERVAL_TICKS != 0) return;
        updateTimer();
    }

    private void updateTimer() {
        Timeline tl = Timeline.get();
        MatchPhase phase = tl.phase();
        // Timeline has no public "ticks elapsed in phase" accessor — tickInPhase is a
        // private field. secondsRemainingInPhase() is the public accessor for the same
        // concept (already clamped to >= 0), scaled back to ticks for BarText.
        int remaining = tl.secondsRemainingInPhase() * Timeline.TICKS_PER_SECOND;

        String name = (phase == MatchPhase.COLD ? "Cold" : "Warm")
                + " " + seasonNumber(tl) + " — " + BarText.mmss(remaining)
                + (tl.isPvpPermanent() ? "  ·  PvP permanent" : "");
        float progress = BarText.progress(remaining, Timeline.PHASE_TICKS);

        if (!name.equals(lastTimerName)) {
            timer.setName(Component.literal(name).withStyle(ChatFormatting.WHITE));
            lastTimerName = name;
        }
        if (progress != lastTimerProgress) {
            timer.setProgress(progress);
            lastTimerProgress = progress;
        }

        BossEvent.BossBarColor color = phase == MatchPhase.COLD
                ? BossEvent.BossBarColor.BLUE
                : BossEvent.BossBarColor.YELLOW;
        if (color != lastTimerColor) {
            timer.setColor(color);
            lastTimerColor = color;
        }
    }

    /** 1-based index of the current warm or cold phase. */
    private static int seasonNumber(Timeline tl) {
        int phasesElapsed = tl.totalTicks() / Timeline.PHASE_TICKS;
        return phasesElapsed / 2 + 1;
    }
}
