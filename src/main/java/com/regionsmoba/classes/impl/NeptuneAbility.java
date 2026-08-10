package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ocean Neptune — Tidebringer modes, the Ground Freeze toggle, and the aquatic
 * passive.
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Ground Freeze — right-click the ice block to toggle the freeze on and off.
 *   Tidebringer — right-click to toggle the trident between two modes.
 *     Riptide — launches the player while in water or rain. 20-second cooldown.
 *     Curse of the Sea — thrown at an enemy; on hit inflicts drowning damage
 *       over 8 seconds, ignored if the target is already underwater. Returns on
 *       hit or miss. 20-second cooldown. PVP-gated: an out-of-combat hit plays
 *       the return animation but deals no damage over time.
 *   Aquatic passive — cannot take drowning damage in water.
 *
 * DEVIATION: the mode toggle is bound to SNEAK + right-click, not a plain
 * right-click. A plain right-click on a trident is how vanilla throws it, and
 * claiming that click would leave the trident unthrowable — which would break
 * both modes rather than switch between them.
 *
 * Each mode swaps the trident's enchantment so vanilla does the heavy lifting:
 * Riptide III in Riptide mode, Loyalty III in Curse mode (that is what makes it
 * "return on hit or miss"). The 20-second cooldown is enforced on Curse of the
 * Sea's damage-over-time application; Riptide leans on vanilla's own throw
 * cooldown rather than a second timer on top.
 */
public final class NeptuneAbility {

    public enum Mode { RIPTIDE, CURSE }

    public static final int CURSE_DURATION_TICKS = 8 * 20;
    public static final int CURSE_COOLDOWN_SECONDS = 20;
    public static final float CURSE_DAMAGE_PER_SECOND = 1.0f;
    public static final int ENCHANT_LEVEL = 3;

    private static final String CURSE_COOLDOWN_ID = "neptune:curse";

    private static final Map<UUID, Mode> MODES = new HashMap<>();
    /** Neptunes with Ground Freeze switched on. */
    private static final Set<UUID> FREEZE_ON = new HashSet<>();
    /** Victim → server tick at which their drowning curse ends. */
    private static final Map<UUID, Long> CURSED = new HashMap<>();

    private NeptuneAbility() {}

    public static Mode mode(UUID player) {
        return MODES.getOrDefault(player, Mode.RIPTIDE);
    }

    /** Read by {@link NeptuneGroundFreeze} — the freeze starts switched off. */
    public static boolean isFreezeEnabled(UUID player) {
        return FREEZE_ON.contains(player);
    }

    public static boolean tryRightClick(ServerPlayer neptune, ItemStack stack) {
        if (ItemTags.hasName(stack, ClassKits.Names.GROUND_FREEZE)) {
            return toggleFreeze(neptune);
        }
        if (!ItemTags.hasName(stack, ClassKits.Names.TIDEBRINGER)) return false;
        // Only sneak-clicks toggle; a plain click falls through to the throw.
        if (!neptune.isShiftKeyDown()) return false;
        return toggleTridentMode(neptune, stack);
    }

    private static boolean toggleFreeze(ServerPlayer neptune) {
        boolean on = !FREEZE_ON.contains(neptune.getUUID());
        if (on) {
            FREEZE_ON.add(neptune.getUUID());
        } else {
            FREEZE_ON.remove(neptune.getUUID());
        }
        tell(neptune, "Ground Freeze " + (on ? "on" : "off") + ".",
                on ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        return true;
    }

    private static boolean toggleTridentMode(ServerPlayer neptune, ItemStack trident) {
        Mode next = mode(neptune.getUUID()) == Mode.RIPTIDE ? Mode.CURSE : Mode.RIPTIDE;
        MODES.put(neptune.getUUID(), next);
        applyModeEnchantment(neptune, trident, next);
        tell(neptune, "Tidebringer: " + (next == Mode.RIPTIDE ? "Riptide" : "Curse of the Sea"),
                ChatFormatting.AQUA);
        return true;
    }

    /**
     * Swaps the trident's enchantment to match the mode. Riptide and Loyalty are
     * mutually exclusive in vanilla, so the stack's enchantments are rebuilt
     * from scratch rather than merged.
     */
    private static void applyModeEnchantment(ServerPlayer neptune, ItemStack trident, Mode mode) {
        ResourceKey<Enchantment> key = mode == Mode.RIPTIDE ? Enchantments.RIPTIDE : Enchantments.LOYALTY;
        HolderGetter<Enchantment> lookup = neptune.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        trident.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        trident.enchant(lookup.getOrThrow(key), ENCHANT_LEVEL);
    }

    /**
     * Called from AFTER_DAMAGE when a thrown Tidebringer lands on a player.
     * Starts the drowning damage-over-time unless the target is already under
     * water, per the doc.
     */
    public static void onTridentHit(ServerPlayer neptune, Player victim) {
        if (mode(neptune.getUUID()) != Mode.CURSE) return;
        if (!PvpManager.isPvpAllowedFor(neptune, victim)) return;
        if (victim.isUnderWater()) return;
        if (!Cooldowns.get().ready(neptune, CURSE_COOLDOWN_ID)) return;

        MinecraftServer server = neptune.level().getServer();
        long now = server != null ? server.getTickCount() : 0L;
        CURSED.put(victim.getUUID(), now + CURSE_DURATION_TICKS);
        Cooldowns.get().set(neptune, CURSE_COOLDOWN_ID, CURSE_COOLDOWN_SECONDS);
        tell(neptune, "Curse of the Sea — " + victim.getGameProfile().getName() + " is drowning.",
                ChatFormatting.DARK_AQUA);
    }

    /** Ticks the drowning curse once a second. */
    public static void tick(MinecraftServer server, long globalTick) {
        if (CURSED.isEmpty() || server == null) return;
        if (globalTick % 20 != 0) return;

        CURSED.entrySet().removeIf(entry -> {
            ServerPlayer victim = server.getPlayerList().getPlayer(entry.getKey());
            if (victim == null || victim.isRemoved()) return true;
            if (globalTick >= entry.getValue()) return true;
            if (!(victim.level() instanceof ServerLevel level)) return true;
            victim.hurt(level.damageSources().drown(), CURSE_DAMAGE_PER_SECOND);
            return false;
        });
    }

    public static void clearForPlayer(UUID player) {
        MODES.remove(player);
        FREEZE_ON.remove(player);
        CURSED.remove(player);
    }

    public static void clearAll() {
        MODES.clear();
        FREEZE_ON.clear();
        CURSED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
