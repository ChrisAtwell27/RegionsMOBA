package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.Area;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ocean Transporter — linked portal pairs.
 *
 * Per docs/src-md/classes/ocean-classes.md:
 *   Right-click the Nether Quartz on a block to convert it to nether quartz ore
 *   — the first portal block. A second right-click elsewhere creates its pair.
 *   Only one pair per Transporter. Portals cannot be placed underwater, with a
 *   block within 2 above, or inside a protected area. Sneak over a portal to
 *   travel to its partner, on a 2-second cooldown carried by the block rather
 *   than the traveller. Enemies cannot travel but CAN break portals; teammates
 *   cannot break them. Each trip earns the owner 1 XP. A completed pair
 *   survives death; a lone unpaired block is destroyed when the owner dies.
 *
 * Naming via {@code /portal <name>} is NOT implemented — it needs a new command
 * plus a hologram, neither of which exists in this codebase yet. Everything
 * that affects movement and combat is here.
 *
 * The deposit-overlap rule ("a regenerating deposit that would spawn over a
 * portal destroys it") is also not implemented; it needs a hook inside the
 * deposit regen path rather than anything on this class.
 */
public final class TransporterAbility {

    public static final Block PORTAL_BLOCK = Blocks.NETHER_QUARTZ_ORE;
    public static final int TRAVEL_COOLDOWN_TICKS = 40; // 2 seconds, per block
    public static final int HEADROOM_BLOCKS = 2;
    public static final int XP_PER_TRAVEL = 1;
    public static final int PARTICLE_INTERVAL_TICKS = 20;

    private static final class Pair {
        final UUID owner;
        final BiomeTeam team;
        BlockPosData first;
        BlockPosData second;
        /** Travel cooldowns keyed by portal block — the doc puts them on the block. */
        final Map<BlockPosData, Long> cooldowns = new HashMap<>();

        Pair(UUID owner, BiomeTeam team, BlockPosData first) {
            this.owner = owner;
            this.team = team;
            this.first = first;
        }

        boolean complete() {
            return first != null && second != null;
        }
    }

    private static final Map<UUID, Pair> PAIRS = new HashMap<>();

    private TransporterAbility() {}

    // ---- Placement ----

    public static boolean tryRightClick(ServerPlayer transporter, ItemStack stack, BlockHitResult hit) {
        if (hit == null) return false;
        if (!ItemTags.hasName(stack, ClassKits.Names.TRANSPORTER_QUARTZ)) return false;

        ServerLevel level = transporter.serverLevel();
        BlockPos clicked = hit.getBlockPos();
        Pair pair = PAIRS.get(transporter.getUUID());

        // Clicking your own portal block dismantles the pair.
        if (pair != null && sideAt(pair, level, clicked) != null) {
            dismantle(level, pair);
            PAIRS.remove(transporter.getUUID());
            tell(transporter, "Portal pair dismantled.", ChatFormatting.GRAY);
            return true;
        }

        BlockPos target = clicked.relative(hit.getDirection());
        if (!canPlace(transporter, level, target)) return true;

        MatchPlayerState state = TeamAssignments.get().state(transporter.getUUID());
        if (state == null || state.team == null) return true;

        level.setBlock(target, PORTAL_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        BlockPosData placed = BlockPosData.of(level, target);

        if (pair == null || pair.complete()) {
            // Starting a new first block replaces any finished pair, per the doc.
            if (pair != null) dismantle(level, pair);
            PAIRS.put(transporter.getUUID(), new Pair(transporter.getUUID(), state.team, placed));
            tell(transporter, "First portal placed — set its pair.", ChatFormatting.AQUA);
        } else {
            pair.second = placed;
            tell(transporter, "Portal pair linked.", ChatFormatting.AQUA);
        }
        return true;
    }

    private static boolean canPlace(ServerPlayer transporter, ServerLevel level, BlockPos target) {
        if (!level.getBlockState(target).canBeReplaced()) {
            tell(transporter, "No room for a portal there.", ChatFormatting.GRAY);
            return false;
        }
        if (!level.getFluidState(target).isEmpty()) {
            tell(transporter, "Portals cannot be placed underwater.", ChatFormatting.GRAY);
            return false;
        }
        for (int dy = 1; dy <= HEADROOM_BLOCKS; dy++) {
            if (!level.getBlockState(target.above(dy)).isAir()) {
                tell(transporter, "A portal needs " + HEADROOM_BLOCKS + " blocks of headroom.",
                        ChatFormatting.GRAY);
                return false;
            }
        }
        if (insideProtectedArea(level, target)) {
            tell(transporter, "Portals cannot be placed inside a protected area.", ChatFormatting.RED);
            return false;
        }
        return true;
    }

    private static boolean insideProtectedArea(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        for (Area area : RegionsConfig.get().protectedAreas.values()) {
            if (area == null || !area.isComplete()) continue;
            if (!area.dimension().equals(dimension)) continue;
            if (area.contains(pos)) return true;
        }
        return false;
    }

    // ---- Travel ----

    public static void tick(MinecraftServer server, long globalTick) {
        if (PAIRS.isEmpty() || server == null) return;

        if (globalTick % PARTICLE_INTERVAL_TICKS == 0) {
            for (Pair pair : PAIRS.values()) {
                markPortal(server, pair.first);
                markPortal(server, pair.second);
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isShiftKeyDown()) continue;
            if (!(player.level() instanceof ServerLevel level)) continue;
            MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
            if (state == null || state.team == null || state.spectator) continue;

            BlockPos under = player.blockPosition().below();
            for (Pair pair : PAIRS.values()) {
                // Enemies can't travel — only the owner's own team may.
                if (!pair.complete() || pair.team != state.team) continue;
                BlockPosData from = sideAt(pair, level, under);
                if (from == null) continue;
                BlockPosData to = from.equals(pair.first) ? pair.second : pair.first;
                travel(server, player, pair, from, to, globalTick);
                break;
            }
        }
    }

    private static void travel(MinecraftServer server, ServerPlayer player, Pair pair,
                               BlockPosData from, BlockPosData to, long globalTick) {
        Long ready = pair.cooldowns.get(from);
        if (ready != null && globalTick < ready) return;

        ServerLevel destination = server.getLevel(to.dimensionKey());
        if (destination == null) return;
        BlockPos target = to.toBlockPos().above();
        player.teleportTo(destination, target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                Set.<RelativeMovement>of(), player.getYRot(), player.getXRot());
        // Both ends go on cooldown so the arrival pad doesn't bounce you straight back.
        pair.cooldowns.put(from, globalTick + TRAVEL_COOLDOWN_TICKS);
        pair.cooldowns.put(to, globalTick + TRAVEL_COOLDOWN_TICKS);
        destination.playSound(null, target, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.2f);

        ServerPlayer owner = server.getPlayerList().getPlayer(pair.owner);
        if (owner != null) owner.giveExperiencePoints(XP_PER_TRAVEL);
    }

    private static void markPortal(MinecraftServer server, BlockPosData pos) {
        if (pos == null) return;
        ServerLevel level = server.getLevel(pos.dimensionKey());
        if (level == null) return;
        BlockPos block = pos.toBlockPos();
        if (!level.hasChunkAt(block)) return;
        level.sendParticles(ParticleTypes.CLOUD,
                block.getX() + 0.5, block.getY() + 1.1, block.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.0);
    }

    // ---- Breaking ----

    /** Teammates may not break a portal; the owner and enemies may. */
    public static boolean allowBreak(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        for (Pair pair : PAIRS.values()) {
            if (sideAt(pair, level, pos) == null) continue;
            if (pair.owner.equals(breaker.getUUID())) return true;
            MatchPlayerState breakerState = TeamAssignments.get().state(breaker.getUUID());
            if (breakerState != null && breakerState.team == pair.team) {
                tell(breaker, "That's your team's portal.", ChatFormatting.RED);
                return false;
            }
            return true;
        }
        return true;
    }

    /** Breaking either side tears down the whole pair and alerts the owner. */
    public static void onBroken(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        var it = PAIRS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pair> entry = it.next();
            Pair pair = entry.getValue();
            if (sideAt(pair, level, pos) == null) continue;
            it.remove();
            dismantle(level, pair);

            if (breaker.getUUID().equals(pair.owner)) return;
            ServerPlayer owner = level.getServer() == null
                    ? null : level.getServer().getPlayerList().getPlayer(pair.owner);
            if (owner != null) {
                owner.level().playSound(null, owner.blockPosition(), SoundEvents.CONDUIT_DEACTIVATE,
                        SoundSource.PLAYERS, 1.0f, 1.0f);
                tell(owner, "Your portal was destroyed by " + breaker.getGameProfile().getName() + "!",
                        ChatFormatting.RED);
            }
            return;
        }
    }

    /**
     * A completed pair survives death; a lone unpaired block does not. Called
     * from the death handler for every player, and no-ops unless they have an
     * incomplete pair on the map.
     */
    public static void onOwnerDeath(ServerPlayer transporter) {
        Pair pair = PAIRS.get(transporter.getUUID());
        if (pair == null || pair.complete()) return;
        if (transporter.level() instanceof ServerLevel level) dismantle(level, pair);
        PAIRS.remove(transporter.getUUID());
        tell(transporter, "Your unpaired portal block was lost on death.", ChatFormatting.GRAY);
    }

    private static void dismantle(ServerLevel level, Pair pair) {
        clearBlock(level, pair.first);
        clearBlock(level, pair.second);
    }

    private static void clearBlock(ServerLevel level, BlockPosData pos) {
        if (pos == null || level.getServer() == null) return;
        ServerLevel target = level.getServer().getLevel(pos.dimensionKey());
        if (target == null) return;
        BlockPos block = pos.toBlockPos();
        if (target.getBlockState(block).is(PORTAL_BLOCK)) {
            target.setBlock(block, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Returns whichever side of the pair sits at this position, or null. */
    private static BlockPosData sideAt(Pair pair, ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        if (samePlace(pair.first, dimension, pos)) return pair.first;
        if (samePlace(pair.second, dimension, pos)) return pair.second;
        return null;
    }

    private static boolean samePlace(BlockPosData data, String dimension, BlockPos pos) {
        return data != null && data.dimensionOrDefault().equals(dimension) && data.toBlockPos().equals(pos);
    }

    public static void clearForPlayer(UUID player) {
        PAIRS.remove(player);
    }

    public static void clearAll() {
        PAIRS.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
