package com.regionsmoba.hud;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.lifeline.LifelineState;
import com.regionsmoba.lifeline.PlainsQuota;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.mixin.FurnaceAccessor;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumMap;
import java.util.Map;

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

    /** One lifeline bar per team: Ocean conduit, Nether furnace, Mountain blood tribute, Plains quota. */
    private final Map<BiomeTeam, ServerBossEvent> lifelines = new EnumMap<>(BiomeTeam.class);

    private String lastTimerName = "";
    private float lastTimerProgress = -1.0f;
    private BossEvent.BossBarColor lastTimerColor;

    private String lastConduitName = "";
    private float lastConduitProgress = -1.0f;
    private BossEvent.BossBarColor lastConduitColor;

    private String lastFurnaceName = "";
    private float lastFurnaceProgress = -1.0f;
    private BossEvent.BossBarColor lastFurnaceColor;

    private String lastTributeName = "";
    private float lastTributeProgress = -1.0f;
    private BossEvent.BossBarColor lastTributeColor;

    private String lastQuotaName = "";
    private float lastQuotaProgress = -1.0f;
    private BossEvent.BossBarColor lastQuotaColor;

    private LifelineBars() {
        lifelines.put(BiomeTeam.OCEAN, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_20));
        lifelines.put(BiomeTeam.NETHER, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS));
        lifelines.put(BiomeTeam.MOUNTAIN, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
        lifelines.put(BiomeTeam.PLAINS, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS));
    }

    public static LifelineBars get() {
        if (instance == null) instance = new LifelineBars();
        return instance;
    }

    /** Adds a player to the timer and to their team's lifeline bar. */
    public void addPlayer(ServerPlayer player, BiomeTeam team) {
        timer.addPlayer(player);
        ServerBossEvent bar = lifelines.get(team);
        if (bar != null) bar.addPlayer(player);
    }

    public void removePlayer(ServerPlayer player) {
        timer.removePlayer(player);
        for (ServerBossEvent bar : lifelines.values()) bar.removePlayer(player);
    }

    /** Spectators watch every lifeline plus the timer. */
    public void addSpectator(ServerPlayer player) {
        timer.addPlayer(player);
        for (ServerBossEvent bar : lifelines.values()) bar.addPlayer(player);
    }

    public void clearAll() {
        timer.removeAllPlayers();
        for (ServerBossEvent bar : lifelines.values()) bar.removeAllPlayers();
        lastTimerName = "";
        lastTimerProgress = -1.0f;
        lastTimerColor = null;
        lastConduitName = "";
        lastConduitProgress = -1.0f;
        lastConduitColor = null;
        lastFurnaceName = "";
        lastFurnaceProgress = -1.0f;
        lastFurnaceColor = null;
        lastTributeName = "";
        lastTributeProgress = -1.0f;
        lastTributeColor = null;
        lastQuotaName = "";
        lastQuotaProgress = -1.0f;
        lastQuotaColor = null;
        instance = null;
    }

    public void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive()) return;
        if (globalTick % UPDATE_INTERVAL_TICKS != 0) return;
        updateTimer();
        updateConduit();
        updateFurnace(server);
        updateBloodTribute();
        updateQuota();
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

    private void updateConduit() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.OCEAN);
        if (bar == null) return;
        LifelineState ls = LifelineState.get();
        int hp = ls.conduitHp;
        boolean dead = hp <= 0;

        String name = dead
                ? "CONDUIT DESTROYED — next death is final"
                : "Conduit — " + hp + " / " + LifelineState.CONDUIT_MAX_HP;
        float progress = BarText.progress(hp, LifelineState.CONDUIT_MAX_HP);

        if (!name.equals(lastConduitName)) {
            bar.setName(Component.literal(name).withStyle(
                    dead ? ChatFormatting.DARK_RED : ChatFormatting.AQUA));
            lastConduitName = name;
        }
        if (progress != lastConduitProgress) {
            bar.setProgress(progress);
            lastConduitProgress = progress;
        }
        BossEvent.BossBarColor color = dead ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE;
        if (color != lastConduitColor) {
            bar.setColor(color);
            lastConduitColor = color;
        }
    }

    private void updateFurnace(MinecraftServer server) {
        ServerBossEvent bar = lifelines.get(BiomeTeam.NETHER);
        if (bar == null) return;

        int litTicks = furnaceLitTicks(server);
        boolean out = litTicks <= 0;
        boolean cold = Timeline.get().phase() == MatchPhase.COLD;

        String name = out
                ? "FURNACE OUT — 1 heart / 30s"
                : "Furnace — " + BarText.mmss(litTicks) + " remaining" + (cold ? "  (2x burn)" : "");
        float progress = BarText.furnaceProgress(
                litTicks, RegionsConfig.get().furnaceBarWindowTicks);

        if (!name.equals(lastFurnaceName)) {
            bar.setName(Component.literal(name).withStyle(
                    out ? ChatFormatting.RED : ChatFormatting.GOLD));
            lastFurnaceName = name;
        }
        if (progress != lastFurnaceProgress) {
            bar.setProgress(progress);
            lastFurnaceProgress = progress;
        }
        BossEvent.BossBarColor color = out ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW;
        if (color != lastFurnaceColor) {
            bar.setColor(color);
            lastFurnaceColor = color;
        }
    }

    /** Remaining burn ticks on the registered furnace, or 0 if unlit/missing. */
    private static int furnaceLitTicks(MinecraftServer server) {
        BlockPosData ref = RegionsConfig.get().furnace;
        if (ref == null) return 0;
        ServerLevel level = server.getLevel(ref.dimensionKey());
        if (level == null) return 0;
        BlockEntity be = level.getBlockEntity(ref.toBlockPos());
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return 0;
        return ((FurnaceAccessor) (Object) furnace).regionsmoba$getLitTimeRemaining();
    }

    private void updateBloodTribute() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.MOUNTAIN);
        if (bar == null) return;

        Timeline tl = Timeline.get();
        boolean active = tl.phase() == MatchPhase.COLD;
        bar.setVisible(active);
        if (!active) return;

        boolean satisfied = LifelineState.get().bloodTributeSatisfied;
        // Timeline has no public "ticks elapsed in phase" accessor — tickInPhase is a
        // private field. secondsRemainingInPhase() (already clamped to >= 0) is the
        // public accessor for the same concept, scaled back to ticks for BarText, and
        // is already the ticks-REMAINING value (no further subtraction needed).
        int remaining = tl.secondsRemainingInPhase() * Timeline.TICKS_PER_SECOND;

        String name = satisfied
                ? "Blood Tribute paid"
                : "Blood Tribute — " + BarText.mmss(remaining) + " left";
        float progress = satisfied ? 1.0f : BarText.progress(remaining, Timeline.PHASE_TICKS);

        if (!name.equals(lastTributeName)) {
            bar.setName(Component.literal(name).withStyle(
                    satisfied ? ChatFormatting.GREEN : ChatFormatting.RED));
            lastTributeName = name;
        }
        if (progress != lastTributeProgress) {
            bar.setProgress(progress);
            lastTributeProgress = progress;
        }
        BossEvent.BossBarColor color = satisfied ? BossEvent.BossBarColor.GREEN : BossEvent.BossBarColor.RED;
        if (color != lastTributeColor) {
            bar.setColor(color);
            lastTributeColor = color;
        }
    }

    private static final int QUOTA_WARNING_TICKS = 20 * 60;

    private void updateQuota() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.PLAINS);
        if (bar == null) return;

        int paid = PlainsQuota.paid();
        int quota = PlainsQuota.currentQuota();
        boolean met = quota > 0 && paid >= quota;

        Timeline tl = Timeline.get();
        // See updateBloodTribute for why secondsRemainingInPhase() * TICKS_PER_SECOND
        // is the correct ticks-remaining substitute for the nonexistent tickInPhase().
        int toCheck = tl.secondsRemainingInPhase() * Timeline.TICKS_PER_SECOND;
        boolean urgent = !met && tl.phase() == MatchPhase.WARM && toCheck <= QUOTA_WARNING_TICKS;

        String name = met
                ? "Quota met"
                : "Quota — " + paid + " / " + quota + " emeralds";
        float progress = BarText.progress(paid, quota);

        if (!name.equals(lastQuotaName)) {
            bar.setName(Component.literal(name).withStyle(
                    urgent ? ChatFormatting.RED : ChatFormatting.GREEN));
            lastQuotaName = name;
        }
        if (progress != lastQuotaProgress) {
            bar.setProgress(progress);
            lastQuotaProgress = progress;
        }
        BossEvent.BossBarColor color = urgent ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN;
        if (color != lastQuotaColor) {
            bar.setColor(color);
            lastQuotaColor = color;
        }
    }
}
