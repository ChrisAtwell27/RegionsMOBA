package com.regionsmoba.command.sub;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.regionsmoba.command.CommandHelpers;
import com.regionsmoba.command.click.PendingRegistration;
import com.regionsmoba.command.click.PendingRegistrationStore;
import com.regionsmoba.command.click.VisualMarkers;
import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.MobDeposit;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.config.RegionsConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Deposit registration commands. All registration is click-driven (see ClickHandler).
 *
 * Covers from docs/src-md/reference/commands.md (Target commands section, deposit rows):
 *   addoredeposit <time> / removeoredeposit / listoredeposits
 *   addblockdeposit <time> / removeblockdeposit / listblockdeposits
 *   addmobdeposit <mob> <time> / removemobdeposit / listmobdeposits
 *   done
 *
 * Per the docs: ore + block deposits use a registration session (first click locks
 * the type, subsequent clicks add more); mob deposits are one-shot per click.
 */
public final class DepositCommands {

    /** Suggests every entity-type id in the registry, for the addmobdeposit <mob> arg. */
    private static final SuggestionProvider<CommandSourceStack> MOB_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);

    private DepositCommands() {}

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        registerOre(root);
        registerBlock(root);
        registerMob(root);
        registerDone(root);
    }

    // ---- Ore deposits ----

    private static void registerOre(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("addoredeposit")
                .then(Commands.argument("time", IntegerArgumentType.integer(1))
                        .executes(ctx -> startOreSession(
                                ctx.getSource(), IntegerArgumentType.getInteger(ctx, "time")))));

        root.then(Commands.literal("removeoredeposit").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.REMOVE_ORE_DEPOSIT),
                "Right-click an ore deposit to remove it.")));

        root.then(Commands.literal("listoredeposits").executes(ctx -> {
            if (RegionsConfig.get().oreDeposits.isEmpty()) {
                CommandHelpers.info(ctx.getSource(), "No ore deposits registered.");
                return 1;
            }
            for (OreDeposit d : RegionsConfig.get().oreDeposits) {
                CommandHelpers.info(ctx.getSource(),
                        d.oreId() + " at " + d.pos() + " (regen " + d.regenSeconds() + "s)");
            }
            return 1;
        }));
    }

    private static int startOreSession(CommandSourceStack src, int time) {
        PendingRegistration reg = new PendingRegistration(PendingRegistration.Kind.ADD_ORE_DEPOSIT_SESSION);
        reg.regenSeconds = time;
        return beginPending(src, reg,
                "Ore-deposit session started (regen " + time + "s). Right-click each ore block to register; /regions done to finish.");
    }

    // ---- Block deposits ----

    private static void registerBlock(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("addblockdeposit")
                .then(Commands.argument("time", IntegerArgumentType.integer(1))
                        .executes(ctx -> startBlockSession(
                                ctx.getSource(), IntegerArgumentType.getInteger(ctx, "time")))));

        root.then(Commands.literal("removeblockdeposit").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.REMOVE_BLOCK_DEPOSIT),
                "Right-click a block deposit to remove it.")));

        root.then(Commands.literal("listblockdeposits").executes(ctx -> {
            if (RegionsConfig.get().blockDeposits.isEmpty()) {
                CommandHelpers.info(ctx.getSource(), "No block deposits registered.");
                return 1;
            }
            for (BlockDeposit d : RegionsConfig.get().blockDeposits) {
                CommandHelpers.info(ctx.getSource(),
                        d.blockId() + " at " + d.pos() + " (regen " + d.regenSeconds() + "s)");
            }
            return 1;
        }));
    }

    private static int startBlockSession(CommandSourceStack src, int time) {
        PendingRegistration reg = new PendingRegistration(PendingRegistration.Kind.ADD_BLOCK_DEPOSIT_SESSION);
        reg.regenSeconds = time;
        return beginPending(src, reg,
                "Block-deposit session started (regen " + time + "s). Right-click each block to register; /regions done to finish.");
    }

    // ---- Mob deposits ----

    private static void registerMob(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("addmobdeposit")
                .then(Commands.argument("mob", StringArgumentType.word())
                        .suggests(MOB_SUGGESTIONS)
                        .then(Commands.argument("time", IntegerArgumentType.integer(1))
                                .executes(ctx -> startMobAdd(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mob"),
                                        IntegerArgumentType.getInteger(ctx, "time"))))));

        root.then(Commands.literal("removemobdeposit").executes(ctx -> beginPending(
                ctx.getSource(), new PendingRegistration(PendingRegistration.Kind.REMOVE_MOB_DEPOSIT),
                "Right-click a mob deposit to remove it.")));

        root.then(Commands.literal("listmobdeposits").executes(ctx -> {
            if (RegionsConfig.get().mobDeposits.isEmpty()) {
                CommandHelpers.info(ctx.getSource(), "No mob deposits registered.");
                return 1;
            }
            for (MobDeposit d : RegionsConfig.get().mobDeposits) {
                CommandHelpers.info(ctx.getSource(),
                        d.mobId() + " at " + d.pos() + " (every " + d.intervalSeconds() + "s)");
            }
            return 1;
        }));
    }

    private static int startMobAdd(CommandSourceStack src, String mobId, int interval) {
        ResourceLocation id = ResourceLocation.tryParse(mobId);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            CommandHelpers.fail(src, "Unknown entity type: " + mobId);
            return 0;
        }
        PendingRegistration reg = new PendingRegistration(PendingRegistration.Kind.ADD_MOB_DEPOSIT);
        reg.mobId = id.toString();
        reg.regenSeconds = interval;
        return beginPending(src, reg,
                "Right-click a block to register as a " + reg.mobId + " spawn point (every " + interval + "s).");
    }

    // ---- /regions done ----

    private static void registerDone(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("done").executes(ctx -> {
            ServerPlayer player;
            try {
                player = ctx.getSource().getPlayerOrException();
            } catch (CommandSyntaxException e) {
                CommandHelpers.fail(ctx.getSource(), "/regions done must be run by a player.");
                return 0;
            }
            PendingRegistration prev = PendingRegistrationStore.clear(player.getUUID());
            if (prev == null) {
                CommandHelpers.warn(ctx.getSource(), "No pending registration to end.");
                return 0;
            }
            if (!prev.markedPositions.isEmpty()) {
                VisualMarkers.revertAll(player, player.serverLevel(), prev.markedPositions);
            }
            String summary = prev.isSession()
                    ? prev.kind + " ended; " + prev.registered + " block(s) registered"
                    : prev.kind + " cancelled";
            CommandHelpers.ok(ctx.getSource(), summary);
            return 1;
        }));
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
