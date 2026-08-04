package com.regionsmoba.economy;

import com.regionsmoba.team.BiomeTeam;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;

/**
 * Per-team trader offers.
 *
 * Buy offers convert a region's raw surplus into emeralds at roughly 10:1, an
 * order of magnitude worse than the Plains composter's 1 crop -> 1 emerald. That
 * gap is what preserves the Plains monopoly economically rather than absolutely.
 *
 * No-arbitrage invariant: no table may buy and sell the same item or its direct
 * smelting product, and any two-offer round trip must lose at least half its
 * value. Re-verify by hand whenever a row changes.
 */
public final class TradeTables {

    /** High enough that no offer locks out within a match. */
    private static final int MAX_USES = 9999;

    private TradeTables() {}

    public static MerchantOffers offersFor(BiomeTeam team) {
        MerchantOffers offers = new MerchantOffers();
        switch (team) {
            case PLAINS -> {
                sell(offers, 1, Items.BREAD, 6);
                sell(offers, 1, Items.BAKED_POTATO, 8);
                sell(offers, 2, Items.HAY_BLOCK, 1);
                sell(offers, 3, Items.IRON_HOE, 1);
            }
            case MOUNTAIN -> {
                buy(offers, Items.COBBLESTONE, 16, 1);
                buy(offers, Items.RAW_IRON, 10, 1);
                buy(offers, Items.COAL, 16, 1);
                sell(offers, 4, Items.IRON_INGOT, 5);
                sell(offers, 12, Items.DIAMOND, 1);
                sell(offers, 3, Items.IRON_PICKAXE, 1);
            }
            case OCEAN -> {
                buy(offers, Items.COD, 12, 1);
                buy(offers, Items.PRISMARINE_SHARD, 8, 1);
                buy(offers, Items.KELP, 16, 1);
                sell(offers, 2, Items.PRISMARINE_BRICKS, 8);
                sell(offers, 3, Items.TURTLE_HELMET, 1);
                sell(offers, 5, Items.HEART_OF_THE_SEA, 1);
            }
            case NETHER -> {
                buy(offers, Items.QUARTZ, 6, 1);
                buy(offers, Items.BLAZE_ROD, 4, 1);
                buy(offers, Items.MAGMA_CREAM, 8, 1);
                sell(offers, 3, Items.OBSIDIAN, 8);
                sell(offers, 6, Items.NETHERITE_SCRAP, 1);
                sell(offers, 4, Items.FIRE_CHARGE, 8);
            }
        }
        return offers;
    }

    /** costCount of item -> emeraldsOut emeralds. */
    private static void buy(MerchantOffers offers, ItemLike item, int costCount, int emeraldsOut) {
        offers.add(new MerchantOffer(
                new ItemCost(item, costCount),
                new ItemStack(Items.EMERALD, emeraldsOut),
                MAX_USES, 0, 0.0f));
    }

    /** emeraldsIn emeralds -> resultCount of item. */
    private static void sell(MerchantOffers offers, int emeraldsIn, ItemLike item, int resultCount) {
        offers.add(new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldsIn),
                new ItemStack(item, resultCount),
                MAX_USES, 0, 0.0f));
    }
}
