package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Random;

/**
 * Nether Alchemist — Tome (random ingredient).
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Right-click the Tome → receive a random potion ingredient by tier.
 *   90s cd. Gunpowder only available after PVP has gone permanent.
 *
 * The Brewing Stand item placement uses the vanilla brewing stand block;
 * "private to Alchemist" enforcement and 2x speed are deferred to a polish pass
 * (would need block-entity owner tracking + brew-tick mixin).
 *
 * Enhanced cauldron brewing recipes are deferred — they need a custom item-drop
 * detection inside the cauldron, recipe matching, and a class-bound potion tag.
 */
public final class AlchemistAbility {

    public static final int COOLDOWN_SECONDS = 90;

    private record Roll(Item item, int weight) {}

    // Doc tier table → flat weighted list. Gunpowder gated separately.
    private static final List<Roll> TIER_TABLE = List.of(
            // Very common (32% / 2 entries → 16% each)
            new Roll(Items.NETHER_WART, 16),
            new Roll(Items.FERMENTED_SPIDER_EYE, 16),
            // Common (28% / 5 entries → 5.6% each)
            new Roll(Items.GLISTERING_MELON_SLICE, 6),
            new Roll(Items.GOLDEN_CARROT, 6),
            new Roll(Items.SUGAR, 6),
            new Roll(Items.SPIDER_EYE, 5),
            new Roll(Items.MAGMA_CREAM, 5),
            // Uncommon (15%)
            new Roll(Items.GLOWSTONE_DUST, 15),
            // Rare (7%)
            new Roll(Items.GHAST_TEAR, 7),
            // Extremely rare (3% — gunpowder PVP-permanent gated separately)
            new Roll(Items.BLAZE_POWDER, 3),
            // Junk (15% / 4 entries)
            new Roll(Items.ROTTEN_FLESH, 4),
            new Roll(Items.POISONOUS_POTATO, 4),
            new Roll(Items.SNOWBALL, 4),
            new Roll(Items.STRING, 3));

    private static final Roll GUNPOWDER = new Roll(Items.GUNPOWDER, 3);

    private static final Random RNG = new Random();

    private AlchemistAbility() {}

    public static boolean tryRightClick(ServerPlayer player, ItemStack stack) {
        if (!ItemTags.hasName(stack, ClassKits.Names.ALCHEMIST_TOME)) return false;
        if (!Cooldowns.get().ready(player, "alchemist:tome")) {
            tell(player, "Tome on cooldown ("
                    + Cooldowns.get().remainingSeconds(player, "alchemist:tome") + "s)", ChatFormatting.GRAY);
            return true;
        }
        Item rolled = roll();
        Inventory inv = player.getInventory();
        ItemStack drop = new ItemStack(rolled, 1);
        if (!inv.add(drop)) {
            player.drop(drop, false);
        }
        Cooldowns.get().set(player, "alchemist:tome", COOLDOWN_SECONDS);
        tell(player, "Tome → " + rolled.getName().getString(), ChatFormatting.LIGHT_PURPLE);
        return true;
    }

    private static Item roll() {
        boolean gunpowderUnlocked = com.regionsmoba.timeline.Timeline.get().isPvpPermanent();
        int totalWeight = TIER_TABLE.stream().mapToInt(Roll::weight).sum();
        if (gunpowderUnlocked) totalWeight += GUNPOWDER.weight;
        int pick = RNG.nextInt(totalWeight);
        for (Roll r : TIER_TABLE) {
            if (pick < r.weight) return r.item;
            pick -= r.weight;
        }
        return GUNPOWDER.item;
    }

    @SuppressWarnings("unused")
    private static final Object KEEP_PVP = PvpManager.class;

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
