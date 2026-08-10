package com.regionsmoba.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.regionsmoba.command.sub.AbortCommand;
import com.regionsmoba.command.sub.AreaCommands;
import com.regionsmoba.command.sub.DebugCommands;
import com.regionsmoba.command.sub.DepositCommands;
import com.regionsmoba.command.sub.PositionCommands;
import com.regionsmoba.command.sub.ResetCommand;
import com.regionsmoba.command.sub.SkipSeasonCommand;
import com.regionsmoba.command.sub.StartCommand;
import com.regionsmoba.command.sub.StatusCommand;
import com.regionsmoba.command.sub.TargetCommands;
import com.regionsmoba.command.sub.VersionCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Registers the /regions command tree. Each subcommand owns its own register method
 * so adding a new one is one import + one call here.
 *
 * Permission: every subcommand requires op (level 2). The single requires() on the
 * root applies to all children.
 */
public final class RegionsCommand {

    public static final String ROOT = "regions";

    private RegionsCommand() {}

    public static void registerAll() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS));

        VersionCommand.register(root);
        StatusCommand.register(root);
        StartCommand.register(root);
        AbortCommand.register(root);
        SkipSeasonCommand.register(root);
        ResetCommand.register(root);
        PositionCommands.register(root);
        AreaCommands.register(root);
        TargetCommands.register(root);
        DepositCommands.register(root);
        DebugCommands.register(root);

        dispatcher.register(root);
    }
}
