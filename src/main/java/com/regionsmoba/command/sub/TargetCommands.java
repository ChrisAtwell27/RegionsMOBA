package com.regionsmoba.command.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.regionsmoba.command.CommandHelpers;
import com.regionsmoba.command.click.PendingRegistration;
import com.regionsmoba.command.click.PendingRegistrationStore;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.team.BiomeTeam;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;

import java.util.Map;
import java.util.Optional;

/**
 * Click-driven target commands and the one-step trader spawn.
 *
 * Covers from docs/src-md/reference/commands.md (Target commands section):
 *   setconduit / unsetconduit
 *   setfurnace / unsetfurnace
 *   setcomposter / unsetcomposter
 *   settrader <biome> / unsettrader <biome> / listtraders
 *   spawntrader <biome>   (Position commands section, but trader-related)
 */
public final class TargetCommands {

    private TargetCommands() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        registerLifelineTargets(root);
        registerTrader(root);
    }

    // ---- Conduit / Furnace / Composter ----

    private static void registerLifelineTargets(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("setconduit").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.SET_CONDUIT),
                "Right-click the ocean conduit block to register it.")));
        root.then(Commands.literal("unsetconduit").executes(ctx -> {
            RegionsConfig.get().conduit = null;
            RegionsConfig.save();
            CommandHelpers.ok(ctx.getSource(), "Conduit cleared.");
            return 1;
        }));

        root.then(Commands.literal("setfurnace").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.SET_FURNACE),
                "Right-click the nether furnace block to register it.")));
        root.then(Commands.literal("unsetfurnace").executes(ctx -> {
            RegionsConfig.get().furnace = null;
            RegionsConfig.save();
            CommandHelpers.ok(ctx.getSource(), "Furnace cleared.");
            return 1;
        }));

        root.then(Commands.literal("setcomposter").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.SET_COMPOSTER),
                "Right-click the plains composter block to register it.")));
        root.then(Commands.literal("unsetcomposter").executes(ctx -> {
            RegionsConfig.get().composter = null;
            RegionsConfig.save();
            CommandHelpers.ok(ctx.getSource(), "Composter cleared.");
            return 1;
        }));
    }

    // ---- Traders ----

    private static void registerTrader(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("settrader")
                .then(Commands.argument("biome", StringArgumentType.word())
                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                        .executes(ctx -> startSetTrader(
                                ctx.getSource(), StringArgumentType.getString(ctx, "biome")))));

        root.then(Commands.literal("unsettrader")
                .then(Commands.argument("biome", StringArgumentType.word())
                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                        .executes(ctx -> unsetTrader(
                                ctx.getSource(), StringArgumentType.getString(ctx, "biome")))));

        root.then(Commands.literal("listtraders").executes(ctx -> {
            Map<String, TraderRef> traders = RegionsConfig.get().traders;
            if (traders.isEmpty()) {
                CommandHelpers.info(ctx.getSource(), "No traders registered.");
                return 1;
            }
            for (BiomeTeam team : BiomeTeam.values()) {
                TraderRef ref = traders.get(team.id());
                if (ref == null) continue;
                CommandHelpers.info(ctx.getSource(),
                        team.displayName() + ": " + ref.entityUuid() + " at (" + ref.pos() + ")");
            }
            return 1;
        }));

        root.then(Commands.literal("spawntrader")
                .then(Commands.argument("biome", StringArgumentType.word())
                        .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                        .executes(ctx -> spawnTrader(
                                ctx.getSource(), StringArgumentType.getString(ctx, "biome")))));
    }

    private static int startSetTrader(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        PendingRegistration reg = new PendingRegistration(PendingRegistration.Kind.SET_TRADER);
        reg.team = team.get();
        return beginPending(src, reg,
                "Right-click an entity to register as the " + team.get().displayName() + " trader.");
    }

    private static int unsetTrader(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        if (RegionsConfig.get().traders.remove(team.get().id()) == null) {
            CommandHelpers.warn(src, team.get().displayName() + " trader was not set.");
            return 0;
        }
        RegionsConfig.save();
        CommandHelpers.ok(src, team.get().displayName() + " trader cleared.");
        return 1;
    }

    private static int spawnTrader(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "spawntrader must be run by a player.");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos pos = CommandHelpers.senderBlockPos(src);
        EntityType<?> type = traderTypeFor(team.get());
        Entity entity = type.spawn(level, pos, MobSpawnType.COMMAND);
        if (entity == null) {
            CommandHelpers.fail(src, "Failed to spawn " + team.get().displayName() + " trader entity.");
            return 0;
        }
        RegionsConfig.get().traders.put(
                team.get().id(),
                new TraderRef(entity.getUUID(), BlockPosData.of(level, pos)));
        RegionsConfig.save();
        CommandHelpers.ok(src, team.get().displayName() + " trader spawned and registered at (" + pos + ")");
        return 1;
    }

    private static EntityType<?> traderTypeFor(BiomeTeam team) {
        return team == BiomeTeam.NETHER ? EntityType.PIGLIN : EntityType.VILLAGER;
    }

    // ---- Shared ----

    private static int beginPending(CommandSourceStack src, PendingRegistration reg, String prompt) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (CommandSyntaxException e) {
            CommandHelpers.fail(src, "This command must be run by a player.");
            return 0;
        }
        PendingRegistration prev = PendingRegistrationStore.get(player.getUUID());
        PendingRegistrationStore.set(player.getUUID(), reg);
        if (prev != null) {
            CommandHelpers.warn(src, "Replaced previous pending registration: " + prev.kind);
        }
        CommandHelpers.info(src, prompt);
        return 1;
    }
}
