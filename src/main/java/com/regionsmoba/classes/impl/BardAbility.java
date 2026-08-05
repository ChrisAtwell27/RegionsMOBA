package com.regionsmoba.classes.impl;

import com.regionsmoba.classes.ClassKits;
import com.regionsmoba.classes.Cooldowns;
import com.regionsmoba.classes.ItemTags;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.pvp.PvpManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
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
 * Plains Bard — the Buffbox.
 *
 * Per docs/src-md/classes/plains-classes.md:
 *   Place the Buffbox and right-click it to select from 4 songs:
 *     Invigorate — teammates, Regeneration I, 20s
 *     Enlighten  — teammates, Speed I, 25s
 *     Intimidate — enemies, Weakness III, 20s
 *     Shackle    — enemies, Slowness II, 15s
 *   Affects players within 15 blocks, shown by note particles. Songs pass
 *   through walls and loop continuously. Switching songs has a 15-second
 *   cooldown. Enemy-target songs only land during a PVP window — outside it the
 *   Buffbox still plays (particles and sound fire) but nothing is applied.
 *   Only the owner or an enemy may break it; teammates cannot. Owner
 *   break/recall costs a 10-second re-place cooldown, an enemy break costs 30.
 *
 * One Buffbox per Bard. Songs re-apply on a 1-second cadence at full duration,
 * which is what makes them "loop continuously" — walking out of range simply
 * lets the last application decay.
 */
public final class BardAbility {

    public static final double RADIUS = 15.0;
    public static final int SWITCH_COOLDOWN_SECONDS = 15;
    public static final int OWNER_REPLACE_COOLDOWN_SECONDS = 10;
    public static final int ENEMY_REPLACE_COOLDOWN_SECONDS = 30;

    private static final String SWITCH_COOLDOWN_ID = "bard:song_switch";
    private static final String REPLACE_COOLDOWN_ID = "bard:replace";

    /** The Buffbox block. Matches the jukebox the kit hands out. */
    public static final Block BUFFBOX_BLOCK = Blocks.JUKEBOX;

    private enum Target { TEAM, ENEMY }

    private record Song(String name, Target target, Holder<MobEffect> effect,
                        int amplifier, int durationTicks, ChatFormatting colour) {}

    private static final List<Song> SONGS = List.of(
            new Song("Invigorate", Target.TEAM, MobEffects.REGENERATION, 0, 20 * 20, ChatFormatting.LIGHT_PURPLE),
            new Song("Enlighten", Target.TEAM, MobEffects.SPEED, 0, 25 * 20, ChatFormatting.AQUA),
            new Song("Intimidate", Target.ENEMY, MobEffects.WEAKNESS, 2, 20 * 20, ChatFormatting.DARK_GRAY),
            new Song("Shackle", Target.ENEMY, MobEffects.SLOWNESS, 1, 15 * 20, ChatFormatting.BLUE));

    private static final class Buffbox {
        final BlockPosData pos;
        final BiomeTeam team;
        int songIndex;

        Buffbox(BlockPosData pos, BiomeTeam team) {
            this.pos = pos;
            this.team = team;
        }
    }

    /** One Buffbox per Bard, keyed by owner. */
    private static final Map<UUID, Buffbox> BOXES = new HashMap<>();

    private BardAbility() {}

    // ---- Placement and song selection ----

    public static boolean tryRightClick(ServerPlayer bard, ItemStack stack, BlockHitResult hit) {
        if (hit == null) return false;

        // Clicking an existing Buffbox cycles its song, whatever is being held.
        Buffbox existing = BOXES.get(bard.getUUID());
        if (existing != null && existing.pos.toBlockPos().equals(hit.getBlockPos())) {
            return cycleSong(bard, existing);
        }

        if (!ItemTags.hasName(stack, ClassKits.Names.BUFFBOX)) return false;
        return place(bard, stack, hit);
    }

    private static boolean place(ServerPlayer bard, ItemStack stack, BlockHitResult hit) {
        if (BOXES.containsKey(bard.getUUID())) {
            tell(bard, "Your Buffbox is already placed — break or recall it first.", ChatFormatting.GRAY);
            return true;
        }
        if (!Cooldowns.get().ready(bard, REPLACE_COOLDOWN_ID)) {
            tell(bard, "Buffbox re-place on cooldown ("
                    + Cooldowns.get().remainingSeconds(bard, REPLACE_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        MatchPlayerState state = TeamAssignments.get().state(bard.getUUID());
        if (state == null || state.team == null) return true;

        ServerLevel level = bard.level();
        BlockPos target = hit.getBlockPos().relative(hit.getDirection());
        if (!level.getBlockState(target).canBeReplaced()) {
            tell(bard, "No room to place the Buffbox there.", ChatFormatting.GRAY);
            return true;
        }

        level.setBlock(target, BUFFBOX_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        BOXES.put(bard.getUUID(), new Buffbox(BlockPosData.of(level, target), state.team));
        // The Buffbox item is deliberately NOT consumed. Breaking the placed
        // jukebox drops a plain, unnamed jukebox, so consuming the named item
        // would permanently destroy the Bard's only Buffbox the first time
        // anyone broke it. One-box-per-Bard is enforced by the BOXES map and the
        // re-place cooldown instead.

        Song song = SONGS.get(0);
        tell(bard, "Buffbox placed — playing " + song.name() + ".", song.colour());
        return true;
    }

    private static boolean cycleSong(ServerPlayer bard, Buffbox box) {
        if (!Cooldowns.get().ready(bard, SWITCH_COOLDOWN_ID)) {
            tell(bard, "Song switch on cooldown ("
                    + Cooldowns.get().remainingSeconds(bard, SWITCH_COOLDOWN_ID) + "s)", ChatFormatting.GRAY);
            return true;
        }
        box.songIndex = (box.songIndex + 1) % SONGS.size();
        Cooldowns.get().set(bard, SWITCH_COOLDOWN_ID, SWITCH_COOLDOWN_SECONDS);
        Song song = SONGS.get(box.songIndex);
        tell(bard, "Now playing: " + song.name(), song.colour());
        return true;
    }

    // ---- Playback ----

    /** Re-applies each Buffbox's song once a second and emits its note particles. */
    public static void tick(MinecraftServer server, long globalTick) {
        if (BOXES.isEmpty() || server == null) return;
        if (globalTick % 20 != 0) return;

        for (Map.Entry<UUID, Buffbox> entry : BOXES.entrySet()) {
            Buffbox box = entry.getValue();
            ServerLevel level = server.getLevel(box.pos.dimensionKey());
            if (level == null) continue;
            BlockPos pos = box.pos.toBlockPos();
            if (!level.hasChunkAt(pos)) continue;

            Song song = SONGS.get(box.songIndex);
            playAmbience(level, pos);

            // Particles and sound fire regardless; only the effect is PVP-gated.
            boolean enemySong = song.target() == Target.ENEMY;
            if (enemySong && !PvpManager.isPvpAllowed()) continue;

            AABB area = new AABB(pos).inflate(RADIUS);
            List<Player> nearby = level.getEntitiesOfClass(Player.class, area, p -> {
                MatchPlayerState s = TeamAssignments.get().state(p.getUUID());
                if (s == null || s.team == null || s.spectator) return false;
                return enemySong ? s.team != box.team : s.team == box.team;
            });
            for (Player p : nearby) {
                p.addEffect(new MobEffectInstance(
                        song.effect(), song.durationTicks(), song.amplifier(), true, false, true));
            }
        }
    }

    private static void playAmbience(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.NOTE,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                6, 0.6, 0.3, 0.6, 0.0);
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 0.6f, 1.0f);
    }

    // ---- Breaking ----

    /**
     * Break gate. Returns false to cancel — teammates of the owner may not break
     * another Bard's Buffbox. The owner and any enemy may.
     */
    public static boolean allowBreak(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        Map.Entry<UUID, Buffbox> owned = boxAt(level, pos);
        if (owned == null) return true;
        if (owned.getKey().equals(breaker.getUUID())) return true;

        MatchPlayerState breakerState = TeamAssignments.get().state(breaker.getUUID());
        if (breakerState != null && breakerState.team == owned.getValue().team) {
            tell(breaker, "That's your team's Buffbox.", ChatFormatting.RED);
            return false;
        }
        return true;
    }

    /** Clears the registration and starts the owner's re-place cooldown. */
    public static void onBroken(ServerPlayer breaker, ServerLevel level, BlockPos pos) {
        Map.Entry<UUID, Buffbox> owned = boxAt(level, pos);
        if (owned == null) return;
        UUID ownerId = owned.getKey();
        BOXES.remove(ownerId);

        boolean byOwner = ownerId.equals(breaker.getUUID());
        int cooldown = byOwner ? OWNER_REPLACE_COOLDOWN_SECONDS : ENEMY_REPLACE_COOLDOWN_SECONDS;
        ServerPlayer owner = level.getServer() == null
                ? null : level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            Cooldowns.get().set(owner, REPLACE_COOLDOWN_ID, cooldown);
            tell(owner, byOwner
                            ? "Buffbox recovered — " + cooldown + "s before you can re-place."
                            : "Your Buffbox was destroyed by an enemy — " + cooldown + "s re-place cooldown.",
                    byOwner ? ChatFormatting.GRAY : ChatFormatting.RED);
        }
    }

    private static Map.Entry<UUID, Buffbox> boxAt(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().identifier().toString();
        for (Map.Entry<UUID, Buffbox> entry : BOXES.entrySet()) {
            Buffbox box = entry.getValue();
            if (box.pos.toBlockPos().equals(pos) && box.pos.dimensionOrDefault().equals(dimension)) {
                return entry;
            }
        }
        return null;
    }

    public static void clearForPlayer(UUID player) {
        BOXES.remove(player);
    }

    public static void clearAll() {
        BOXES.clear();
    }

    private static void tell(ServerPlayer p, String msg, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }
}
