package com.regionsmoba.economy;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * A fixed-offer Merchant owned by the mod rather than by an entity.
 *
 * Piglin does not implement Merchant and there is no SimpleMerchant in 1.21.11,
 * so trades cannot be attached to the Nether trader as villager offers. Supplying
 * our own Merchant and opening a MerchantMenu against it works for any entity
 * type and gives all four traders identical behavior.
 */
public final class RegionsMerchant implements Merchant {

    private final MerchantOffers offers;
    private Player tradingPlayer;

    public RegionsMerchant(MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public void setTradingPlayer(Player player) {
        this.tradingPlayer = player;
    }

    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers newOffers) {
        // Fixed tables; nothing may override them at runtime.
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
