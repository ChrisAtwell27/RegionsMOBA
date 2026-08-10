package com.regionsmoba.protection;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.MobDeposit;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Enforces block-break and block-place protection for registered zones.
 *
 * Per docs/src-md/reference/protected-areas.md the protection scope is:
 *   - Implicit: lobby, biome bounds, the three lifeline blocks, traders, every
 *     deposit. (Lifelines are handled by their own classes; deposits are an
 *     EXCEPTION inside protected areas.)
 *   - Explicit: named protected areas via /regions addprotectedarea.
 *
 * Plus the trial chamber (docs/src-md/gameplay/trial-chamber.md): "No blocks can
 * be broken inside the chamber bounds" — stricter than a protected area, deposits
 * do NOT function inside the chamber.
 *
 * Build-mode bypass (per protected-areas.md): operators with build-mode on
 * skip all checks for themselves only.
 *
 * Place-protection: blocks the placement target — i.e. the block position the
 * player is trying to put a block at — not the targeted face.
 */
public final class BlockProtection {

    private BlockProtection() {}

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!MatchManager.get().isActive()) return true;
            if (!(player instanceof ServerPlayer sp)) return true;
            if (BuildMode.isEnabled(sp.getUUID())) return true;
            String dim = world.dimension().location().toString();

            // Trial chamber: blanket block-break ban, NO deposit exception.
            if (insideChamber(pos, dim)) {
                deny(sp, "Block-mining is disabled inside the trial chamber.");
                return false;
            }

            boolean inProtected = inLobby(pos, dim) || inAnyProtectedArea(pos, dim);
            if (!inProtected) return true; // free terrain — allow

            // Inside a protected area — deposits are an explicit exception.
            BlockPosData posData = BlockPosData.of((net.minecraft.server.level.ServerLevel) world, pos);
            if (isAnyDeposit(posData)) return true;

            deny(sp, "Block-mining is disabled in this protected area.");
            return false;
        });

        UseBlockCallback.EVENT.register(BlockProtection::onUseBlock);
    }

    /**
     * Combined place-protection AND block-entity-interaction protection. We let
     * sign joins / composter conversion / etc. through by checking whether the
     * targeted block is one of the explicit exceptions before denying.
     */
    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!MatchManager.get().isActive()) return InteractionResult.PASS;
        if (BuildMode.isEnabled(sp.getUUID())) return InteractionResult.PASS;

        ItemStack held = sp.getMainHandItem();
        BlockPos clicked = hit.getBlockPos();
        String dim = world.dimension().location().toString();

        // Block-entity interaction protection: if the clicked block has a BE and
        // sits inside a protected area, deny — chest/hopper/dispenser/etc. can't
        // be opened. The exceptions are the registered lifeline blocks (handled
        // by their own classes which fire after this returns PASS).
        if (insideChamber(clicked, dim) || inLobby(clicked, dim) || inAnyProtectedArea(clicked, dim)) {
            BlockEntity be = world.getBlockEntity(clicked);
            if (be != null && !isLifelineException(clicked, dim)) {
                deny(sp, "Block-entity interaction is disabled here.");
                return InteractionResult.FAIL;
            }
        }

        // Place-protection — only relevant if the player is holding a block item.
        if (!(held.getItem() instanceof BlockItem)) return InteractionResult.PASS;

        // Where would the block actually go? It's the position adjacent to the hit
        // face — replaceability is not perfectly modelled here, but is good enough
        // to deny the common case of "build wall in protected area".
        Direction face = hit.getDirection();
        BlockPos target = clicked.relative(face);

        if (insideChamber(target, dim) || inLobby(target, dim) || inAnyProtectedArea(target, dim)) {
            deny(sp, "Block-placement is disabled here.");
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /**
     * Returns true if the clicked position is one of the registered lifeline blocks
     * (Conduit / Furnace / Composter) — these are left to their own handlers
     * (ConduitLifeline, vanilla furnace fuel-feed, ComposterLifeline).
     */
    private static boolean isLifelineException(BlockPos pos, String dim) {
        BlockPosData furnace = RegionsConfig.get().furnace;
        if (furnace != null && samePos(pos, dim, furnace)) return true;
        BlockPosData composter = RegionsConfig.get().composter;
        if (composter != null && samePos(pos, dim, composter)) return true;
        BlockPosData conduit = RegionsConfig.get().conduit;
        if (conduit != null && samePos(pos, dim, conduit)) return true;
        return false;
    }

    private static boolean samePos(BlockPos a, String dim, BlockPosData b) {
        return a.getX() == b.x() && a.getY() == b.y() && a.getZ() == b.z()
                && dim.equals(b.dimensionOrDefault());
    }

    // ---- Region predicates ----

    private static boolean inLobby(BlockPos pos, String dim) {
        Area lobby = RegionsConfig.get().lobby;
        return lobby != null && lobby.isComplete()
                && lobby.dimension().equals(dim)
                && lobby.contains(pos);
    }

    private static boolean inAnyProtectedArea(BlockPos pos, String dim) {
        for (Area area : RegionsConfig.get().protectedAreas.values()) {
            if (area == null || !area.isComplete()) continue;
            if (!area.dimension().equals(dim)) continue;
            if (area.contains(pos)) return true;
        }
        return false;
    }

    private static boolean insideChamber(BlockPos pos, String dim) {
        Area chamber = RegionsConfig.get().chamberBounds;
        return chamber != null && chamber.isComplete()
                && chamber.dimension().equals(dim)
                && chamber.contains(pos);
    }

    // ---- Deposit predicates ----

    public static boolean isAnyDeposit(BlockPosData pos) {
        for (OreDeposit d : RegionsConfig.get().oreDeposits) if (d.pos().equals(pos)) return true;
        for (BlockDeposit d : RegionsConfig.get().blockDeposits) if (d.pos().equals(pos)) return true;
        for (MobDeposit d : RegionsConfig.get().mobDeposits) if (d.pos().equals(pos)) return true;
        return false;
    }

    public static OreDeposit oreDepositAt(BlockPosData pos) {
        for (OreDeposit d : RegionsConfig.get().oreDeposits) if (d.pos().equals(pos)) return d;
        return null;
    }

    public static BlockDeposit blockDepositAt(BlockPosData pos) {
        for (BlockDeposit d : RegionsConfig.get().blockDeposits) if (d.pos().equals(pos)) return d;
        return null;
    }

    private static void deny(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
    }
}
