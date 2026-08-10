package com.regionsmoba.command.click;

import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.MobDeposit;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.lobby.NationsSignHandler;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Iterator;

/**
 * Dispatches click events to the operator's pending registration. Block clicks feed
 * conduit/furnace/composter/deposit registrations; entity clicks feed trader
 * registration.
 *
 * One-shot kinds (SET_*, REMOVE_*, ADD_MOB_DEPOSIT) clear the pending registration
 * after handling. Session kinds (ADD_ORE/BLOCK_DEPOSIT_SESSION) persist until
 * /regions done is run.
 */
public final class ClickHandler {

    private ClickHandler() {}

    public static void register() {
        UseBlockCallback.EVENT.register(ClickHandler::onUseBlock);
        UseEntityCallback.EVENT.register(ClickHandler::onUseEntity);
    }

    private static InteractionResult onUseBlock(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level world,
            InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        BlockPos pos = hitResult.getBlockPos();
        PendingRegistration reg = PendingRegistrationStore.get(sp.getUUID());
        if (reg != null) {
            return handleBlockClick(sp, world, pos, reg);
        }
        // No pending operator registration — try [Nations] sign join/leave.
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) world;
        if (NationsSignHandler.handleClick(sp, level, pos)) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult onUseEntity(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level world,
            InteractionHand hand,
            Entity entity,
            net.minecraft.world.phys.EntityHitResult hitResult) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        PendingRegistration reg = PendingRegistrationStore.get(sp.getUUID());
        if (reg == null) return InteractionResult.PASS;
        return handleEntityClick(sp, entity, reg);
    }

    // ---- Block dispatch ----

    private static InteractionResult handleBlockClick(
            ServerPlayer sp, net.minecraft.world.level.Level world, BlockPos pos, PendingRegistration reg) {
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) world;
        BlockPosData data = BlockPosData.of(level, pos);
        switch (reg.kind) {
            case SET_CONDUIT -> {
                RegionsConfig.get().conduit = data;
                RegionsConfig.save();
                PendingRegistrationStore.clear(sp.getUUID());
                feedback(sp, "Conduit set at (" + data + ")", ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case SET_FURNACE -> {
                RegionsConfig.get().furnace = data;
                RegionsConfig.save();
                PendingRegistrationStore.clear(sp.getUUID());
                feedback(sp, "Furnace set at (" + data + ")", ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case SET_COMPOSTER -> {
                RegionsConfig.get().composter = data;
                RegionsConfig.save();
                PendingRegistrationStore.clear(sp.getUUID());
                feedback(sp, "Composter set at (" + data + ")", ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case REMOVE_ORE_DEPOSIT -> {
                Iterator<OreDeposit> it = RegionsConfig.get().oreDeposits.iterator();
                while (it.hasNext()) {
                    OreDeposit od = it.next();
                    if (od.pos().equals(data)) {
                        it.remove();
                        RegionsConfig.save();
                        PendingRegistrationStore.clear(sp.getUUID());
                        feedback(sp, "Removed ore deposit (" + od.oreId() + " at " + data + ")", ChatFormatting.GREEN);
                        return InteractionResult.SUCCESS;
                    }
                }
                feedback(sp, "No ore deposit registered at (" + data + ")", ChatFormatting.YELLOW);
                return InteractionResult.SUCCESS;
            }
            case REMOVE_BLOCK_DEPOSIT -> {
                Iterator<BlockDeposit> it = RegionsConfig.get().blockDeposits.iterator();
                while (it.hasNext()) {
                    BlockDeposit bd = it.next();
                    if (bd.pos().equals(data)) {
                        it.remove();
                        RegionsConfig.save();
                        PendingRegistrationStore.clear(sp.getUUID());
                        feedback(sp, "Removed block deposit (" + bd.blockId() + " at " + data + ")", ChatFormatting.GREEN);
                        return InteractionResult.SUCCESS;
                    }
                }
                feedback(sp, "No block deposit registered at (" + data + ")", ChatFormatting.YELLOW);
                return InteractionResult.SUCCESS;
            }
            case REMOVE_MOB_DEPOSIT -> {
                Iterator<MobDeposit> it = RegionsConfig.get().mobDeposits.iterator();
                while (it.hasNext()) {
                    MobDeposit md = it.next();
                    if (md.pos().equals(data)) {
                        it.remove();
                        RegionsConfig.save();
                        PendingRegistrationStore.clear(sp.getUUID());
                        feedback(sp, "Removed mob deposit (" + md.mobId() + " at " + data + ")", ChatFormatting.GREEN);
                        return InteractionResult.SUCCESS;
                    }
                }
                feedback(sp, "No mob deposit registered at (" + data + ")", ChatFormatting.YELLOW);
                return InteractionResult.SUCCESS;
            }
            case ADD_ORE_DEPOSIT_SESSION -> {
                BlockState state = world.getBlockState(pos);
                String blockId = blockId(state);
                if (reg.lockedItemId == null) {
                    reg.lockedItemId = blockId;
                }
                if (!reg.lockedItemId.equals(blockId)) {
                    feedback(sp, "Session locked to " + reg.lockedItemId + "; cannot add " + blockId,
                            ChatFormatting.YELLOW);
                    return InteractionResult.SUCCESS;
                }
                if (alreadyRegisteredOre(data)) {
                    feedback(sp, "Already registered: " + data, ChatFormatting.YELLOW);
                    return InteractionResult.SUCCESS;
                }
                RegionsConfig.get().oreDeposits.add(new OreDeposit(data, blockId, reg.regenSeconds));
                RegionsConfig.save();
                reg.registered++;
                BlockPos immutable = pos.immutable();
                reg.markedPositions.add(immutable);
                VisualMarkers.mark(sp, immutable);
                feedback(sp, "Ore deposit #" + reg.registered + ": " + blockId + " at (" + data + ")",
                        ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case ADD_BLOCK_DEPOSIT_SESSION -> {
                BlockState state = world.getBlockState(pos);
                String blockId = blockId(state);
                if (reg.lockedItemId == null) {
                    reg.lockedItemId = blockId;
                }
                if (!reg.lockedItemId.equals(blockId)) {
                    feedback(sp, "Session locked to " + reg.lockedItemId + "; cannot add " + blockId,
                            ChatFormatting.YELLOW);
                    return InteractionResult.SUCCESS;
                }
                if (alreadyRegisteredBlock(data)) {
                    feedback(sp, "Already registered: " + data, ChatFormatting.YELLOW);
                    return InteractionResult.SUCCESS;
                }
                RegionsConfig.get().blockDeposits.add(new BlockDeposit(data, blockId, reg.regenSeconds));
                RegionsConfig.save();
                reg.registered++;
                BlockPos immutable = pos.immutable();
                reg.markedPositions.add(immutable);
                VisualMarkers.mark(sp, immutable);
                feedback(sp, "Block deposit #" + reg.registered + ": " + blockId + " at (" + data + ")",
                        ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case ADD_MOB_DEPOSIT -> {
                if (alreadyRegisteredMob(data)) {
                    feedback(sp, "Mob deposit already at " + data, ChatFormatting.YELLOW);
                    return InteractionResult.SUCCESS;
                }
                RegionsConfig.get().mobDeposits.add(new MobDeposit(data, reg.mobId, reg.regenSeconds));
                RegionsConfig.save();
                PendingRegistrationStore.clear(sp.getUUID());
                feedback(sp, "Mob deposit registered: " + reg.mobId + " every " + reg.regenSeconds + "s at (" + data + ")",
                        ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            case SET_TRADER -> {
                feedback(sp, "settrader expects an entity click, not a block. Click the trader entity.",
                        ChatFormatting.YELLOW);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.PASS;
    }

    // ---- Entity dispatch ----

    private static InteractionResult handleEntityClick(ServerPlayer sp, Entity entity, PendingRegistration reg) {
        if (reg.kind != PendingRegistration.Kind.SET_TRADER) return InteractionResult.PASS;
        if (reg.team == null) {
            feedback(sp, "Internal error: trader registration missing team", ChatFormatting.RED);
            PendingRegistrationStore.clear(sp.getUUID());
            return InteractionResult.SUCCESS;
        }
        BlockPosData pos = BlockPosData.of(sp.serverLevel(), entity.blockPosition());
        RegionsConfig.get().traders.put(
                reg.team.id(),
                new TraderRef(entity.getUUID(), pos));
        RegionsConfig.save();
        PendingRegistrationStore.clear(sp.getUUID());
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        feedback(sp, reg.team.displayName() + " trader registered: " + entityId + " at (" + pos + ")",
                ChatFormatting.GREEN);
        return InteractionResult.SUCCESS;
    }

    // ---- Helpers ----

    private static boolean alreadyRegisteredOre(BlockPosData pos) {
        for (OreDeposit d : RegionsConfig.get().oreDeposits) {
            if (d.pos().equals(pos)) return true;
        }
        return false;
    }

    private static boolean alreadyRegisteredBlock(BlockPosData pos) {
        for (BlockDeposit d : RegionsConfig.get().blockDeposits) {
            if (d.pos().equals(pos)) return true;
        }
        return false;
    }

    private static boolean alreadyRegisteredMob(BlockPosData pos) {
        for (MobDeposit d : RegionsConfig.get().mobDeposits) {
            if (d.pos().equals(pos)) return true;
        }
        return false;
    }

    private static String blockId(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.toString();
    }

    private static void feedback(ServerPlayer sp, String msg, ChatFormatting color) {
        sp.sendSystemMessage(Component.literal(msg).withStyle(color));
    }

    /** Helper for command code that needs to send via CommandSourceStack instead of player. */
    public static void cmdFeedback(CommandSourceStack src, String msg, ChatFormatting color) {
        src.sendSuccess(() -> Component.literal(msg).withStyle(color), false);
    }
}
