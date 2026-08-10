package com.regionsmoba.lifeline;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Set;

/**
 * Plains lifeline (economic).
 *
 * Per docs/src-md/teams/plains.md and docs/src-md/gameplay/economy-and-trade.md:
 *   "The plains composter is the only source of emeralds. Right-click the
 *    composter with a crop to convert the crop into emeralds."
 *   "No other team can generate emeralds."
 *
 * Implementation: intercept right-click on the registered composter; if the
 * player is on Plains and holding a crop in main hand, consume one crop and
 * grant one emerald. Other teams' clicks are blocked entirely (with feedback)
 * so vanilla composting can't run on the registered block.
 */
public final class ComposterLifeline {

    /**
     * What counts as a "crop". Per the docs the term is broad; we accept the
     * standard farmable produce. Map authors can extend this if they add custom
     * crops.
     */
    private static final Set<Item> CROPS = Set.of(
            Items.WHEAT,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT,
            Items.MELON_SLICE,
            Items.PUMPKIN,
            Items.SWEET_BERRIES);

    private ComposterLifeline() {}

    public static void register() {
        UseBlockCallback.EVENT.register(ComposterLifeline::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!MatchManager.get().isActive()) return InteractionResult.PASS;
        BlockPosData composter = RegionsConfig.get().composter;
        if (composter == null) return InteractionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        if (pos.getX() != composter.x() || pos.getY() != composter.y() || pos.getZ() != composter.z()) {
            return InteractionResult.PASS;
        }
        if (!world.dimension().location().toString().equals(composter.dimensionOrDefault())) {
            return InteractionResult.PASS;
        }

        MatchPlayerState state = TeamAssignments.get().state(sp.getUUID());
        if (state == null || state.team != BiomeTeam.PLAINS) {
            // Block any non-Plains interaction with the registered composter.
            tell(sp, "Only Plains can use the composter.", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }

        ItemStack held = sp.getMainHandItem();

        if (held.is(Items.EMERALD)) {
            int want = sp.isShiftKeyDown() ? 1 : held.getCount();
            int quota = PlainsQuota.currentQuota();
            if (quota <= 0) {
                tell(sp, "No quota is active right now.", ChatFormatting.GRAY);
                return InteractionResult.SUCCESS;
            }
            int taken = PlainsQuota.pay(want);
            if (taken == 0) {
                tell(sp, "Quota already met — " + PlainsQuota.paid() + " / " + quota + ".",
                        ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            held.shrink(taken);
            tell(sp, "Paid " + taken + " emeralds — " + PlainsQuota.paid() + " / " + quota + ".",
                    ChatFormatting.GREEN);
            return InteractionResult.SUCCESS;
        }

        if (held.isEmpty() || !CROPS.contains(held.getItem())) {
            tell(sp, "Hold a crop (wheat, carrot, potato, beetroot, melon slice, pumpkin, sweet berries) to convert.",
                    ChatFormatting.GRAY);
            return InteractionResult.SUCCESS;
        }
        held.shrink(1);
        ItemStack emerald = new ItemStack(Items.EMERALD);
        if (!sp.getInventory().add(emerald)) {
            sp.drop(emerald, false);
        }
        tell(sp, "+1 Emerald", ChatFormatting.GREEN);
        return InteractionResult.SUCCESS;
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
