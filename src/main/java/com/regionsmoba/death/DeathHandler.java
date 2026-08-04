package com.regionsmoba.death;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.chamber.TrialChamber;
import com.regionsmoba.classes.KitGrant;
import com.regionsmoba.classes.impl.BerserkerAbility;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.hud.LifelineBars;
import com.regionsmoba.lifeline.LifelineState;
import com.regionsmoba.lifeline.Lives;
import com.regionsmoba.lobby.LobbyFlow;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.team.TeamPassives;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;

import java.util.Set;

/**
 * Handles player death and respawn:
 *
 *   - On AFTER_DEATH: decrement lives. Ocean players whose lifeline (Conduit) is
 *     destroyed go straight to spectator regardless of remaining lives, per
 *     docs/src-md/teams/ocean.md and gameplay/timeline.md.
 *
 *   - On AFTER_RESPAWN: teleport the new player object to their team spawn and
 *     re-apply the team passive, since respawn creates a fresh ServerPlayer
 *     instance and clears mob effects. Spectators get teleported to the lobby
 *     spawn instead.
 */
public final class DeathHandler {

    private DeathHandler() {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // Trial-chamber kill credit: fire on EVERY living-entity death, not just
            // player deaths. The chamber tracks wave mobs by UUID; non-wave deaths
            // are no-ops in TrialChamber.onLivingDeath.
            TrialChamber.get().onLivingDeath(entity, attackerOf(source));

            // Berserker heart-stack credit on enemy player deaths anywhere on the map.
            BerserkerAbility.onEnemyDeath(entity, source);

            if (!(entity instanceof ServerPlayer victim)) return;
            if (!MatchManager.get().isActive()) return;
            MatchPlayerState state = TeamAssignments.get().state(victim.getUUID());
            if (state == null || state.spectator) return;

            // Berserker death penalty (-5 hearts) applies before life decrement.
            if (state.biomeClass == com.regionsmoba.team.BiomeClass.MOUNTAIN_BERSERKER) {
                BerserkerAbility.onBerserkerDeath(victim);
            }

            if (state.team == BiomeTeam.OCEAN && LifelineState.get().oceanPermadeath) {
                Lives.enterSpectator(victim, state, "Conduit destroyed — final death");
                return;
            }
            Lives.loseLife(victim, "killed in combat");
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!MatchManager.get().isActive()) return;
            MatchPlayerState state = TeamAssignments.get().state(newPlayer.getUUID());
            if (state == null) return;
            // Respawn replaces the ServerPlayer instance; Entity identity (id/hashCode)
            // is per-instance, so the old instance is never deduped out of the boss bars'
            // player sets by addPlayer's Set.add(). Detach it explicitly on both branches
            // below, or it silently keeps receiving every future bar-update packet.
            LifelineBars.get().removePlayer(oldPlayer);
            if (state.spectator) {
                teleportToLobby(newPlayer);
                LifelineBars.get().addSpectator(newPlayer);
                return;
            }
            if (state.team == null) return;
            LobbyFlow.teleportToTeamSpawn(newPlayer, state.team);
            TeamPassives.apply(newPlayer, state.team);
            LifelineBars.get().addPlayer(newPlayer, state.team);
            if (state.biomeClass != null) {
                KitGrant.grant(newPlayer, state.biomeClass);
                if (state.biomeClass == com.regionsmoba.team.BiomeClass.MOUNTAIN_BERSERKER) {
                    BerserkerAbility.reapply(newPlayer);
                }
            }
        });

        // Mid-match disconnect: detach from the timer and any lifeline bar so the
        // connection doesn't keep receiving bar-update packets after it's gone.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                LifelineBars.get().removePlayer(handler.player));
    }

    private static Player attackerOf(DamageSource source) {
        if (source.getEntity() instanceof Player p) return p;
        if (source.getDirectEntity() instanceof Player p) return p;
        return null;
    }

    private static void teleportToLobby(ServerPlayer player) {
        BlockPosData lobby = RegionsConfig.get().lobbySpawn;
        if (lobby == null) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerLevel level = server.getLevel(lobby.dimensionKey());
        if (level == null) return;
        player.teleportTo(level, lobby.x() + 0.5, lobby.y(), lobby.z() + 0.5,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
        RegionsMOBA.LOGGER.debug("Teleported spectator {} to lobby spawn",
                player.getGameProfile().name());
    }
}
