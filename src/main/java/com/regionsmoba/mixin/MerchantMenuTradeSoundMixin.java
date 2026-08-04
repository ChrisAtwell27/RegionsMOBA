package com.regionsmoba.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RegionsMerchant (the mod's fixed-offer Merchant for the four team traders,
 * see economy/RegionsMerchant.java) is not an Entity and always reports
 * isClientSide() == false. MerchantMenu.playTradeSound() hard-casts its
 * `trader` field to Entity whenever isClientSide() is false, so trading with
 * a mod trader throws ClassCastException the instant a player shift-clicks
 * the trade result — reached from quickMoveStack on the RESULT_SLOT branch,
 * on the main server thread, with nothing catching it. That takes the whole
 * server down.
 *
 * We cancel the sound rather than making RegionsMerchant.isClientSide()
 * return true: MerchantMenu.removed(Player) gates payment-slot cleanup on
 * that same flag, and flipping it would leak the emeralds sitting in the
 * payment slots when a player closes the screen.
 */
@Mixin(MerchantMenu.class)
public class MerchantMenuTradeSoundMixin {

    @Shadow
    @Final
    private Merchant trader;

    @Inject(method = "playTradeSound", at = @At("HEAD"), cancellable = true)
    private void regionsmoba$skipNonEntityTrader(CallbackInfo ci) {
        if (!(trader instanceof Entity)) {
            ci.cancel();
        }
    }
}
