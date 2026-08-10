package com.regionsmoba.lobby;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Handles [Nations] signs — both right-clicks and the {@code /nations join}
 * auto-matcher. Validates sign content per the docs:
 *   Row 1 must be "[Nations]" (case-insensitive)
 *   Row 2 must be a positive integer and a multiple of 4
 *
 * Valid signs become live join boards: row 3 displays "X / Y joined" and updates
 * on every join/leave. When the threshold is met, the registered joiners start a
 * match and teleport to the lobby spawn.
 *
 * Players queued at one sign are moved when they click another. Right-clicking
 * the sign you're queued at leaves the queue.
 *
 * Either face may carry the tag: validation checks the front text first, then the
 * back, and the counter is written to every tagged face so both sides stay in sync.
 */
public final class NationsSignHandler {

    private static final String NATIONS_TAG = "[nations]";

    private NationsSignHandler() {}

    /** What a sign's text says: whether it is tagged, and the parsed threshold. */
    public record SignCheck(boolean tagged, Integer minPlayers, String row2) {
        static final SignCheck UNTAGGED = new SignCheck(false, null, "");

        /** Tagged and row 2 parsed — the sign is usable as a join board. */
        public boolean usable() {
            return tagged && minPlayers != null;
        }
    }

    /** A usable join board and its current queue size. */
    public record Lobby(BlockPosData pos, int minPlayers, int joined) {
        public int slotsLeft() {
            return Math.max(0, minPlayers - joined);
        }
    }

    /** Outcome of an auto-join attempt, for the command layer to report. */
    public enum JoinStatus { JOINED, MOVED, ALREADY_QUEUED, MATCH_ACTIVE, NO_LOBBIES }

    public record JoinResult(JoinStatus status, Lobby lobby) {}

    // ---- Right-click path ----

    /** Returns true if the click was handled (operator should not see vanilla edit UI). */
    public static boolean handleClick(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return false;

        SignCheck check = check(sign);
        if (!check.tagged()) return false;

        // From here we know it's intended to be a [Nations] sign — handle it
        // (and intercept the click) even if row 2 is invalid, so we can tell the
        // player why it's not working.
        BlockPosData signPos = BlockPosData.of(level, pos);
        if (check.minPlayers() == null) {
            tell(player, "Sign rejected: row 2 must be a positive integer and a multiple of 4 (got '"
                    + check.row2() + "')", ChatFormatting.RED);
            return true;
        }
        // Signs that predate the mod are only discoverable once someone clicks
        // them, so every valid click also records the position for /nations join.
        RegionsConfig.rememberNationsSign(signPos);

        if (MatchManager.get().isActive()) {
            tell(player, "A match is already in progress. Wait for it to end.", ChatFormatting.YELLOW);
            return true;
        }

        int min = check.minPlayers();
        NationsLobbyRegistry registry = NationsLobbyRegistry.get();

        if (signPos.equals(registry.currentSignFor(player.getUUID()))) {
            // Right-clicked the sign they're already queued at — leave.
            NationsSignState state = registry.leave(player.getUUID());
            tell(player, "Left the join queue (" + (state != null ? state.joiners.size() : 0) + " / " + min + ")",
                    ChatFormatting.GRAY);
            if (state != null) updateDisplay(level, sign, state);
            return true;
        }

        NationsSignState state = joinAndRefresh(player, signPos, min);
        tell(player, "Joined the queue (" + state.joiners.size() + " / " + min + ")", ChatFormatting.GREEN);
        updateDisplay(level, sign, state);
        maybeStart(level.getServer(), state);
        return true;
    }

    // ---- Command path ----

    /**
     * Queue the player at the fullest open lobby.
     *
     * "Fullest" is the highest joiner count; ties break toward the board that needs
     * the fewest more players, then by position so the pick is stable across calls.
     */
    public static JoinResult joinBest(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return new JoinResult(JoinStatus.NO_LOBBIES, null);
        if (MatchManager.get().isActive()) return new JoinResult(JoinStatus.MATCH_ACTIVE, null);

        List<Lobby> lobbies = openLobbies(server);
        if (lobbies.isEmpty()) return new JoinResult(JoinStatus.NO_LOBBIES, null);

        Lobby best = lobbies.stream()
                .max(Comparator.comparingInt(Lobby::joined)
                        .thenComparing(Comparator.comparingInt(Lobby::slotsLeft).reversed())
                        .thenComparing(Lobby::pos, Comparator.comparing(BlockPosData::dimensionOrDefault)
                                .thenComparingInt(BlockPosData::x)
                                .thenComparingInt(BlockPosData::y)
                                .thenComparingInt(BlockPosData::z)
                                .reversed()))
                .orElseThrow();

        NationsLobbyRegistry registry = NationsLobbyRegistry.get();
        BlockPosData current = registry.currentSignFor(player.getUUID());
        if (best.pos().equals(current)) {
            return new JoinResult(JoinStatus.ALREADY_QUEUED, best);
        }

        NationsSignState state = joinAndRefresh(player, best.pos(), best.minPlayers());
        refreshSign(server, best.pos());
        JoinStatus status = current == null ? JoinStatus.JOINED : JoinStatus.MOVED;
        Lobby joined = new Lobby(best.pos(), state.minPlayers, state.joiners.size());
        maybeStart(server, state);
        return new JoinResult(status, joined);
    }

    /** Drop the player from whatever queue they're in. Returns the sign left, or null. */
    public static NationsSignState leaveQueue(ServerPlayer player) {
        NationsSignState state = NationsLobbyRegistry.get().leave(player.getUUID());
        if (state != null && player.getServer() != null) refreshSign(player.getServer(), state.signPos);
        return state;
    }

    /**
     * Every known sign that is currently a usable join board, with live queue sizes.
     * Positions whose block is no longer a [Nations] sign are pruned from config as
     * they're encountered; positions in an unloaded dimension are skipped, not pruned.
     */
    public static List<Lobby> openLobbies(MinecraftServer server) {
        List<Lobby> out = new ArrayList<>();
        List<BlockPosData> stale = new ArrayList<>();
        for (BlockPosData pos : List.copyOf(RegionsConfig.get().nationsSigns)) {
            ServerLevel level = server.getLevel(pos.dimensionKey());
            if (level == null) continue;
            BlockEntity be = level.getBlockEntity(pos.toBlockPos());
            if (!(be instanceof SignBlockEntity sign)) {
                stale.add(pos);
                continue;
            }
            SignCheck check = check(sign);
            if (!check.tagged()) {
                stale.add(pos);
                continue;
            }
            // Tagged but row 2 is malformed: leave it registered so an operator can
            // fix the text, but don't offer it as a destination.
            if (check.minPlayers() == null) continue;
            NationsSignState state = NationsLobbyRegistry.get().get(pos);
            out.add(new Lobby(pos, check.minPlayers(), state != null ? state.joiners.size() : 0));
        }
        for (BlockPosData pos : stale) RegionsConfig.forgetNationsSign(pos);
        return out;
    }

    // ---- Shared internals ----

    /**
     * Queue the player and repaint the board they came from — switching queues
     * leaves a stale count behind otherwise.
     */
    private static NationsSignState joinAndRefresh(ServerPlayer player, BlockPosData signPos, int min) {
        NationsLobbyRegistry registry = NationsLobbyRegistry.get();
        BlockPosData previous = registry.currentSignFor(player.getUUID());
        NationsSignState state = registry.join(player.getUUID(), signPos, min);
        if (previous != null && !previous.equals(signPos) && player.getServer() != null) {
            refreshSign(player.getServer(), previous);
        }
        return state;
    }

    private static void maybeStart(MinecraftServer server, NationsSignState state) {
        if (server == null || !state.thresholdReached()) return;
        MatchManager.get().startWithJoiners(server, state.joiners);
        // Lobby teleport is handled by MatchManager.startWithJoiners.
    }

    /** Repaint a board from the registry's current state. Silent if it's gone. */
    private static void refreshSign(MinecraftServer server, BlockPosData signPos) {
        NationsSignState state = NationsLobbyRegistry.get().get(signPos);
        if (state == null) return;
        ServerLevel level = server.getLevel(signPos.dimensionKey());
        if (level == null) return;
        if (level.getBlockEntity(signPos.toBlockPos()) instanceof SignBlockEntity sign) {
            updateDisplay(level, sign, state);
        }
    }

    /** Front text first, then back — either face may carry the tag. */
    public static SignCheck check(SignBlockEntity sign) {
        for (boolean front : new boolean[]{true, false}) {
            SignText text = sign.getText(front);
            if (!isNationsTag(lineString(text, 0))) continue;
            String row2 = lineString(text, 1).trim();
            return new SignCheck(true, parseMinPlayers(row2), row2);
        }
        return SignCheck.UNTAGGED;
    }

    private static String lineString(SignText text, int line) {
        Component[] msgs = text.getMessages(false);
        if (line < 0 || line >= msgs.length) return "";
        return msgs[line].getString();
    }

    private static boolean isNationsTag(String s) {
        return s != null && s.trim().toLowerCase(Locale.ROOT).equals(NATIONS_TAG);
    }

    public static Integer parseMinPlayers(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            int v = Integer.parseInt(s);
            if (v <= 0 || v % 4 != 0) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Write the counter to every tagged face so both sides of the sign agree. */
    private static void updateDisplay(ServerLevel level, SignBlockEntity sign, NationsSignState state) {
        Component count = Component.literal(state.joiners.size() + " / " + state.minPlayers + " joined")
                .withStyle(ChatFormatting.AQUA);
        boolean painted = false;
        for (boolean front : new boolean[]{true, false}) {
            SignText text = sign.getText(front);
            if (!isNationsTag(lineString(text, 0))) continue;
            sign.setText(text.setMessage(2, count), front);
            painted = true;
        }
        if (!painted) return;
        // setText flags chunk dirty; the BE update packet is sent to nearby clients.
        sign.setChanged();
        level.sendBlockUpdated(sign.getBlockPos(), sign.getBlockState(), sign.getBlockState(),
                Block.UPDATE_CLIENTS);
        RegionsMOBA.LOGGER.debug("Sign at {} now shows {}/{}",
                sign.getBlockPos(), state.joiners.size(), state.minPlayers);
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
