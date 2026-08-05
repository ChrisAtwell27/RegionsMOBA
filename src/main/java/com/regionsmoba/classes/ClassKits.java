package com.regionsmoba.classes;

import com.regionsmoba.team.BiomeClass;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.regionsmoba.classes.ItemTags.named;
import static com.regionsmoba.classes.ItemTags.stack;
import static com.regionsmoba.classes.ItemTags.tool;

/**
 * Per-class spawn kit. Items match docs/src-md/classes/{team}-classes.md.
 *
 * Kit items are produced fresh each time {@link KitGrant#grant} runs — we always
 * call .copy() before adding to the player inventory.
 *
 * Ability-item display names — referenced as {@code Names.*} — are the source of
 * truth for matching at click time, so the per-class ability classes pull names
 * from the same constants.
 */
public final class ClassKits {

    public static final class Names {
        public static final String GUARDIANS_WARP = "Guardian's Warp";
        public static final String ALERT_ITEM = "Alert Item";
        public static final String GROUND_FREEZE = "Ground Freeze";
        public static final String TIDEBRINGER = "Tidebringer";
        public static final String SIREN_COOLDOWN = "Drain";
        public static final String HEALER_BLOOD_BAG = "Blood Bag";
        public static final String TRANSPORTER_QUARTZ = "Nether Quartz Portal";

        public static final String ALCHEMIST_BREWING_STAND = "Alchemist's Brewing Stand";
        public static final String ALCHEMIST_TOME = "Alchemist's Tome";
        public static final String INTENSIFIER = "Intensifier";
        public static final String CORRUPT = "Corrupt";
        public static final String BLOODCURSED_TERRAFORM = "Bloodcursed Terraform";
        public static final String WIZARD_WAND = "Wand";
        public static final String WIZARD_SPELLBOOK = "Spellbook";
        public static final String BLOOD_SENSE = "Blood Sense";
        public static final String INSIDIOUS_DISPATCH = "Insidious Dispatch";
        public static final String RIFT_BLAZE_ROD = "Rift Rod";

        public static final String ARROW_OF_INFINITY = "Arrow of Infinity";
        public static final String FEAST = "Feast";
        public static final String FAMINE = "Famine";
        public static final String BUFFBOX = "Buffbox";
        public static final String BRUTE_FORCE = "Brute Force";
        public static final String SCOUT_GRAPPLE = "Grapple";

        public static final String RESOURCE_DROP = "Resource Drop";
        public static final String REPLICATION_CACHE = "Replication Cache";
        public static final String FRENZY = "Frenzy";
        public static final String UNBREAKABLE_WILL = "Unbreakable Will";
        public static final String MINER_PASSION = "Miner's Passion";
        public static final String GOLD_RUSH = "Gold Rush";
        public static final String POWERPAD_SPEED1 = "Speed I PowerPad";
        public static final String POWERPAD_HASTE1 = "Haste I PowerPad";
        public static final String DISENCHANT_BOOK = "Disenchant Book";

        private Names() {}
    }

    private static final Map<BiomeClass, List<ItemStack>> KITS = new EnumMap<>(BiomeClass.class);

    static {
        // ---- Ocean ----
        KITS.put(BiomeClass.OCEAN_DEFENDER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                tool(Items.CHAINMAIL_CHESTPLATE),
                named(Items.LIME_DYE, Names.GUARDIANS_WARP),
                named(Items.PRISMARINE_SHARD, Names.ALERT_ITEM)));

        KITS.put(BiomeClass.OCEAN_IMMOBILIZER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL)));

        KITS.put(BiomeClass.OCEAN_NEPTUNE, List.of(
                tool(Items.STONE_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.ICE, Names.GROUND_FREEZE),
                named(Items.TRIDENT, Names.TIDEBRINGER),
                stack(Items.LILY_PAD, 10)));

        KITS.put(BiomeClass.OCEAN_SIREN, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.RED_DYE, Names.SIREN_COOLDOWN)));

        KITS.put(BiomeClass.OCEAN_HEALER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.HONEY_BOTTLE, Names.HEALER_BLOOD_BAG)));

        KITS.put(BiomeClass.OCEAN_TRANSPORTER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.QUARTZ, Names.TRANSPORTER_QUARTZ)));

        // ---- Nether ----
        KITS.put(BiomeClass.NETHER_ALCHEMIST, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                tool(Items.LEATHER_HELMET),
                tool(Items.LEATHER_CHESTPLATE),
                tool(Items.LEATHER_LEGGINGS),
                tool(Items.LEATHER_BOOTS),
                named(Items.BREWING_STAND, Names.ALCHEMIST_BREWING_STAND),
                named(Items.BOOK, Names.ALCHEMIST_TOME)));

        KITS.put(BiomeClass.NETHER_ENCHANTER, List.of(
                tool(Items.GOLDEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.LAPIS_LAZULI, Names.INTENSIFIER)));

        KITS.put(BiomeClass.NETHER_BLOODMAGE, List.of(
                tool(Items.STONE_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.FERMENTED_SPIDER_EYE, Names.CORRUPT),
                named(Items.CRIMSON_HYPHAE, Names.BLOODCURSED_TERRAFORM)));

        KITS.put(BiomeClass.NETHER_WIZARD, List.of(
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.STICK, Names.WIZARD_WAND),
                named(Items.WRITTEN_BOOK, Names.WIZARD_SPELLBOOK)));

        KITS.put(BiomeClass.NETHER_VAMPIRE, List.of(
                tool(Items.STONE_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.MUSIC_DISC_13, Names.BLOOD_SENSE),
                named(Items.BLACK_DYE, Names.INSIDIOUS_DISPATCH)));

        KITS.put(BiomeClass.NETHER_RIFT_WALKER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.BLAZE_ROD, Names.RIFT_BLAZE_ROD)));

        // ---- Plains ----
        KITS.put(BiomeClass.PLAINS_ARCHER, List.of(
                tool(Items.BOW),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.ARROW, Names.ARROW_OF_INFINITY),
                tool(Items.POTION)));

        KITS.put(BiomeClass.PLAINS_SPY, List.of(
                tool(Items.GOLDEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL)));

        KITS.put(BiomeClass.PLAINS_FARMER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                tool(Items.STONE_HOE),
                stack(Items.BONE_MEAL, 15),
                named(Items.GOLDEN_CARROT, Names.FEAST),
                named(Items.DEAD_BUSH, Names.FAMINE)));

        KITS.put(BiomeClass.PLAINS_BARD, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.JUKEBOX, Names.BUFFBOX)));

        KITS.put(BiomeClass.PLAINS_LUMBERJACK, List.of(
                tool(Items.STONE_AXE),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.BRICKS, Names.BRUTE_FORCE)));

        KITS.put(BiomeClass.PLAINS_SCOUT, List.of(
                tool(Items.GOLDEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.FISHING_ROD, Names.SCOUT_GRAPPLE)));

        // ---- Mountain ----
        KITS.put(BiomeClass.MOUNTAIN_BUILDER, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.BOOK, Names.RESOURCE_DROP),
                named(Items.COMPOSTER, Names.REPLICATION_CACHE)));

        KITS.put(BiomeClass.MOUNTAIN_WARRIOR, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                tool(Items.POTION),
                named(Items.BLAZE_POWDER, Names.FRENZY)));

        KITS.put(BiomeClass.MOUNTAIN_BERSERKER, List.of(
                tool(Items.STONE_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.NETHERITE_INGOT, Names.UNBREAKABLE_WILL)));

        KITS.put(BiomeClass.MOUNTAIN_MINER, List.of(
                named(Items.STONE_PICKAXE, Names.MINER_PASSION),
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.GOLD_NUGGET, Names.GOLD_RUSH)));

        KITS.put(BiomeClass.MOUNTAIN_TINKERER, List.of(
                tool(Items.STONE_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                named(Items.REDSTONE_BLOCK, Names.POWERPAD_SPEED1),
                named(Items.COAL_BLOCK, Names.POWERPAD_HASTE1),
                named(Items.BOOK, 10, Names.DISENCHANT_BOOK)));

        KITS.put(BiomeClass.MOUNTAIN_ACROBAT, List.of(
                tool(Items.WOODEN_SWORD),
                tool(Items.WOODEN_PICKAXE),
                tool(Items.WOODEN_AXE),
                tool(Items.WOODEN_SHOVEL),
                tool(Items.BOW),
                stack(Items.ARROW, 6)));
    }

    private ClassKits() {}

    public static List<ItemStack> kit(BiomeClass biomeClass) {
        return KITS.getOrDefault(biomeClass, List.of());
    }
}
