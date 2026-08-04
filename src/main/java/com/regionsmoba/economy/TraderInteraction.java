package com.regionsmoba.economy;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

import java.util.UUID;

/** Opens a team's trade screen when its registered trader entity is right-clicked. */
public final class TraderInteraction {

    private TraderInteraction() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            if (!MatchManager.get().isActive()) return InteractionResult.PASS;

            BiomeTeam team = teamOfTrader(entity.getUUID());
            if (team == null) return InteractionResult.PASS;

            // Merchant.openTradingScreen is the only path that sends the client a
            // ClientboundMerchantOffersPacket (via Player.sendMerchantOffers once
            // openMenu returns a present container id) — calling ServerPlayer.openMenu
            // directly, as before, leaves the client's offer list empty.
            Component title = Component.literal(team.displayName() + " Trader");
            RegionsMerchant merchant = new RegionsMerchant(TradeTables.offersFor(team));
            merchant.setTradingPlayer(sp);
            merchant.openTradingScreen(sp, title, 1);
            return InteractionResult.SUCCESS;
        });
    }

    private static BiomeTeam teamOfTrader(UUID entityId) {
        for (BiomeTeam team : BiomeTeam.values()) {
            TraderRef ref = RegionsConfig.get().trader(team);
            if (ref != null && entityId.equals(ref.entityUuid())) return team;
        }
        return null;
    }
}
