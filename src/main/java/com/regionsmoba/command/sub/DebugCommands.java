package com.regionsmoba.command.sub;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.regionsmoba.chamber.TrialChamber;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.KitGrant;
import com.regionsmoba.command.CommandHelpers;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.debug.TestMode;
import com.regionsmoba.deposit.DepositTracker;
import com.regionsmoba.events.PermanentLossTracker;
import com.regionsmoba.lifeline.BloodTributeLifeline;
import com.regionsmoba.lifeline.LifelineState;
import com.regionsmoba.lifeline.PlainsQuota;
import com.regionsmoba.lobby.LobbyFlow;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.protection.BuildMode;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.repair.ModEntities;
import com.regionsmoba.repair.WorldSnapshot;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.team.TeamPassives;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import com.regionsmoba.trader.TraderRespawn;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.AABB;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Debug subcommands. Operator-only and intended for development, map testing, and
 * live troubleshooting. See docs/src-md/reference/commands.md (Debug commands).
 *
 * Slice 7 adds:
 *   /regions debug map repair      (already in slice 2)
 *   /regions debug buildmode <on|off>
 *   /regions debug lives <player> <count>
 *   /regions debug team <player> <biome>
 *   /regions debug class <player> <class>
 *   /regions debug deposits regen
 *   /regions debug deposits reset
 *
 * The remaining debug surface (season force, pvp force, lifeline state, mass-event
 * fire, chamber waves, cooldowns clear, kit re-grant, tp, mob kill, trader respawn,
 * deposit info, show overlays, reload) is layered in with the slices that ship the
 * underlying systems.
 */
public final class DebugCommands {

    private DebugCommands() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("debug")
                .then(Commands.literal("map")
                        .then(Commands.literal("repair").executes(ctx -> repairNow(ctx.getSource()))))
                .then(Commands.literal("buildmode")
                        .then(Commands.literal("on").executes(ctx -> setBuildMode(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setBuildMode(ctx.getSource(), false))))
                .then(Commands.literal("lives")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setLives(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("team")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("biome", StringArgumentType.word())
                                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                        .executes(ctx -> setTeam(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "biome"))))))
                .then(Commands.literal("testmode")
                        .then(Commands.literal("on").executes(ctx -> testMode(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> testMode(ctx.getSource(), false))))
                .then(Commands.literal("win")
                        .then(Commands.argument("biome", StringArgumentType.word())
                                .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                .executes(ctx -> forceWin(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "biome")))))
                .then(Commands.literal("class")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .executes(ctx -> setClass(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "class"))))))
                .then(Commands.literal("deposits")
                        .then(Commands.literal("regen").executes(ctx -> depositsRegen(ctx.getSource())))
                        .then(Commands.literal("reset").executes(ctx -> depositsReset(ctx.getSource()))))
                .then(Commands.literal("massevent")
                        .then(Commands.literal("fire")
                                .then(Commands.argument("biome", StringArgumentType.word())
                                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                        .executes(ctx -> massEventFire(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "biome")))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("biome", StringArgumentType.word())
                                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                        .executes(ctx -> massEventReset(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "biome"))))))
                .then(Commands.literal("chamber")
                        .then(Commands.literal("wave")
                                .then(Commands.literal("start").executes(ctx -> chamberWaveStart(ctx.getSource())))
                                .then(Commands.literal("end").executes(ctx -> chamberWaveEnd(ctx.getSource()))))
                        .then(Commands.literal("waves")
                                .then(Commands.literal("on").executes(ctx -> chamberCadence(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> chamberCadence(ctx.getSource(), false)))))
                .then(Commands.literal("kit")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> reKit(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("cooldowns")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("clear")
                                        .executes(ctx -> clearCooldowns(
                                                ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("season")
                        .then(Commands.literal("cold").executes(ctx -> forceSeason(ctx.getSource(), MatchPhase.COLD)))
                        .then(Commands.literal("warm").executes(ctx -> forceSeason(ctx.getSource(), MatchPhase.WARM))))
                .then(Commands.literal("seasontimer")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                .executes(ctx -> setSeasonTimer(
                                        ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(Commands.literal("pvp")
                        .then(Commands.literal("on").executes(ctx -> setPvpOverride(ctx.getSource(), PvpManager.Override.ON)))
                        .then(Commands.literal("off").executes(ctx -> setPvpOverride(ctx.getSource(), PvpManager.Override.OFF)))
                        .then(Commands.literal("permanent").executes(ctx -> setPvpOverride(ctx.getSource(), PvpManager.Override.PERMANENT)))
                        .then(Commands.literal("clear").executes(ctx -> setPvpOverride(ctx.getSource(), null))))
                .then(Commands.literal("bloodtribute")
                        .then(Commands.literal("satisfy").executes(ctx -> bloodTribute(ctx.getSource(), "satisfy")))
                        .then(Commands.literal("reset").executes(ctx -> bloodTribute(ctx.getSource(), "reset")))
                        .then(Commands.literal("fail").executes(ctx -> bloodTribute(ctx.getSource(), "fail"))))
                .then(Commands.literal("state").executes(ctx -> dumpState(ctx.getSource())))
                .then(Commands.literal("tp")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("lobby")
                                        .executes(ctx -> tpTo(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), null)))
                                .then(Commands.argument("biome", StringArgumentType.word())
                                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                        .executes(ctx -> tpTo(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "biome"))))))
                .then(Commands.literal("trader")
                        .then(Commands.literal("respawn")
                                .then(Commands.argument("biome", StringArgumentType.word())
                                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                        .executes(ctx -> traderRespawn(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "biome"))))))
                .then(Commands.literal("mob")
                        .then(Commands.literal("kill")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                        .executes(ctx -> mobKill(
                                                ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("show")
                        .then(Commands.literal("protected").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.PROTECTED)))
                        .then(Commands.literal("deposits").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.DEPOSITS)))
                        .then(Commands.literal("spawns").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.SPAWNS)))
                        .then(Commands.literal("biomes").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.BIOMES)))
                        .then(Commands.literal("lifelines").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.LIFELINES)))
                        .then(Commands.literal("lobby").executes(ctx -> toggleOverlay(ctx.getSource(), com.regionsmoba.debug.VisualizationOverlays.Overlay.LOBBY)))
                        .then(Commands.literal("all").executes(ctx -> overlayAll(ctx.getSource())))
                        .then(Commands.literal("off").executes(ctx -> overlayOff(ctx.getSource()))))
                .then(Commands.literal("furnace")
                        .then(Commands.literal("lit").executes(ctx -> setFurnaceLit(ctx.getSource(), true)))
                        .then(Commands.literal("unlit").executes(ctx -> setFurnaceLit(ctx.getSource(), false)))
                        .then(Commands.literal("fuel")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setFurnaceFuel(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks"))))))
                .then(Commands.literal("conduit")
                        .then(Commands.literal("hp")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 200))
                                        .executes(ctx -> setConduitHp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(Commands.literal("quota")
                        .then(Commands.literal("set")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> quotaSet(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "amount")))))
                        .then(Commands.literal("info")
                                .executes(ctx -> quotaInfo(ctx.getSource()))))
                .then(Commands.literal("deposit")
                        .then(Commands.literal("info").executes(ctx -> depositInfo(ctx.getSource()))))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource()))));
    }

    // ---- map repair ----

    private static int repairNow(CommandSourceStack src) {
        MatchManager match = MatchManager.get();
        if (!match.isActive()) {
            CommandHelpers.fail(src, "No active match — nothing to repair against.");
            return 0;
        }
        WorldSnapshot.RestoreResult result = match.debugRepairNow();
        CommandHelpers.ok(src, "Map repair: restored " + result.blocksRestored() + " block(s).");
        return 1;
    }

    // ---- buildmode ----

    private static int setBuildMode(CommandSourceStack src, boolean on) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "Build mode requires a player sender.");
            return 0;
        }
        BuildMode.set(player.getUUID(), on);
        CommandHelpers.ok(src, "Build mode " + (on ? "ON" : "OFF") + " for " + player.getGameProfile().getName());
        return 1;
    }

    // ---- lives / team / class ----

    private static int setLives(CommandSourceStack src, ServerPlayer target, int count) {
        MatchPlayerState state = TeamAssignments.get().state(target.getUUID());
        if (state == null) {
            CommandHelpers.fail(src, target.getGameProfile().getName() + " is not in the current match.");
            return 0;
        }
        state.lives = count;
        if (count == 0) state.spectator = true;
        CommandHelpers.ok(src, target.getGameProfile().getName() + " lives = " + count);
        return 1;
    }

    // ---- solo test mode / manual match end ----

    /**
     * Solo playtest toggle. Starts a match if none is running, adds the sender so
     * every other debug command can act on them, and suppresses automatic match
     * end until switched off.
     */
    private static int testMode(CommandSourceStack src, boolean on) {
        if (!on) {
            if (!TestMode.isActive()) {
                CommandHelpers.warn(src, "Test mode is already off.");
                return 0;
            }
            TestMode.set(false);
            CommandHelpers.ok(src, "Test mode off — automatic match end is live again.");
            return 1;
        }

        if (TestMode.isActive()) {
            CommandHelpers.warn(src, "Test mode is already on.");
            return 0;
        }

        ServerPlayer sender = src.getPlayer();
        if (sender == null) {
            CommandHelpers.fail(src, "Run this as a player — test mode adds the sender to the match.");
            return 0;
        }

        MatchManager match = MatchManager.get();
        boolean started = false;
        if (!match.isActive()) {
            match.start(src.getServer());
            started = true;
        }

        // Order matters: MatchManager.start() runs a reset path that calls
        // TestMode.clear(). Setting the flag before the start would silently wipe
        // it, leaving a match that still auto-ends — and it looks like it worked
        // until a winner is declared mid-playtest. Never hoist this above start().
        match.addMatchPlayer(sender.getUUID());
        TeamAssignments.get().join(sender.getUUID());
        TestMode.set(true);

        CommandHelpers.ok(src, started
                ? "Match started, test mode on — pick a team."
                : "Test mode on — you have joined the running match.");
        LobbyFlow.openTeamPicker(sender);
        return 1;
    }

    /**
     * Manually declares a winner and ends the match. Broadcasts the same line
     * MatchEndConditions uses on a natural end so both paths read identically.
     */
    private static int forceWin(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        MatchManager match = MatchManager.get();
        if (!match.isActive()) {
            CommandHelpers.fail(src, "No match is running.");
            return 0;
        }
        BiomeTeam winner = team.get();
        Component msg = Component.literal("Match over — " + winner.displayName() + " wins!")
                .withStyle(winner.color(), ChatFormatting.BOLD);
        for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
        match.abort();
        CommandHelpers.ok(src, "Match ended — " + winner.displayName() + " declared winner.");
        return 1;
    }

    private static int setTeam(CommandSourceStack src, ServerPlayer target, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        MatchManager match = MatchManager.get();
        MatchPlayerState state = TeamAssignments.get().state(target.getUUID());
        if (state == null) {
            if (!match.isActive()) {
                CommandHelpers.fail(src, target.getGameProfile().getName() + " is not in the current match.");
                return 0;
            }
            // Auto-join: doubles as the join path for a second tester.
            match.addMatchPlayer(target.getUUID());
            TeamAssignments.get().join(target.getUUID());
            state = TeamAssignments.get().state(target.getUUID());
        }

        if (state.team != null) TeamPassives.clear(target, state.team);
        state.team = team.get();
        state.biomeClass = null;
        TeamPassives.apply(target, team.get());
        LobbyFlow.teleportToTeamSpawn(target, team.get());
        LobbyFlow.openClassPicker(target, team.get());

        CommandHelpers.ok(src, target.getGameProfile().getName() + " → " + team.get().displayName()
                + " (teleported; pick a class)");
        return 1;
    }

    private static int setClass(CommandSourceStack src, ServerPlayer target, String classId) {
        MatchPlayerState state = TeamAssignments.get().state(target.getUUID());
        if (state == null || state.team == null) {
            CommandHelpers.fail(src, target.getGameProfile().getName() + " has no team yet.");
            return 0;
        }
        Optional<BiomeClass> bc = BiomeClass.fromId(state.team, classId);
        if (bc.isEmpty()) {
            CommandHelpers.fail(src, "Unknown class for " + state.team.displayName() + ": " + classId);
            return 0;
        }
        state.biomeClass = bc.get();
        CommandHelpers.ok(src, target.getGameProfile().getName() + " → " + bc.get().displayName());
        return 1;
    }

    // ---- deposits ----

    private static int depositsRegen(CommandSourceStack src) {
        DepositTracker.get().regenAllNow(src.getServer());
        CommandHelpers.ok(src, "All deposit cooldowns regenerated.");
        return 1;
    }

    private static int depositsReset(CommandSourceStack src) {
        DepositTracker.get().resetCooldowns();
        CommandHelpers.ok(src, "All deposit cooldowns cleared.");
        return 1;
    }

    // ---- mass events ----

    private static int massEventFire(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        PermanentLossTracker.get().forceFire(team.get(), src.getServer());
        CommandHelpers.ok(src, team.get().displayName() + " mass event force-fired.");
        return 1;
    }

    private static int massEventReset(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        PermanentLossTracker.get().resetTeam(team.get());
        CommandHelpers.ok(src, team.get().displayName() + " mass event re-armed.");
        return 1;
    }

    // ---- trial chamber ----

    private static int chamberWaveStart(CommandSourceStack src) {
        if (!MatchManager.get().isActive()) {
            CommandHelpers.fail(src, "No active match.");
            return 0;
        }
        TrialChamber.get().startWave(src.getServer());
        CommandHelpers.ok(src, "Trial wave started.");
        return 1;
    }

    private static int chamberWaveEnd(CommandSourceStack src) {
        if (!MatchManager.get().isActive() || !TrialChamber.get().waveActive()) {
            CommandHelpers.fail(src, "No active trial wave.");
            return 0;
        }
        TrialChamber.get().endWave(src.getServer());
        CommandHelpers.ok(src, "Trial wave ended early; loot awarded to leader (or split on tie).");
        return 1;
    }

    private static int chamberCadence(CommandSourceStack src, boolean enabled) {
        TrialChamber.get().setCadenceEnabled(enabled);
        CommandHelpers.ok(src, "Chamber wave cadence " + (enabled ? "ON" : "OFF"));
        return 1;
    }

    // ---- kit / cooldowns ----

    private static int reKit(CommandSourceStack src, ServerPlayer target) {
        MatchPlayerState s = TeamAssignments.get().state(target.getUUID());
        if (s == null || s.biomeClass == null) {
            CommandHelpers.fail(src, target.getGameProfile().getName() + " has no class set.");
            return 0;
        }
        KitGrant.grant(target, s.biomeClass);
        CommandHelpers.ok(src, target.getGameProfile().getName() + " kit re-granted: " + s.biomeClass.displayName());
        return 1;
    }

    private static int clearCooldowns(CommandSourceStack src, ServerPlayer target) {
        Cooldowns.get().clearForPlayer(target.getUUID());
        CommandHelpers.ok(src, target.getGameProfile().getName() + " cooldowns cleared.");
        return 1;
    }

    // ---- season / pvp / blood tribute ----

    private static int forceSeason(CommandSourceStack src, MatchPhase target) {
        if (!Timeline.get().isRunning()) {
            CommandHelpers.fail(src, "Timeline is not running.");
            return 0;
        }
        Timeline.PhaseChange change = Timeline.get().forcePhase(target);
        if (change == null) {
            CommandHelpers.warn(src, "Already in phase " + target);
            return 0;
        }
        BloodTributeLifeline.onPhaseChange(change, src.getServer());
        PlainsQuota.onPhaseChange(src.getServer(), change.from(), change.to());
        CommandHelpers.ok(src, "Phase forced to " + target);
        return 1;
    }

    private static int setSeasonTimer(CommandSourceStack src, int seconds) {
        if (!Timeline.get().isRunning()) {
            CommandHelpers.fail(src, "Timeline is not running.");
            return 0;
        }
        Timeline.get().setSecondsRemainingInPhase(seconds);
        CommandHelpers.ok(src, "Phase will end in " + seconds + "s.");
        return 1;
    }

    private static int setPvpOverride(CommandSourceStack src, PvpManager.Override value) {
        PvpManager.setOverride(value);
        CommandHelpers.ok(src, "PVP override = " + (value == null ? "none (timeline-driven)" : value.name()));
        return 1;
    }

    private static int bloodTribute(CommandSourceStack src, String action) {
        switch (action) {
            case "satisfy" -> {
                BloodTributeLifeline.debugSatisfy();
                CommandHelpers.ok(src, "Blood Tribute marked satisfied.");
            }
            case "reset" -> {
                BloodTributeLifeline.debugReset();
                CommandHelpers.ok(src, "Blood Tribute reset to unsatisfied.");
            }
            case "fail" -> {
                BloodTributeLifeline.debugFail(src.getServer());
                CommandHelpers.ok(src, "Blood Tribute failure penalty applied.");
            }
            default -> {
                CommandHelpers.fail(src, "Unknown action: " + action);
                return 0;
            }
        }
        return 1;
    }

    // ---- state dump ----

    private static int dumpState(CommandSourceStack src) {
        MatchManager match = MatchManager.get();
        Timeline t = Timeline.get();
        line(src, "State", match.state().name());
        if (match.isActive()) {
            line(src, "Phase", t.phase().name());
            line(src, "Phase remaining", t.secondsRemainingInPhase() + "s");
            line(src, "PVP allowed", String.valueOf(PvpManager.isPvpAllowed()));
            line(src, "PVP override", PvpManager.override() == null ? "none" : PvpManager.override().name());
            line(src, "Match players", String.valueOf(match.matchPlayers().size()));
            line(src, "Match dimensions", match.matchDimensions().toString());
            line(src, "Conduit HP", LifelineState.get().conduitHp + " / " + LifelineState.CONDUIT_MAX_HP);
            line(src, "Ocean permadeath", String.valueOf(LifelineState.get().oceanPermadeath));
            line(src, "Blood Tribute satisfied", String.valueOf(LifelineState.get().bloodTributeSatisfied));
            line(src, "Tracked mod entities", String.valueOf(ModEntities.trackedCount()));
            line(src, "Trial wave active", String.valueOf(TrialChamber.get().waveActive()));
            for (BiomeTeam team : BiomeTeam.values()) {
                line(src, team.displayName() + " starting/lost",
                        PermanentLossTracker.get().startingMembers(team) + "/" + PermanentLossTracker.get().permanentLosses(team)
                                + (PermanentLossTracker.get().massEventFired(team) ? " (event fired)" : ""));
            }
        }
        return 1;
    }

    private static void line(CommandSourceStack src, String key, String value) {
        src.sendSuccess(() -> Component.literal(key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }

    // ---- tp / trader / mob kill ----

    private static int tpTo(CommandSourceStack src, ServerPlayer target, String biomeId) {
        BlockPosData pos;
        if (biomeId == null) {
            pos = RegionsConfig.get().lobbySpawn;
            if (pos == null) {
                CommandHelpers.fail(src, "No lobby spawn registered.");
                return 0;
            }
        } else {
            Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
            if (team.isEmpty()) {
                CommandHelpers.fail(src, "Unknown biome: " + biomeId);
                return 0;
            }
            List<BlockPosData> spawns = RegionsConfig.get().spawnsFor(team.get());
            if (spawns.isEmpty()) {
                CommandHelpers.fail(src, "No spawn registered for " + team.get().displayName());
                return 0;
            }
            pos = spawns.get(0);
        }
        MinecraftServer server = src.getServer();
        ServerLevel level = server.getLevel(pos.dimensionKey());
        if (level == null) {
            CommandHelpers.fail(src, "Dimension not loaded: " + pos.dimensionOrDefault());
            return 0;
        }
        target.teleportTo(level, pos.x() + 0.5, pos.y(), pos.z() + 0.5,
                Set.<RelativeMovement>of(), target.getYRot(), target.getXRot());
        CommandHelpers.ok(src, "Teleported " + target.getGameProfile().getName() + " to " + (biomeId == null ? "lobby" : biomeId));
        return 1;
    }

    private static int traderRespawn(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        boolean ok = TraderRespawn.respawnOne(src.getServer(), team.get());
        if (!ok) {
            CommandHelpers.fail(src, team.get().displayName() + " trader is not registered.");
            return 0;
        }
        CommandHelpers.ok(src, team.get().displayName() + " trader respawned.");
        return 1;
    }

    private static int mobKill(CommandSourceStack src, int radius) {
        ServerLevel level;
        try {
            level = src.getPlayerOrException().serverLevel();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "/regions debug mob kill must be run by a player.");
            return 0;
        }
        AABB box = AABB.ofSize(src.getPosition(), radius * 2, radius * 2, radius * 2);
        Iterator<Entity> it = level.getEntitiesOfClass(Entity.class, box, e -> ModEntities.isTracked(e)).iterator();
        int killed = 0;
        while (it.hasNext()) {
            it.next().discard();
            killed++;
        }
        CommandHelpers.ok(src, "Killed " + killed + " mod-spawned mob(s) in radius " + radius);
        return 1;
    }

    // ---- overlays ----

    private static int toggleOverlay(CommandSourceStack src, com.regionsmoba.debug.VisualizationOverlays.Overlay overlay) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "/regions debug show must be run by a player.");
            return 0;
        }
        com.regionsmoba.debug.VisualizationOverlays.toggle(player.getUUID(), overlay);
        boolean on = com.regionsmoba.debug.VisualizationOverlays.overlaysFor(player.getUUID()).contains(overlay);
        CommandHelpers.ok(src, "Overlay " + overlay.name() + " " + (on ? "ON" : "OFF"));
        return 1;
    }

    private static int overlayAll(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "/regions debug show must be run by a player.");
            return 0;
        }
        com.regionsmoba.debug.VisualizationOverlays.enableAll(player.getUUID());
        CommandHelpers.ok(src, "All overlays ON");
        return 1;
    }

    private static int overlayOff(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "/regions debug show must be run by a player.");
            return 0;
        }
        com.regionsmoba.debug.VisualizationOverlays.disableAll(player.getUUID());
        CommandHelpers.ok(src, "All overlays OFF");
        return 1;
    }

    // ---- furnace / conduit / quota / deposit / reload ----

    private static int setFurnaceLit(CommandSourceStack src, boolean lit) {
        BlockPosData pos = RegionsConfig.get().furnace;
        if (pos == null) {
            CommandHelpers.fail(src, "No furnace registered.");
            return 0;
        }
        ServerLevel level = src.getServer().getLevel(pos.dimensionKey());
        if (level == null) {
            CommandHelpers.fail(src, "Furnace dimension not loaded.");
            return 0;
        }
        net.minecraft.core.BlockPos bp = pos.toBlockPos();
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(bp);
        if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
            CommandHelpers.fail(src, "Block at furnace position has no LIT property: " + state);
            return 0;
        }
        level.setBlock(bp, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, lit),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        CommandHelpers.ok(src, "Furnace " + (lit ? "lit" : "extinguished"));
        return 1;
    }

    private static int setFurnaceFuel(CommandSourceStack src, int ticks) {
        BlockPosData pos = RegionsConfig.get().furnace;
        if (pos == null) {
            CommandHelpers.fail(src, "No furnace registered.");
            return 0;
        }
        ServerLevel level = src.getServer().getLevel(pos.dimensionKey());
        if (level == null) {
            CommandHelpers.fail(src, "Furnace dimension not loaded.");
            return 0;
        }
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos.toBlockPos());
        if (!(be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity)) {
            CommandHelpers.fail(src, "Block at furnace position is not a furnace.");
            return 0;
        }
        com.regionsmoba.mixin.FurnaceAccessor accessor = (com.regionsmoba.mixin.FurnaceAccessor) (Object) be;
        accessor.regionsmoba$setLitTimeRemaining(ticks);
        CommandHelpers.ok(src, "Furnace fuel set to " + ticks + " ticks.");
        return 1;
    }

    private static int setConduitHp(CommandSourceStack src, int amount) {
        com.regionsmoba.lifeline.LifelineState ls = com.regionsmoba.lifeline.LifelineState.get();
        ls.conduitHp = amount;
        ls.oceanPermadeath = amount <= 0;
        CommandHelpers.ok(src, "Conduit HP set to " + amount + (amount <= 0 ? " (Ocean permadeath ARMED)" : ""));
        return 1;
    }

    private static int quotaSet(CommandSourceStack src, int amount) {
        LifelineState.get().plainsQuotaPaid = amount;
        CommandHelpers.ok(src, "Plains quota paid set to " + amount + ".");
        return 1;
    }

    private static int quotaInfo(CommandSourceStack src) {
        CommandHelpers.info(src, "Plains quota: " + PlainsQuota.paid()
                + " / " + PlainsQuota.currentQuota()
                + "  (season " + (LifelineState.get().plainsQuotaSeason + 1)
                + ", members " + PlainsQuota.livingMembers() + ")");
        return 1;
    }

    private static int depositInfo(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "/regions debug deposit info must be run by a player.");
            return 0;
        }
        com.regionsmoba.command.click.PendingRegistrationStore.set(
                player.getUUID(),
                new com.regionsmoba.command.click.PendingRegistration(
                        com.regionsmoba.command.click.PendingRegistration.Kind.REMOVE_ORE_DEPOSIT));
        CommandHelpers.warn(src,
                "Right-click a deposit to print its info — registered as REMOVE_ORE_DEPOSIT, immediate cancel after info print is recommended via /regions done.");
        return 1;
    }

    private static int reload(CommandSourceStack src) {
        com.regionsmoba.config.RegionsConfig.load();
        CommandHelpers.ok(src, "Config reloaded from disk.");
        return 1;
    }
}
