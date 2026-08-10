package com.regionsmoba.mixin;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.lobby.NationsSignHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Edit-time validation for [Nations] signs.
 *
 * Per docs/src-md/reference/signs.md: row 1 must be "[Nations]" (case-insensitive)
 * and row 2 must be a positive integer multiple of 4. The sign-click handler
 * (NationsSignHandler) already validates at click time; this mixin gives the
 * placer immediate feedback at edit time so they don't ship a broken sign.
 *
 * We don't reject the edit (that would need cancelling the packet); we just
 * message the player with a clear status — accepted or rejected reason.
 *
 * This is also where accepted boards get recorded in the config, and where a board
 * that loses its header gets dropped, so {@code /nations join} can find lobbies
 * that nobody has clicked yet.
 */
@Mixin(SignBlockEntity.class)
public class SignEditValidationMixin {

    @Inject(method = "updateSignText", at = @At("RETURN"))
    private void regionsmoba$validate(Player player, boolean front, List<FilteredText> messages, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) return;
        SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) return;

        BlockPosData pos = BlockPosData.of(level, self.getBlockPos());
        NationsSignHandler.SignCheck check = NationsSignHandler.check(self);

        if (!check.tagged()) {
            // Header removed or overwritten — stop offering it to /nations join.
            RegionsConfig.forgetNationsSign(pos);
            return;
        }
        if (!check.usable()) {
            sp.sendSystemMessage(Component.literal(
                            "[Nations] sign rejected: row 2 must be a positive integer and a multiple of 4 (got '"
                                    + check.row2() + "')")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // Registering here — not just on click — is what lets /nations join find a
        // freshly placed board before anybody has touched it.
        RegionsConfig.rememberNationsSign(pos);
        sp.sendSystemMessage(Component.literal(
                        "[Nations] sign accepted — players will join here (min " + check.minPlayers() + ")")
                .withStyle(ChatFormatting.GREEN));
    }
}
