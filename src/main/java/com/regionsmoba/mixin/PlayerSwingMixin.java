package com.regionsmoba.mixin;

import com.regionsmoba.classes.AbilityHooks;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes every main-hand swing to {@link AbilityHooks#onSwing}.
 *
 * Fabric's AttackEntityCallback and AttackBlockCallback only fire when the swing
 * actually lands on something. Left-click abilities that are cast at nothing —
 * the Immobilizer's AoE slow, the Archer's bow-special cycle — need the raw
 * swing, and this packet handler is the only place it surfaces server-side.
 *
 * handleAnimate runs {@code PacketUtils.ensureRunningOnSameThread} before its
 * body, so by TAIL we are on the server thread and can touch world state.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerSwingMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void regionsmoba$onSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (packet.getHand() != InteractionHand.MAIN_HAND) return;
        AbilityHooks.onSwing(player);
    }
}
