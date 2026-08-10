package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.pvp.PvpManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plains Archer — Arrow of Infinity.
 *
 * Per docs/src-md/classes/plains-classes.md: "Never runs out of arrows while the
 * Arrow of Infinity is in inventory, regardless of which bow is held."
 *
 * Implemented as a per-tick top-up rather than the Infinity enchantment, because
 * Infinity only applies to the bow carrying it and the doc explicitly covers any
 * bow. Vanilla's ammo search picks the first matching arrow stack, and the named
 * Arrow of Infinity is a plain {@code Items.ARROW} underneath, so it is that
 * stack that gets consumed — refilling it each tick makes the quiver bottomless.
 *
 * Dropping the Arrow of Infinity stops the refill, which is exactly the doc's
 * "while ... in inventory" condition.
 *
 * The +1 bow damage passive lives in {@link com.regionsmoba.classes.BonusDamageHook},
 * and the Punch I bow is applied through the ClassKits enchantment table.
 *
 * Bow specials: left-click any bow to cycle between Rain of Arrows and Poison
 * Shot, right-click to fire the selected one. Both are PVP-gated and cost one
 * arrow.
 *
 * DEVIATION: the doc fires a special by drawing the bow; here it fires on
 * right-click at the aimed point. Vanilla's bow draw has no server-side hook
 * that can distinguish "charging a special" from a normal shot without
 * replacing the whole bow item, and a right-click cast keeps the normal shot
 * fully intact.
 */
public final class ArcherAbility {

    /** Refill target. Any value above 1 works; a full stack keeps the HUD steady. */
    public static final int QUIVER_SIZE = 64;

    private ArcherAbility() {}

    public static void tick(ServerPlayer archer) {
        Inventory inventory = archer.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!ItemTags.hasName(stack, ClassKits.Names.ARROW_OF_INFINITY)) continue;
            if (stack.getCount() < QUIVER_SIZE) stack.setCount(QUIVER_SIZE);
            return;
        }
    }

    // ---- Bow specials ----

    public static final int RAIN_COOLDOWN_SECONDS = 30;
    public static final int POISON_COOLDOWN_SECONDS = 50;
    public static final double CAST_RANGE = 32.0;
    public static final double RAIN_RADIUS = 4.0;
    public static final int RAIN_ARROWS = 12;
    public static final int RAIN_SPAWN_HEIGHT = 12;
    public static final int POISON_CLOUD_DURATION_TICKS = 12 * 20;
    public static final float POISON_CLOUD_RADIUS = 3.0f;

    private enum Special {
        RAIN("Rain of Arrows", RAIN_COOLDOWN_SECONDS, "archer:rain"),
        POISON("Poison Shot", POISON_COOLDOWN_SECONDS, "archer:poison");

        final String label;
        final int cooldownSeconds;
        final String cooldownId;

        Special(String label, int cooldownSeconds, String cooldownId) {
            this.label = label;
            this.cooldownSeconds = cooldownSeconds;
            this.cooldownId = cooldownId;
        }
    }

    private static final Map<UUID, Special> SELECTED = new HashMap<>();

    /** Left-click a bow to cycle specials. Driven by the swing hook. */
    public static boolean trySwing(ServerPlayer archer) {
        if (!(archer.getMainHandItem().getItem() instanceof BowItem)) return false;
        Special[] all = Special.values();
        Special current = SELECTED.getOrDefault(archer.getUUID(), all[all.length - 1]);
        Special next = all[(current.ordinal() + 1) % all.length];
        SELECTED.put(archer.getUUID(), next);
        tell(archer, "Bow special: " + next.label + " (" + next.cooldownSeconds + "s)",
                ChatFormatting.LIGHT_PURPLE);
        return true;
    }

    public static boolean tryRightClick(ServerPlayer archer, ItemStack stack) {
        if (!(stack.getItem() instanceof BowItem)) return false;
        Special special = SELECTED.get(archer.getUUID());
        if (special == null) return false; // no special armed — leave the normal shot alone

        if (!Cooldowns.get().ready(archer, special.cooldownId)) {
            tell(archer, special.label + " on cooldown ("
                    + Cooldowns.get().remainingSeconds(archer, special.cooldownId) + "s)", ChatFormatting.GRAY);
            return true;
        }
        if (!PvpManager.isPvpAllowed()) {
            tell(archer, special.label + " requires a combat phase.", ChatFormatting.YELLOW);
            return true;
        }
        if (!consumeArrow(archer)) {
            tell(archer, "No arrows.", ChatFormatting.GRAY);
            return true;
        }

        Vec3 target = aimPoint(archer);
        switch (special) {
            case RAIN -> castRainOfArrows(archer, target);
            case POISON -> castPoisonShot(archer, target);
        }
        Cooldowns.get().set(archer, special.cooldownId, special.cooldownSeconds);
        tell(archer, special.label + "!", ChatFormatting.LIGHT_PURPLE);
        return true;
    }

    /** Drops a ring of arrows into the target circle. */
    private static void castRainOfArrows(ServerPlayer archer, Vec3 target) {
        ServerLevel level = archer.serverLevel();
        for (int i = 0; i < RAIN_ARROWS; i++) {
            double angle = 2 * Math.PI * i / RAIN_ARROWS;
            double x = target.x + Math.cos(angle) * RAIN_RADIUS;
            double z = target.z + Math.sin(angle) * RAIN_RADIUS;
            Arrow arrow = new Arrow(level, x, target.y + RAIN_SPAWN_HEIGHT, z,
                    new ItemStack(Items.ARROW), null);
            arrow.setOwner(archer);
            arrow.setDeltaMovement(0, -1.2, 0);
            level.addFreshEntity(arrow);
        }
    }

    /** Leaves a lingering poison cloud at the target point. */
    private static void castPoisonShot(ServerPlayer archer, Vec3 target) {
        ServerLevel level = archer.serverLevel();
        AreaEffectCloud cloud = new AreaEffectCloud(level, target.x, target.y, target.z);
        cloud.setOwner(archer);
        cloud.setRadius(POISON_CLOUD_RADIUS);
        cloud.setDuration(POISON_CLOUD_DURATION_TICKS);
        cloud.setPotionContents(new PotionContents(Potions.POISON));
        level.addFreshEntity(cloud);
    }

    /**
     * Where the special lands: the first block hit along the aim line, or the
     * far end of the line if it hits nothing.
     */
    private static Vec3 aimPoint(ServerPlayer archer) {
        Vec3 from = archer.getEyePosition(1.0f);
        Vec3 to = from.add(archer.getLookAngle().scale(CAST_RANGE));
        BlockHitResult hit = archer.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, archer));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : to;
    }

    /**
     * Takes one arrow. The Arrow of Infinity stack is preferred, since the
     * per-tick refill puts it straight back — that is what makes a special cost
     * "only 1 arrow" without ever emptying the quiver.
     */
    private static boolean consumeArrow(ServerPlayer archer) {
        Inventory inventory = archer.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(Items.ARROW)) continue;
            stack.shrink(1);
            return true;
        }
        return false;
    }

    public static void clearForPlayer(UUID player) {
        SELECTED.remove(player);
    }

    public static void clearAll() {
        SELECTED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
