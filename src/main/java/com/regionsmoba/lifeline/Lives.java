package com.regionsmoba.lifeline;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.events.PermanentLossTracker;
import com.regionsmoba.hud.LifelineBars;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.UUID;

/**
 * Single point for "this player loses a life". Used by:
 *   - the death handler (slice 7)
 *   - Mountain Blood Tribute failure
 *   - any future cause that decrements lives
 *
 * Per docs/src-md/gameplay/timeline.md: 3 lives by default; at 0, spectator for
 * the rest of the match. We push the player into spectator gamemode and flip
 * the state's spectator flag.
 */
public final class Lives {

    private Lives() {}

    /** Returns true if the player has now run out of lives and entered spectator. */
    public static boolean loseLife(ServerPlayer player, String reason) {
        UUID id = player.getUUID();
        MatchPlayerState state = TeamAssignments.get().state(id);
        if (state == null || state.spectator) return false;
        state.lives = Math.max(0, state.lives - 1);
        Component msg = Component.literal("You lost a life: " + reason + " (lives: " + state.lives + ")")
                .withStyle(ChatFormatting.YELLOW);
        player.sendSystemMessage(msg);
        if (state.lives <= 0) {
            return enterSpectator(player, state, "out of lives");
        }
        return false;
    }

    /** Force a player into spectator state for the rest of the match. */
    public static boolean enterSpectator(ServerPlayer player, MatchPlayerState state, String reason) {
        boolean firstTransition = !state.spectator;
        state.spectator = true;
        state.lives = 0;
        player.setGameMode(GameType.SPECTATOR);
        player.sendSystemMessage(Component.literal("Spectator: " + reason)
                .withStyle(ChatFormatting.GRAY));
        RegionsMOBA.LOGGER.info("{} → spectator ({})", player.getGameProfile().name(), reason);
        if (firstTransition && state.team != null) {
            PermanentLossTracker.get().recordPermanentLoss(state.team, player.level().getServer());
        }
        // Route to the spectator bar set even outside a death/respawn cycle (e.g.
        // PlainsQuota elimination, /regions debug lives ... 0). The timer and the
        // player's own-team bar are already attached, so this only adds the rest.
        LifelineBars.get().addSpectator(player);
        return true;
    }

    /** Convenience that walks UUID → ServerPlayer via the player list. */
    public static boolean loseLife(Player player, String reason) {
        if (player instanceof ServerPlayer sp) return loseLife(sp, reason);
        return false;
    }
}
