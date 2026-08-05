package com.regionsmoba.classes.impl;

import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mountain Tinkerer — PowerPads.
 *
 * Per docs/src-md/classes/mountain-classes.md:
 *   Place a PowerPad block; a pressure plate appears on top. Stepping on it
 *   (self or teammate) grants a buff:
 *     Redstone Block  → Speed I     45s
 *     Coal Block      → Haste I     45s
 *     Diamond Block   → Speed II    20s
 *     Gold Block      → Haste II    15s
 *     Deepslate Block → Absorption I 20s
 *   Stepping on a pad refreshes its buff to full duration. A higher-level buff
 *   is not replaced by a lower-level pad of the same family until it expires.
 *   The Tinkerer earns XP each time a teammate receives a new buff from one of
 *   their pads. Pads last until broken; an enemy break fragments the block into
 *   8 items, 4 to the owner and 4 to the breaker.
 *
 * Disenchanting (the 10 spawn books) is not implemented — it needs a crafting
 * recipe hook rather than an ability listener.
 */
public final class TinkererAbility {

    /** How often pads scan for someone standing on them. */
    public static final int SCAN_INTERVAL_TICKS = 10;
    public static final int ENEMY_BREAK_FRAGMENTS = 8;
    public static final int XP_PER_TEAMMATE_BUFF = 2;

    private record PadSpec(Holder<MobEffect> effect, int amplifier, int durationTicks, String label) {}

    private static final Map<Block, PadSpec> PADS = new HashMap<>();

    static {
        PADS.put(Blocks.REDSTONE_BLOCK, new PadSpec(MobEffects.SPEED, 0, 45 * 20, "Speed I"));
        PADS.put(Blocks.COAL_BLOCK, new PadSpec(MobEffects.HASTE, 0, 45 * 20, "Haste I"));
        PADS.put(Blocks.DIAMOND_BLOCK, new PadSpec(MobEffects.SPEED, 1, 20 * 20, "Speed II"));
        PADS.put(Blocks.GOLD_BLOCK, new PadSpec(MobEffects.HASTE, 1, 15 * 20, "Haste II"));
        PADS.put(Blocks.DEEPSLATE, new PadSpec(MobEffects.ABSORPTION, 0, 20 * 20, "Absorption I"));
    }

    /** The plate that sits on top of a pad. Cosmetic — detection is positional. */
    private static final Block PLATE = Blocks.STONE_PRESSURE_PLATE;

    private record Pad(UUID owner, BlockPosData pos, Block block) {}

    /** Every placed pad, keyed by position so break lookups are O(1). */
    private static final Map<BlockPosData, Pad> PADS_PLACED = new HashMap<>();

    private TinkererAbility() {}

    // ---- Placement ----

    public static boolean tryRightClick(ServerPlayer tinkerer, ItemStack stack, BlockHitResult hit) {
        if (hit == null) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        Block block = blockItem.getBlock();
        PadSpec spec = PADS.get(block);
        if (spec == null) return false;

        ServerLevel level = tinkerer.level();
        BlockPos target = hit.getBlockPos().relative(hit.getDirection());
        if (!level.getBlockState(target).canBeReplaced()) {
            tell(tinkerer, "No room to place the PowerPad there.", ChatFormatting.GRAY);
            return true;
        }
        BlockPos above = target.above();
        if (!level.getBlockState(above).canBeReplaced()) {
            tell(tinkerer, "A PowerPad needs a free block above it for the plate.", ChatFormatting.GRAY);
            return true;
        }

        level.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(above, PLATE.defaultBlockState(), Block.UPDATE_ALL);
        PADS_PLACED.put(BlockPosData.of(level, target), new Pad(tinkerer.getUUID(), BlockPosData.of(level, target), block));
        stack.shrink(1);

        tell(tinkerer, "PowerPad placed — " + spec.label() + ".", ChatFormatting.GOLD);
        return true;
    }

    // ---- Buff delivery ----

    public static void tick(MinecraftServer server, long globalTick) {
        if (PADS_PLACED.isEmpty() || server == null) return;
        if (globalTick % SCAN_INTERVAL_TICKS != 0) return;

        for (Pad pad : PADS_PLACED.values()) {
            ServerLevel level = server.getLevel(pad.pos().dimensionKey());
            if (level == null) continue;
            BlockPos padPos = pad.pos().toBlockPos();
            if (!level.hasChunkAt(padPos)) continue;
            PadSpec spec = PADS.get(pad.block());
            if (spec == null) continue;

            // Anyone whose feet are in the plate block directly above the pad.
            BlockPos plate = padPos.above();
            AABB box = new AABB(plate).inflate(0.3, 0.6, 0.3);
            List<Player> standing = level.getEntitiesOfClass(Player.class, box, p -> !p.isSpectator());
            for (Player p : standing) {
                applyPad(server, pad, spec, p);
            }
        }
    }

    private static void applyPad(MinecraftServer server, Pad pad, PadSpec spec, Player player) {
        // Don't downgrade: a stronger buff of the same family keeps priority
        // until it expires.
        MobEffectInstance current = player.getEffect(spec.effect());
        if (current != null && current.getAmplifier() > spec.amplifier()) return;

        boolean isNew = current == null;
        player.addEffect(new MobEffectInstance(
                spec.effect(), spec.durationTicks(), spec.amplifier(), true, false, true));

        // XP only for buffing someone else, and only on a fresh application —
        // a refresh every scan would otherwise pay out forever.
        if (!isNew || player.getUUID().equals(pad.owner())) return;
        MatchPlayerState ownerState = TeamAssignments.get().state(pad.owner());
        MatchPlayerState targetState = TeamAssignments.get().state(player.getUUID());
        if (ownerState == null || targetState == null || ownerState.team != targetState.team) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(pad.owner());
        if (owner != null) owner.giveExperiencePoints(XP_PER_TEAMMATE_BUFF);
    }

    // ---- Breaking ----

    /**
     * Drops the enemy-break fragments and de-registers the pad. The plate above
     * is cleared too, so a broken pad never leaves a floating plate behind.
     */
    public static void onBroken(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        Pad pad = PADS_PLACED.remove(BlockPosData.of(level, pos));
        if (pad == null) return;

        BlockPos plate = pos.above();
        if (level.getBlockState(plate).is(PLATE)) {
            level.setBlock(plate, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        if (breaker.getUUID().equals(pad.owner())) return;

        // Enemy break: 8 fragments total, split evenly. Vanilla already dropped
        // the pad block itself at the break site, so that counts as the
        // breaker's first fragment and only the remainder is popped — otherwise
        // the break would yield 9.
        int half = ENEMY_BREAK_FRAGMENTS / 2;
        int breakerRemainder = Math.max(0, half - 1);
        if (breakerRemainder > 0) {
            Block.popResource(level, pos, new ItemStack(pad.block().asItem(), breakerRemainder));
        }
        ServerPlayer owner = level.getServer() == null
                ? null : level.getServer().getPlayerList().getPlayer(pad.owner());
        if (owner != null) {
            owner.getInventory().add(new ItemStack(pad.block().asItem(), half));
            tell(owner, "A PowerPad was destroyed — " + half + " fragments recovered.", ChatFormatting.RED);
        }
    }

    public static void clearForPlayer(UUID player) {
        PADS_PLACED.values().removeIf(pad -> pad.owner().equals(player));
    }

    public static void clearAll() {
        PADS_PLACED.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
