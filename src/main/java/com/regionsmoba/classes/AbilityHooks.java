package com.regionsmoba.classes;

import net.minecraft.server.MinecraftServer;

/**
 * Central wiring point for class-ability event listeners and per-tick work.
 *
 * Ability implementations live in {@link com.regionsmoba.classes.impl} and are
 * registered/ticked through here so {@link com.regionsmoba.RegionsMOBA} only has
 * to call {@code AbilityHooks.register()} once during mod init and
 * {@code AbilityHooks.tick(server)} from the END_SERVER_TICK loop.
 *
 * Currently a no-op surface — concrete ability behavior is layered in per class.
 */
public final class AbilityHooks {

    private AbilityHooks() {}

    public static void register() {
        // Ability classes register their own Fabric event listeners here as they
        // are implemented. Order is not significant.
    }

    public static void tick(MinecraftServer server) {
        Cooldowns.get().tick(server.getTickCount());
    }
}
