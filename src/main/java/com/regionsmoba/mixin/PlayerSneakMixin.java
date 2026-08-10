package com.regionsmoba.mixin;

import com.regionsmoba.classes.AbilityHooks;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Surfaces the client's sneak key-press to the server.
 *
 * The Mountain Acrobat's double jump needs a deliberate mid-air input, and on
 * 1.21.1 the jump key is not one: ServerboundPlayerInputPacket is only sent
 * while the player is riding a vehicle, so a jump press on foot never reaches
 * the server at all. The movement packet is no help either — it only carries the
 * resulting position, by which point a jump has been indistinguishable from a
 * fall for several ticks.
 *
 * Sneak is the one mid-air key vanilla does report every time, via this command
 * packet, so the Acrobat's trigger is a mid-air sneak double-tap instead
 * (see {@link com.regionsmoba.classes.impl.AcrobatAbility}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerSneakMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerCommand", at = @At("TAIL"))
    private void regionsmoba$onSneak(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY) return;
        AbilityHooks.onSneakPress(player);
    }
}
