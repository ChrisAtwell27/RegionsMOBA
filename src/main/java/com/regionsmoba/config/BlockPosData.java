package com.regionsmoba.config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Position with its dimension, so cross-dimension setups (overworld + nether biome,
 * etc.) work end-to-end. Dimension is stored as the registry id string (e.g.
 * "minecraft:overworld") to keep JSON portable.
 */
public record BlockPosData(String dimension, int x, int y, int z) {

    public static final String DEFAULT_DIMENSION = "minecraft:overworld";

    public static BlockPosData of(ServerLevel level, BlockPos pos) {
        return new BlockPosData(dimensionId(level), pos.getX(), pos.getY(), pos.getZ());
    }

    public static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    /** Treats a null dimension as overworld — defensive against hand-edited JSON. */
    public String dimensionOrDefault() {
        return dimension != null ? dimension : DEFAULT_DIMENSION;
    }

    public ResourceKey<Level> dimensionKey() {
        ResourceLocation id = ResourceLocation.parse(dimensionOrDefault());
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    @Override
    public String toString() {
        return dimensionOrDefault() + " " + x + ", " + y + ", " + z;
    }
}
