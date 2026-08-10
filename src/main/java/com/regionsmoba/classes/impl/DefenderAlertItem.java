package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ocean Defender — Alert Item.
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Right-click the prismarine shard to place it at your feet. It plays an
 *   alert sound to all Ocean teammates if an enemy picks it up. An enemy
 *   holding an item in either hand cannot pick it up.
 *
 * The shard is dropped as a real {@link ItemEntity} with pickup suppressed, and
 * a scan handles the "pickup" itself. Letting vanilla do the pickup would work,
 * but the AFTER-the-fact event gives no reliable handle on who took it, and the
 * empty-hands rule has to be checked before the item leaves the ground.
 */
public final class DefenderAlertItem {

    public static final double PICKUP_RADIUS = 1.5;
    public static final int SCAN_INTERVAL_TICKS = 10;

    private record Alert(UUID owner, BiomeTeam team, UUID itemEntity) {}

    private static final Map<UUID, Alert> ALERTS = new HashMap<>();

    private DefenderAlertItem() {}

    public static boolean tryRightClick(ServerPlayer defender, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.ALERT_ITEM)) return false;
        MatchPlayerState self = TeamAssignments.get().state(defender.getUUID());
        if (self == null || self.team == null) return true;

        ServerLevel level = defender.serverLevel();
        ItemStack dropped = stack.copyWithCount(1);
        ItemEntity entity = new ItemEntity(level,
                defender.getX(), defender.getY() + 0.1, defender.getZ(), dropped);
        // Never picked up by vanilla — the scan below decides who may take it.
        entity.setNeverPickUp();
        entity.setUnlimitedLifetime();
        level.addFreshEntity(entity);

        ALERTS.put(entity.getUUID(), new Alert(defender.getUUID(), self.team, entity.getUUID()));
        stack.shrink(1);
        tell(defender, "Alert Item placed.", ChatFormatting.AQUA);
        return true;
    }

    /** Watches each placed shard for an empty-handed enemy standing over it. */
    public static void tick(MinecraftServer server, long globalTick) {
        if (ALERTS.isEmpty() || server == null) return;
        if (globalTick % SCAN_INTERVAL_TICKS != 0) return;

        ALERTS.entrySet().removeIf(entry -> {
            Alert alert = entry.getValue();
            ItemEntity item = findItem(server, alert.itemEntity());
            if (item == null || item.isRemoved()) return true;

            AABB box = item.getBoundingBox().inflate(PICKUP_RADIUS);
            List<Player> nearby = item.level().getEntitiesOfClass(Player.class, box, p -> {
                MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
                if (s == null || s.team == null || s.spectator) return false;
                if (s.team == alert.team()) return false;
                // Doc rule: an enemy with anything in either hand can't take it.
                return p.getMainHandItem().isEmpty() && p.getOffhandItem().isEmpty();
            });
            if (nearby.isEmpty()) return false;

            Player thief = nearby.get(0);
            item.discard();
            raiseAlarm(server, alert, thief);
            return true;
        });
    }

    private static void raiseAlarm(MinecraftServer server, Alert alert, Player thief) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
            if (s == null || s.team != alert.team()) continue;
            p.level().playSound(null, p.blockPosition(), SoundEvents.CONDUIT_ACTIVATE,
                    SoundSource.PLAYERS, 1.0f, 1.0f);
            tell(p, "Alert Item taken by " + thief.getGameProfile().getName() + "!", ChatFormatting.RED);
        }
    }

    private static ItemEntity findItem(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof ItemEntity item) return item;
        }
        return null;
    }

    public static void clearForPlayer(UUID player) {
        ALERTS.values().removeIf(alert -> alert.owner().equals(player));
    }

    public static void clearAll() {
        ALERTS.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
