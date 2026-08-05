package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * Nether Rift Walker — Blaze rod rift (simplified to Nether team spawn teleport).
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Right-click rod opens a destination list (teammates / 3 enemy spawn markers /
 *   Nether team spawn). 10s countdown. Sneaking teammates pulled along.
 *   Cooldown = 30s × travellers (Rift Walker + 1-3 teammates).
 *
 * Slice 13 MVP: right-click teleports the Rift Walker (only) to the Nether team
 * spawn instantly. 30s cd. Applies Weakness II for 5s on arrival.
 *
 * The destination menu, 10s countdown, party rifting, and enemy-lifeline target
 * filtering are all deferred — they need a destination picker UI + party
 * coordination logic.
 */
public final class RiftWalkerAbility {

    public static final int COOLDOWN_SECONDS = 30;
    public static final int WEAKNESS_DURATION_TICKS = 5 * 20;

    private RiftWalkerAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.RIFT_BLAZE_ROD)) return false;
        if (!Cooldowns.get().ready(player, "rift:walk")) {
            tell(player, "Rift on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, "rift:walk") + "s)", ChatFormatting.GRAY);
            return true;
        }
        MatchPlayerState selfState = TeamAssignments.get().state(player.getUUID());
        if (selfState == null || selfState.team != BiomeTeam.NETHER) {
            tell(player, "Rift available to Nether only.", ChatFormatting.RED);
            return true;
        }
        List<BlockPosData> spawns = RegionsConfig.get().spawnsFor(BiomeTeam.NETHER);
        if (spawns.isEmpty()) {
            tell(player, "No Nether team spawn registered.", ChatFormatting.RED);
            return true;
        }
        BlockPosData target = spawns.get(0);
        MinecraftServer server = player.level().getServer();
        if (server == null) return true;
        ServerLevel level = server.getLevel(target.dimensionKey());
        if (level == null) {
            tell(player, "Destination dimension not loaded.", ChatFormatting.RED);
            return true;
        }
        player.teleportTo(level, target.x() + 0.5, target.y(), target.z() + 0.5,
                Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
        player.addEffect(new MobEffectInstance(
                MobEffects.WEAKNESS, WEAKNESS_DURATION_TICKS, 1, true, false, true));
        Cooldowns.get().set(player, "rift:walk", COOLDOWN_SECONDS);
        tell(player, "Rift to Nether team spawn.", ChatFormatting.LIGHT_PURPLE);
        return true;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
