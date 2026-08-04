package com.regionsmoba.economy;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.UUID;

/** Opens a team's trade screen when its registered trader entity is right-clicked. */
public final class TraderInteraction {

    private TraderInteraction() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            if (!MatchManager.get().isActive()) return InteractionResult.PASS;

            BiomeTeam team = teamOfTrader(entity.getUUID());
            if (team == null) return InteractionResult.PASS;

            Component title = Component.literal(team.displayName() + " Trader");
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new MerchantMenu(
                            id, inv, new RegionsMerchant(TradeTables.offersFor(team))),
                    title));
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
