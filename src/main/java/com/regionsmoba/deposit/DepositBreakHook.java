package com.regionsmoba.deposit;

import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.protection.BlockProtection;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Post-break hook that runs the deposit aftermath:
 *   - Ore deposit was mined → setBlock cobblestone + start cooldown.
 *   - Block deposit was mined → start cooldown (block is already air).
 *
 * The break is allowed by BlockProtection's BEFORE check (deposits are an
 * exception); this AFTER hook handles the substitution.
 */
public final class DepositBreakHook {

    private DepositBreakHook() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!MatchManager.get().isActive()) return;
            if (!(world instanceof ServerLevel level)) return;
            BlockPosData posData = BlockPosData.of(level, pos);
            long tick = level.getServer().getTickCount();

            OreDeposit ore = BlockProtection.oreDepositAt(posData);
            if (ore != null) {
                // The breaking player is passed through so a Miner's Gold Rush
                // can waive the Cold Season regen penalty.
                ServerPlayer miner = player instanceof ServerPlayer sp ? sp : null;
                DepositTracker.get().onOreMined(level, pos, ore, tick, miner);
                return;
            }
            BlockDeposit block = BlockProtection.blockDepositAt(posData);
            if (block != null) {
                DepositTracker.get().onBlockMined(block, tick);
            }
        });
    }
}
