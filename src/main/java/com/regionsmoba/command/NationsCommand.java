package com.regionsmoba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.lobby.LobbyFlow;
import com.regionsmoba.lobby.NationsSignHandler;
import com.regionsmoba.lobby.NationsSignState;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * The player-facing command tree, rooted at /nations.
 *
 * Separate from /regions because that root gates its whole subtree behind
 * permission level 2 — these are meant for everyone, so they need a root of
 * their own with no requires().
 *
 * /nations join is the no-sign-hunting path into a game: it queues the caller at
 * the busiest [Nations] board on the server.
 */
public final class NationsCommand {

    public static final String ROOT = "nations";

    private NationsCommand() {}

    public static void registerAll() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("join").executes(ctx -> join(ctx.getSource())))
                .then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource())))
                .then(Commands.literal("class").executes(ctx -> changeClass(ctx.getSource()))));
    }

    private static int join(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        NationsSignHandler.JoinResult result = NationsSignHandler.joinBest(player);
        NationsSignHandler.Lobby lobby = result.lobby();
        switch (result.status()) {
            case JOINED, MOVED -> {
                String prefix = result.status() == NationsSignHandler.JoinStatus.MOVED
                        ? "Moved to the fullest lobby" : "Joined the fullest lobby";
                CommandHelpers.ok(src, prefix + " — " + counts(lobby) + remaining(lobby));
                return 1;
            }
            case ALREADY_QUEUED -> {
                CommandHelpers.info(src, "Already queued at the fullest lobby — " + counts(lobby) + remaining(lobby));
                return 1;
            }
            case MATCH_ACTIVE -> {
                CommandHelpers.fail(src, "A match is already in progress. Wait for it to end.");
                return 0;
            }
            default -> {
                CommandHelpers.fail(src, "No [Nations] lobbies are open. Ask an operator to set one up, "
                        + "or right-click a [Nations] sign directly.");
                return 0;
            }
        }
    }

    private static int leave(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        NationsSignState state = NationsSignHandler.leaveQueue(player);
        if (state == null) {
            CommandHelpers.warn(src, "You're not in a join queue.");
            return 0;
        }
        CommandHelpers.ok(src, "Left the queue (" + state.joiners.size() + " / " + state.minPlayers + ")");
        return 1;
    }

    /**
     * Mid-match class change. Restricted to your own region: walking home is the
     * cost, which is why there is no cooldown. Switching preserves earned gear —
     * see {@link com.regionsmoba.classes.KitGrant#switchClass}.
     */
    private static int changeClass(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();

        if (!MatchManager.get().isActive()) {
            CommandHelpers.fail(src, "No match is running.");
            return 0;
        }
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        if (state == null || state.team == null) {
            CommandHelpers.fail(src, "You have no team yet.");
            return 0;
        }
        if (state.spectator) {
            CommandHelpers.fail(src, "Spectators cannot change class.");
            return 0;
        }

        Area home = RegionsConfig.get().biomeBounds(state.team);
        String dimension = player.level().dimension().location().toString();
        boolean atHome = home != null && home.isComplete()
                && home.dimension().equals(dimension)
                && home.contains(player.getX(), player.getY(), player.getZ());
        if (!atHome) {
            CommandHelpers.warn(src, "You must be inside your own region to change class.");
            return 0;
        }

        LobbyFlow.openClassPicker(player, state.team);
        return 1;
    }

    private static String counts(NationsSignHandler.Lobby lobby) {
        return lobby.joined() + " / " + lobby.minPlayers() + " joined.";
    }

    private static String remaining(NationsSignHandler.Lobby lobby) {
        int left = lobby.slotsLeft();
        if (left <= 0) return " Starting now.";
        return " " + left + " more to start.";
    }
}
