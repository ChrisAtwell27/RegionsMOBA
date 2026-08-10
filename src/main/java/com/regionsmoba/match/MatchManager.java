package com.regionsmoba.match;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.chamber.TrialChamber;
import com.regionsmoba.classes.AbilityHooks;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.DamageModifiers;
import com.regionsmoba.classes.impl.BardAbility;
import com.regionsmoba.classes.impl.BerserkerAbility;
import com.regionsmoba.classes.impl.BloodmageAbility;
import com.regionsmoba.classes.impl.DefenderAbility;
import com.regionsmoba.classes.impl.ImmobilizerAbility;
import com.regionsmoba.classes.impl.LumberjackAbility;
import com.regionsmoba.classes.impl.MinerAbility;
import com.regionsmoba.classes.impl.NeptuneAbility;
import com.regionsmoba.classes.impl.SpyAbility;
import com.regionsmoba.classes.impl.TinkererAbility;
import com.regionsmoba.classes.impl.TransporterAbility;
import com.regionsmoba.classes.impl.WarriorAbility;
import com.regionsmoba.classes.impl.WizardAbility;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.deposit.DepositTracker;
import com.regionsmoba.events.MassEventTargeting;
import com.regionsmoba.events.PermanentLossTracker;
import com.regionsmoba.hud.LifelineBars;
import com.regionsmoba.lifeline.LifelineState;
import com.regionsmoba.lobby.LobbyFlow;
import com.regionsmoba.lobby.NationsLobbyRegistry;
import com.regionsmoba.protection.BuildMode;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.repair.WorldSnapshot;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.timeline.Timeline;
import com.regionsmoba.trader.TraderRespawn;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory holder of the current match. Not persisted across server restarts —
 * a server crash mid-match drops the match back to DORMANT on next boot.
 *
 * Owns one {@link WorldSnapshot} per "match dimension" (any dimension referenced
 * by a registration). Cross-dimension setups (overworld + nether biome, etc.) are
 * fully supported — every involved dimension gets its own snapshot and gets
 * repaired independently on match end.
 */
public final class MatchManager {

    private static MatchManager instance;

    private MatchState state = MatchState.DORMANT;
    private MinecraftServer server;
    private final Map<ResourceKey<Level>, WorldSnapshot> snapshots = new HashMap<>();
    private Set<ResourceKey<Level>> matchDimensions = Set.of();
    private long matchStartTick = -1;
    /** Players in the current match (insertion-ordered for stable teleport order). */
    private final Set<UUID> matchPlayers = new LinkedHashSet<>();

    private MatchManager() {}

    public static MatchManager get() {
        if (instance == null) instance = new MatchManager();
        return instance;
    }

    public MatchState state() {
        return state;
    }

    public boolean isActive() {
        return state == MatchState.ACTIVE;
    }

    public boolean isDormant() {
        return state == MatchState.DORMANT;
    }

    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer server() {
        return server;
    }

    /**
     * Returns the snapshot for the given level if it's a match dimension,
     * lazily creating one. Returns null if not a match dimension or no match active.
     */
    public WorldSnapshot snapshot(ServerLevel level) {
        if (!isActive()) return null;
        ResourceKey<Level> key = level.dimension();
        if (!matchDimensions.contains(key)) return null;
        return snapshots.computeIfAbsent(key, k -> new WorldSnapshot());
    }

    public boolean isMatchDimension(ServerLevel level) {
        return matchDimensions.contains(level.dimension());
    }

    public Set<ResourceKey<Level>> matchDimensions() {
        return matchDimensions;
    }

    /** Number of game ticks since match start, or -1 if not active. */
    public long elapsedTicks() {
        if (state != MatchState.ACTIVE || server == null) return -1;
        return server.getTickCount() - matchStartTick;
    }

    public Set<UUID> matchPlayers() {
        return matchPlayers;
    }

    /**
     * Transitions DORMANT/LOBBY → ACTIVE with no joiners. Used by /regions start
     * (manual) when an operator wants to start a match without the sign flow.
     */
    public void start(MinecraftServer server) {
        startInternal(server, Set.of());
    }

    /**
     * Starts a match with the given joiners and teleports each one to the lobby
     * spawn. Used by the [Nations] sign auto-trigger.
     */
    public void startWithJoiners(MinecraftServer server, Collection<UUID> joiners) {
        startInternal(server, joiners);
    }

    private void startInternal(MinecraftServer server, Collection<UUID> joiners) {
        if (state == MatchState.ACTIVE) {
            RegionsMOBA.LOGGER.warn("MatchManager.start() called while already ACTIVE");
            return;
        }
        this.server = server;
        this.snapshots.clear();
        this.matchDimensions = computeMatchDimensions();
        this.matchStartTick = server.getTickCount();
        this.matchPlayers.clear();
        this.matchPlayers.addAll(joiners);
        this.state = MatchState.ACTIVE;
        // Sign queues are now in the match — clear them so subsequent right-clicks
        // build a queue for the next match.
        NationsLobbyRegistry.get().clearAll();
        TeamAssignments.get().reset(matchPlayers);
        LifelineState.get().resetAll();
        LifelineBars.get().clearAll();
        DepositTracker.get().resetAll();
        PermanentLossTracker.get().resetAll();
        MassEventTargeting.get().resetAll();
        TrialChamber.get().resetAll();
        TrialChamber.get().onMatchStart(server.getTickCount());
        Cooldowns.get().clearAll();
        AbilityHooks.clearAll();
        WarriorAbility.clearAll();
        BardAbility.clearAll();
        BerserkerAbility.clearAll();
        SpyAbility.clearAll();
        WizardAbility.clearAll();
        ImmobilizerAbility.clearAll();
        BloodmageAbility.clearAll();
        LumberjackAbility.clearAll();
        MinerAbility.clearAll();
        TinkererAbility.clearAll();
        NeptuneAbility.clearAll();
        TransporterAbility.clearAll();
        com.regionsmoba.classes.impl.ArcherAbility.clearAll();
        com.regionsmoba.classes.impl.BloodmageTerraform.clearAll();
        com.regionsmoba.classes.impl.DefenderAlertItem.clearAll();
        com.regionsmoba.classes.impl.BuilderCache.clearAll();
        com.regionsmoba.classes.impl.EnchanterAbility.clearAll();
        com.regionsmoba.classes.impl.AlchemistCauldron.clearAll();
        com.regionsmoba.timeline.DrownedSpawning.clearAll();
        Timeline.get().start();
        teleportJoinersToLobby();
        LobbyFlow.onMatchStart(server, matchPlayers);
        RegionsMOBA.LOGGER.info("Match started: {} player(s) across {} dimension(s): {}",
                matchPlayers.size(), matchDimensions.size(), matchDimensions);
    }

    private void teleportJoinersToLobby() {
        if (matchPlayers.isEmpty() || server == null) return;
        BlockPosData lobby = RegionsConfig.get().lobbySpawn;
        if (lobby == null) {
            RegionsMOBA.LOGGER.warn("No lobby spawn registered — joiners stay where they are.");
            broadcastToMatch("Match started, but no lobby spawn is registered.", ChatFormatting.YELLOW);
            return;
        }
        ServerLevel level = server.getLevel(lobby.dimensionKey());
        if (level == null) {
            RegionsMOBA.LOGGER.warn("Lobby dimension {} is not loaded — joiners stay where they are.",
                    lobby.dimensionOrDefault());
            return;
        }
        double x = lobby.x() + 0.5;
        double y = lobby.y();
        double z = lobby.z() + 0.5;
        for (UUID id : matchPlayers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            p.teleportTo(level, x, y, z, java.util.Set.<RelativeMovement>of(), p.getYRot(), p.getXRot());
        }
    }

    /**
     * Strips the transient per-class attribute modifiers from every match player
     * still online. Call before {@code matchPlayers.clear()} — that set is the
     * only handle on who was in the match.
     */
    private void clearClassAttributes() {
        if (server == null) return;
        for (UUID id : matchPlayers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            DamageModifiers.clear(p);
            DefenderAbility.clearBonusHp(p);
            BerserkerAbility.clearStack(p);
            BloodmageAbility.clearCurse(p);
        }
    }

    private void broadcastToMatch(String msg, ChatFormatting color) {
        if (server == null) return;
        Component c = Component.literal(msg).withStyle(color);
        for (UUID id : matchPlayers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(c);
        }
    }

    private Set<ResourceKey<Level>> computeMatchDimensions() {
        Set<ResourceKey<Level>> dims = new HashSet<>();
        for (String dim : RegionsConfig.get().referencedDimensions()) {
            ResourceLocation id = ResourceLocation.tryParse(dim);
            if (id != null) {
                dims.add(ResourceKey.create(Registries.DIMENSION, id));
            }
        }
        // Default to overworld if nothing is registered yet.
        if (dims.isEmpty() && server != null) {
            dims.add(Level.OVERWORLD);
        }
        return dims;
    }

    /**
     * Ends the match: despawns mod-spawned entities and dropped items in every
     * match dimension, then runs map repair against each per-dimension snapshot.
     * Returns aggregate restored-block count.
     */
    public WorldSnapshot.RestoreResult abort() {
        if (state == MatchState.DORMANT) {
            RegionsMOBA.LOGGER.info("MatchManager.abort() called while DORMANT — no-op");
            return new WorldSnapshot.RestoreResult(0);
        }
        state = MatchState.ENDED;

        int despawned = 0;
        if (server != null) {
            for (ResourceKey<Level> key : matchDimensions) {
                ServerLevel level = server.getLevel(key);
                if (level != null) despawned += ModEntities.despawnInLevel(level);
            }
        }
        ModEntities.clearAll();

        int totalRestored = 0;
        if (server != null) {
            for (Map.Entry<ResourceKey<Level>, WorldSnapshot> e : snapshots.entrySet()) {
                ServerLevel level = server.getLevel(e.getKey());
                if (level != null) {
                    totalRestored += e.getValue().restore(level).blocksRestored();
                }
            }
        }
        snapshots.clear();
        // Class attribute modifiers live on the ServerPlayer, and the players stay
        // online past match end — strip them here or the Warrior's +1 melee and the
        // Defender's conduit HP bonus follow them into the lobby.
        clearClassAttributes();
        matchPlayers.clear();
        TeamAssignments.get().clearAll();
        LifelineState.get().resetAll();
        LifelineBars.get().clearAll();
        DepositTracker.get().resetAll();
        PermanentLossTracker.get().resetAll();
        MassEventTargeting.get().resetAll();
        TrialChamber.get().resetAll();
        Cooldowns.get().clearAll();
        AbilityHooks.clearAll();
        WarriorAbility.clearAll();
        BardAbility.clearAll();
        BerserkerAbility.clearAll();
        SpyAbility.clearAll();
        WizardAbility.clearAll();
        ImmobilizerAbility.clearAll();
        BloodmageAbility.clearAll();
        LumberjackAbility.clearAll();
        MinerAbility.clearAll();
        TinkererAbility.clearAll();
        NeptuneAbility.clearAll();
        TransporterAbility.clearAll();
        com.regionsmoba.classes.impl.ArcherAbility.clearAll();
        com.regionsmoba.classes.impl.BloodmageTerraform.clearAll();
        com.regionsmoba.classes.impl.DefenderAlertItem.clearAll();
        com.regionsmoba.classes.impl.BuilderCache.clearAll();
        com.regionsmoba.classes.impl.EnchanterAbility.clearAll();
        com.regionsmoba.classes.impl.AlchemistCauldron.clearAll();
        com.regionsmoba.timeline.DrownedSpawning.clearAll();
        Timeline.get().stop();

        int tradersRespawned = TraderRespawn.respawnAll(server);
        RegionsMOBA.LOGGER.info("Match ended; restored {} block(s), despawned {} entity/item(s), respawned {} trader(s)",
                totalRestored, despawned, tradersRespawned);
        matchDimensions = Set.of();
        matchStartTick = -1;
        state = MatchState.DORMANT;
        return new WorldSnapshot.RestoreResult(totalRestored);
    }

    /**
     * Run map repair against every dimension's snapshot without ending the match.
     * Used by /regions debug map repair to verify the restore behaves as expected.
     */
    public WorldSnapshot.RestoreResult debugRepairNow() {
        if (!isActive() || server == null) return new WorldSnapshot.RestoreResult(0);
        int total = 0;
        for (Map.Entry<ResourceKey<Level>, WorldSnapshot> e : snapshots.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey());
            if (level != null) total += e.getValue().restore(level).blocksRestored();
        }
        return new WorldSnapshot.RestoreResult(total);
    }

    /** Called from ServerLifecycleEvents.SERVER_STOPPING — reset in-memory state. */
    public void onServerStop() {
        state = MatchState.DORMANT;
        matchStartTick = -1;
        snapshots.clear();
        matchDimensions = Set.of();
        matchPlayers.clear();
        TeamAssignments.get().clearAll();
        LifelineState.get().resetAll();
        LifelineBars.get().clearAll();
        DepositTracker.get().resetAll();
        PermanentLossTracker.get().resetAll();
        MassEventTargeting.get().resetAll();
        TrialChamber.get().resetAll();
        Cooldowns.get().clearAll();
        AbilityHooks.clearAll();
        WarriorAbility.clearAll();
        BardAbility.clearAll();
        BerserkerAbility.clearAll();
        SpyAbility.clearAll();
        WizardAbility.clearAll();
        ImmobilizerAbility.clearAll();
        BloodmageAbility.clearAll();
        LumberjackAbility.clearAll();
        MinerAbility.clearAll();
        TinkererAbility.clearAll();
        NeptuneAbility.clearAll();
        TransporterAbility.clearAll();
        com.regionsmoba.classes.impl.ArcherAbility.clearAll();
        com.regionsmoba.classes.impl.BloodmageTerraform.clearAll();
        com.regionsmoba.classes.impl.DefenderAlertItem.clearAll();
        com.regionsmoba.classes.impl.BuilderCache.clearAll();
        com.regionsmoba.classes.impl.EnchanterAbility.clearAll();
        com.regionsmoba.classes.impl.AlchemistCauldron.clearAll();
        com.regionsmoba.timeline.DrownedSpawning.clearAll();
        BuildMode.clearAll();
        Timeline.get().stop();
        ModEntities.clearAll();
        NationsLobbyRegistry.get().dropAll();
        server = null;
    }
}
