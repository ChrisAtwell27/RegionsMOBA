package com.regionsmoba.classes.impl;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Nether Alchemist — Enhanced Brewing.
 *
 * Per docs/src-md/classes/nether-classes.md:
 *   Brew in a water-filled cauldron instead of a brewing stand. Throw in a
 *   "Dusted" potion (a tier II, glowstone- or redstone-augmented potion), then
 *   its base ingredient. Gunpowder optionally converts the result to a splash
 *   potion. Each brew consumes a water level, teammates cannot pick items back
 *   out (enemies can), and the Alchemist sneaks on the cauldron to retrieve
 *   what they threw in.
 *
 * The cauldron is discovered rather than registered: a tick scan looks for item
 * entities resting inside a water cauldron and absorbs the ones an Alchemist
 * threw. That avoids needing a "place your brewing cauldron" step the doc never
 * mentions — any water cauldron works, exactly as written.
 *
 * SIMPLIFICATION: "class-bound" is recorded as the brewer's name on the result
 * rather than enforced at drink time. Enforcing it needs a consume hook, which
 * is a separate surface from everything here; the marker at least makes an
 * illegally-traded potion visible.
 */
public final class AlchemistCauldron {

    public static final int SCAN_INTERVAL_TICKS = 20;
    /** How far from an Alchemist a thrown item can be and still count. */
    public static final double THROW_SCAN_RADIUS = 8.0;
    /** Enhanced potions gain a level over the dusted input. */
    public static final int ENHANCED_AMPLIFIER_BONUS = 1;

    private static final class Brew {
        final UUID owner;
        final List<ItemStack> contents = new ArrayList<>();

        Brew(UUID owner) {
            this.owner = owner;
        }
    }

    /** Pending brews keyed by cauldron position. */
    private static final Map<BlockPosData, Brew> BREWS = new HashMap<>();

    private AlchemistCauldron() {}

    /**
     * Absorbs Alchemist-thrown items that are sitting in water cauldrons, and
     * completes any brew whose ingredients are now satisfied.
     */
    public static void tick(MinecraftServer server, long globalTick) {
        if (server == null || globalTick % SCAN_INTERVAL_TICKS != 0) return;

        // Absorb newly thrown items. The scan is anchored on each Alchemist
        // rather than sweeping the world: you can only throw an item into a
        // cauldron you are standing next to, and there are at most a handful of
        // Alchemists in a match.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
            if (state == null || state.biomeClass != BiomeClass.NETHER_ALCHEMIST) continue;
            if (!(player.level() instanceof ServerLevel level)) continue;
            AABB nearby = player.getBoundingBox().inflate(THROW_SCAN_RADIUS);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, nearby, ItemEntity::isAlive)) {
                absorb(level, item);
            }
        }

        // Try to finish each brew.
        BREWS.entrySet().removeIf(entry -> {
            ServerLevel level = server.getLevel(entry.getKey().dimensionKey());
            if (level == null) return true;
            BlockPos pos = entry.getKey().toBlockPos();
            if (!level.hasChunkAt(pos)) return false;
            if (!isWaterCauldron(level.getBlockState(pos))) {
                dropContents(level, pos, entry.getValue());
                return true;
            }
            return tryComplete(server, level, pos, entry.getValue());
        });
    }

    private static void absorb(ServerLevel level, ItemEntity item) {
        UUID thrower = item.getOwner() == null ? null : item.getOwner().getUUID();
        if (thrower == null) return;
        MatchPlayerState state = TeamAssignments.get().state(thrower);
        if (state == null || state.biomeClass != BiomeClass.NETHER_ALCHEMIST) return;

        BlockPos pos = item.blockPosition();
        if (!isWaterCauldron(level.getBlockState(pos))) return;

        BlockPosData key = BlockPosData.of(level, pos);
        Brew brew = BREWS.computeIfAbsent(key, k -> new Brew(thrower));
        if (!brew.owner.equals(thrower)) return; // someone else's cauldron
        brew.contents.add(item.getItem().copy());
        item.discard();
    }

    /**
     * Completes a brew once the cauldron holds a dusted potion and at least one
     * other ingredient. Gunpowder turns the result into a splash potion.
     */
    private static boolean tryComplete(MinecraftServer server, ServerLevel level, BlockPos pos, Brew brew) {
        Optional<ItemStack> dusted = brew.contents.stream()
                .filter(AlchemistCauldron::isDustedPotion)
                .findFirst();
        if (dusted.isEmpty()) return false;

        boolean splash = brew.contents.stream().anyMatch(s -> s.is(Items.GUNPOWDER));
        boolean hasIngredient = brew.contents.stream()
                .anyMatch(s -> !isDustedPotion(s) && !s.is(Items.GUNPOWDER));
        if (!hasIngredient) return false;

        ItemStack result = enhance(dusted.get(), splash, server, brew.owner);
        Block.popResource(level, pos.above(), result);
        drainOneLevel(level, pos);

        ServerPlayer owner = server.getPlayerList().getPlayer(brew.owner);
        if (owner != null) {
            tell(owner, "Enhanced potion brewed" + (splash ? " (splash)" : "") + ".", ChatFormatting.LIGHT_PURPLE);
        }
        return true;
    }

    /** Builds the enhanced potion: every effect gains a level, name records the brewer. */
    private static ItemStack enhance(ItemStack dusted, boolean splash, MinecraftServer server, UUID owner) {
        PotionContents contents = dusted.get(DataComponents.POTION_CONTENTS);
        ItemStack result = new ItemStack(splash ? Items.SPLASH_POTION : Items.POTION);
        if (contents != null) {
            List<MobEffectInstance> boosted = new ArrayList<>();
            contents.getAllEffects().forEach(effect -> boosted.add(new MobEffectInstance(
                    effect.getEffect(),
                    effect.getDuration(),
                    effect.getAmplifier() + ENHANCED_AMPLIFIER_BONUS,
                    effect.isAmbient(),
                    effect.isVisible())));
            result.set(DataComponents.POTION_CONTENTS,
                    new PotionContents(contents.potion(), contents.customColor(), boosted));
        }
        ServerPlayer brewer = server.getPlayerList().getPlayer(owner);
        String brewerName = brewer == null ? "an Alchemist" : brewer.getGameProfile().getName();
        result.set(DataComponents.CUSTOM_NAME,
                Component.literal("Enhanced Potion (" + brewerName + ")").withStyle(ChatFormatting.LIGHT_PURPLE));
        return result;
    }

    /** Sneak on your own cauldron to pull the thrown items back out. */
    public static boolean tryRetrieve(ServerPlayer alchemist, BlockPos pos) {
        if (!alchemist.isShiftKeyDown()) return false;
        ServerLevel level = alchemist.serverLevel();
        BlockPosData key = BlockPosData.of(level, pos);
        Brew brew = BREWS.get(key);
        if (brew == null || !brew.owner.equals(alchemist.getUUID())) return false;

        for (ItemStack stack : brew.contents) {
            if (!alchemist.getInventory().add(stack)) alchemist.drop(stack, false);
        }
        BREWS.remove(key);
        tell(alchemist, "Retrieved your brewing ingredients.", ChatFormatting.GRAY);
        return true;
    }

    private static void dropContents(ServerLevel level, BlockPos pos, Brew brew) {
        for (ItemStack stack : brew.contents) {
            Block.popResource(level, pos, stack);
        }
    }

    /** A "Dusted" potion: tier II, i.e. glowstone- or redstone-augmented. */
    private static boolean isDustedPotion(ItemStack stack) {
        if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) return false;
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.getAmplifier() > 0 || effect.getDuration() > 3600) return true;
        }
        return false;
    }

    private static boolean isWaterCauldron(BlockState state) {
        return state.is(Blocks.WATER_CAULDRON);
    }

    /** Each brew costs one water level; an empty cauldron reverts to a plain one. */
    private static void drainOneLevel(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.WATER_CAULDRON)) return;
        int water = state.getValue(LayeredCauldronBlock.LEVEL);
        if (water <= 1) {
            level.setBlock(pos, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        } else {
            level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL, water - 1), Block.UPDATE_ALL);
        }
    }

    public static void clearAll() {
        BREWS.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
