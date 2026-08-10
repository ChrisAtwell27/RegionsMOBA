package com.regionsmoba.lifeline;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Ocean lifeline.
 *
 * Per docs/src-md/teams/ocean.md:
 *   "A single conduit block. 200 HP. Each successful break by an enemy removes
 *    1 HP and respawns the block. At 0 HP, every ocean player's next death is
 *    final regardless of lives remaining."
 *
 * Implementation: hook PlayerBlockBreakEvents.BEFORE on the registered conduit
 * position; cancel the break (so the block stays = "respawns" instantly), and
 * decrement HP if the breaker is an enemy. Ocean teammates can't damage their
 * own lifeline either — break is silently cancelled.
 */
public final class ConduitLifeline {

    private ConduitLifeline() {}

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!MatchManager.get().isActive()) return true;
            BlockPosData conduit = RegionsConfig.get().conduit;
            if (conduit == null) return true;
            if (pos.getX() != conduit.x() || pos.getY() != conduit.y() || pos.getZ() != conduit.z()) return true;
            if (!world.dimension().location().toString().equals(conduit.dimensionOrDefault())) return true;
            if (!(player instanceof ServerPlayer sp)) return false;

            MatchPlayerState attackerState = TeamAssignments.get().state(sp.getUUID());
            if (attackerState == null || attackerState.team == null) {
                // Not a match participant — block break entirely.
                feedback(sp, "You can't break the conduit.", ChatFormatting.RED);
                return false;
            }
            if (attackerState.team == BiomeTeam.OCEAN) {
                feedback(sp, "Ocean players can't damage their own conduit.", ChatFormatting.YELLOW);
                return false;
            }

            // Enemy break — register the hit.
            LifelineState ls = LifelineState.get();
            if (ls.conduitHp <= 0) {
                feedback(sp, "Conduit is already destroyed.", ChatFormatting.GRAY);
                return false;
            }
            ls.conduitHp--;
            MinecraftServer server = sp.level().getServer();
            RegionsMOBA.LOGGER.info("Conduit hit by {} ({}); HP {}/{}",
                    sp.getGameProfile().getName(), attackerState.team.id(), ls.conduitHp, LifelineState.CONDUIT_MAX_HP);
            broadcastToOcean(server, Component.literal("Conduit: " + ls.conduitHp + " / " + LifelineState.CONDUIT_MAX_HP)
                    .withStyle(ChatFormatting.AQUA));
            if (ls.conduitHp <= 0) {
                ls.oceanPermadeath = true;
                broadcastToOcean(server, Component.literal("CONDUIT DESTROYED — your next death is FINAL.")
                        .withStyle(ChatFormatting.DARK_RED));
            }
            // Cancel the break so the block "respawns" (stays).
            return false;
        });
    }

    private static void feedback(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }

    private static void broadcastToOcean(MinecraftServer server, Component msg) {
        if (server == null) return;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.OCEAN) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
