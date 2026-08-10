package com.regionsmoba.lobby;

import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.classes.KitGrant;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.events.PermanentLossTracker;
import com.regionsmoba.hud.LifelineBars;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import com.regionsmoba.team.TeamPassives;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Central orchestrator for the lobby → in-match flow:
 *   1. Match start: every joiner gets a compass.
 *   2. Right-click compass: open team picker (or class picker if team already chosen).
 *   3. Team picked: teleport to team spawn, apply passive, open class picker.
 *   4. Class picked: kit grant is deferred to slice 9; player is "in match".
 *
 * The compass is recognised by its custom-name component, not by item type — so
 * a player swapping it out won't accidentally trigger pickers via a bare compass.
 */
public final class LobbyFlow {

    private static final Component COMPASS_NAME = Component.literal("Team Picker")
            .withStyle(ChatFormatting.GOLD);

    private LobbyFlow() {}

    public static void register() {
        UseItemCallback.EVENT.register(LobbyFlow::onUseItem);
    }

    /** Called by MatchManager when a match starts. Hands every joiner a compass. */
    public static void onMatchStart(MinecraftServer server, Set<UUID> joiners) {
        for (UUID id : joiners) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            giveCompass(p);
            tell(p, "Right-click your compass to pick a nation.", ChatFormatting.AQUA);
        }
    }

    private static InteractionResultHolder<ItemStack> onUseItem(
            Player player, net.minecraft.world.level.Level world, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(held);
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.pass(held);
        ItemStack stack = sp.getMainHandItem();
        if (!isPickerCompass(stack)) return InteractionResultHolder.pass(held);

        TeamAssignments ta = TeamAssignments.get();
        if (!ta.isInMatch(sp.getUUID())) return InteractionResultHolder.pass(held);
        MatchPlayerState state = ta.state(sp.getUUID());
        if (state == null) return InteractionResultHolder.pass(held);

        if (state.team == null) {
            openTeamPicker(sp);
        } else if (state.biomeClass == null) {
            openClassPicker(sp, state.team);
        } else {
            tell(sp, "You're already in the match as " + state.biomeClass.displayName() + ".", ChatFormatting.GRAY);
        }
        return net.minecraft.world.InteractionResultHolder.success(sp.getItemInHand(hand));
    }

    public static void onTeamPicked(Player player, BiomeTeam team) {
        if (!(player instanceof ServerPlayer sp)) return;
        TeamAssignments.AssignResult result = TeamAssignments.get().assignTeam(sp.getUUID(), team);
        sp.closeContainer();
        switch (result) {
            case OK -> {
                PermanentLossTracker.get().recordJoin(team);
                tell(sp, "Joined " + team.displayName() + ".", team.color());
                teleportToTeamSpawn(sp, team);
                TeamPassives.apply(sp, team);
                LifelineBars.get().addPlayer(sp, team);
                // Slight delay to ensure teleport completes before opening next menu.
                openClassPicker(sp, team);
            }
            case TEAM_FULL -> {
                tell(sp, team.displayName() + " is full (cap " + TeamAssignments.get().teamCap() + "). Pick another.",
                        ChatFormatting.YELLOW);
                openTeamPicker(sp);
            }
            case ALREADY_ASSIGNED -> tell(sp, "You already have a team.", ChatFormatting.GRAY);
            case UNKNOWN_PLAYER -> tell(sp, "You're not in the current match.", ChatFormatting.RED);
        }
    }

    public static void onClassPicked(Player player, BiomeClass biomeClass) {
        if (!(player instanceof ServerPlayer sp)) return;
        // Captured before assignClass, which overwrites it.
        MatchPlayerState state = TeamAssignments.get().state(sp.getUUID());
        BiomeClass previous = state == null ? null : state.biomeClass;

        boolean ok = TeamAssignments.get().assignClass(sp.getUUID(), biomeClass);
        sp.closeContainer();
        if (!ok) {
            tell(sp, "Class pick failed (no team set, or class doesn't match team).", ChatFormatting.RED);
            return;
        }
        tell(sp, "Class: " + biomeClass.displayName() + ".", biomeClass.team().color());

        if (previous == null) {
            // First pick of the match: a full grant onto a clean inventory.
            KitGrant.grant(sp, biomeClass);
        } else {
            // Mid-match switch: keep everything the player earned, strip only the
            // old class's named ability items.
            KitGrant.switchClass(sp, previous, biomeClass);
        }
    }

    // ---- Helpers ----

    /** Public so the debug tooling can drop an operator straight into team selection. */
    public static void openTeamPicker(ServerPlayer sp) {
        sp.openMenu(new SimpleMenuProvider(
                (syncId, inv, player) -> new TeamPickerMenu(syncId, inv),
                TeamPickerMenu.TITLE));
    }

    /** Public so the debug team-switch can re-prompt for a class on the new team. */
    public static void openClassPicker(ServerPlayer sp, BiomeTeam team) {
        sp.openMenu(new SimpleMenuProvider(
                (syncId, inv, player) -> new ClassPickerMenu(syncId, inv, team),
                ClassPickerMenu.title(team)));
    }

    private static void giveCompass(ServerPlayer sp) {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponents.CUSTOM_NAME, COMPASS_NAME);
        Inventory inv = sp.getInventory();
        inv.clearContent();
        inv.add(stack);
    }

    private static boolean isPickerCompass(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.COMPASS)) return false;
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && COMPASS_NAME.getString().equals(name.getString());
    }

    public static void teleportToTeamSpawn(ServerPlayer sp, BiomeTeam team) {
        List<BlockPosData> spawns = RegionsConfig.get().spawnsFor(team);
        if (spawns.isEmpty()) {
            tell(sp, "No " + team.displayName() + " spawn registered — staying in lobby.", ChatFormatting.YELLOW);
            RegionsMOBA.LOGGER.warn("No spawn registered for team {}", team.id());
            return;
        }
        // Round-robin pick: simplest stable distribution. Pick by player UUID hash for determinism.
        BlockPosData spawn = spawns.get(Math.floorMod(sp.getUUID().hashCode(), spawns.size()));
        MinecraftServer server = sp.level().getServer();
        ServerLevel level = server != null ? server.getLevel(spawn.dimensionKey()) : null;
        if (level == null) {
            tell(sp, "Spawn dimension " + spawn.dimensionOrDefault() + " is not loaded.", ChatFormatting.RED);
            return;
        }
        sp.teleportTo(level, spawn.x() + 0.5, spawn.y(), spawn.z() + 0.5,
                Set.<RelativeMovement>of(), sp.getYRot(), sp.getXRot());
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
