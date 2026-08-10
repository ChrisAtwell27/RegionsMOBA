package com.regionsmoba.debug;

import com.regionsmoba.config.Area;
import com.regionsmoba.config.BlockDeposit;
import com.regionsmoba.config.BlockPosData;
import com.regionsmoba.config.MobDeposit;
import com.regionsmoba.config.OreDeposit;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.team.BiomeTeam;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-operator particle overlays for /regions debug show. State is in-memory:
 * a Set of enabled overlays per operator UUID. Each second, the tick fires
 * particles to each operator's client only (sendParticles with the player
 * argument set), so other players don't see the debug visualisation.
 *
 * Per docs/src-md/reference/commands.md "Visualization" section:
 *   show protected   — outline every protected area in red particles
 *   show deposits    — highlight deposits with regen-timer particles
 *   show spawns      — mark all team spawn points
 *   show biomes      — outline biome bounds in team colours
 *   show lifelines   — mark Conduit, Furnace, Composter
 *   show lobby       — outline the lobby area
 *   show all / off   — bulk toggle
 */
public final class VisualizationOverlays {

    public enum Overlay { PROTECTED, DEPOSITS, SPAWNS, BIOMES, LIFELINES, LOBBY }

    public static final int TICK_INTERVAL = 20;
    public static final int EDGE_SAMPLES_PER_BLOCK = 1;

    private static final DustParticleOptions DUST_RED = dust(0xFF3344);
    private static final DustParticleOptions DUST_WHITE = dust(0xEEEEEE);
    private static final DustParticleOptions DUST_OCEAN = dust(0x4477FF);
    private static final DustParticleOptions DUST_NETHER = dust(0xFF3322);
    private static final DustParticleOptions DUST_PLAINS = dust(0x44CC55);
    private static final DustParticleOptions DUST_MOUNTAIN = dust(0xAAAAAA);
    private static final SimpleParticleType HAPPY = ParticleTypes.HAPPY_VILLAGER;
    private static final SimpleParticleType END_ROD = ParticleTypes.END_ROD;
    private static final SimpleParticleType SOUL = ParticleTypes.SOUL_FIRE_FLAME;

    private static final Map<UUID, Set<Overlay>> enabled = new HashMap<>();

    private VisualizationOverlays() {}

    /** DustParticleOptions takes an RGB vector on 1.21.1, not a packed int. */
    private static DustParticleOptions dust(int rgb) {
        return new DustParticleOptions(
                new Vector3f(
                        ((rgb >> 16) & 0xFF) / 255.0f,
                        ((rgb >> 8) & 0xFF) / 255.0f,
                        (rgb & 0xFF) / 255.0f),
                1.0f);
    }

    public static void clearAll() {
        enabled.clear();
    }

    public static Set<Overlay> overlaysFor(UUID player) {
        return enabled.getOrDefault(player, Set.of());
    }

    public static void enable(UUID player, Overlay overlay) {
        enabled.computeIfAbsent(player, k -> EnumSet.noneOf(Overlay.class)).add(overlay);
    }

    public static void disable(UUID player, Overlay overlay) {
        Set<Overlay> set = enabled.get(player);
        if (set != null) set.remove(overlay);
    }

    public static void enableAll(UUID player) {
        enabled.put(player, EnumSet.allOf(Overlay.class));
    }

    public static void disableAll(UUID player) {
        enabled.remove(player);
    }

    public static void toggle(UUID player, Overlay overlay) {
        Set<Overlay> set = enabled.computeIfAbsent(player, k -> EnumSet.noneOf(Overlay.class));
        if (!set.add(overlay)) set.remove(overlay);
    }

    public static void tick(MinecraftServer server, long globalTick) {
        if (globalTick % TICK_INTERVAL != 0) return;
        if (server == null || enabled.isEmpty()) return;
        for (Map.Entry<UUID, Set<Overlay>> e : enabled.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Set<Overlay> set = e.getValue();
            if (set.isEmpty()) continue;
            ServerLevel level = p.serverLevel();
            for (Overlay o : set) emit(level, p, o);
        }
    }

    private static void emit(ServerLevel level, ServerPlayer p, Overlay overlay) {
        switch (overlay) {
            case PROTECTED -> {
                for (Area area : RegionsConfig.get().protectedAreas.values()) {
                    if (area != null && area.isComplete()) emitAreaEdges(level, p, area, DUST_RED);
                }
            }
            case LOBBY -> {
                Area lobby = RegionsConfig.get().lobby;
                if (lobby != null && lobby.isComplete()) emitAreaEdges(level, p, lobby, DUST_WHITE);
            }
            case BIOMES -> {
                for (BiomeTeam t : BiomeTeam.values()) {
                    Area a = RegionsConfig.get().biomeBounds(t);
                    if (a != null && a.isComplete()) emitAreaEdges(level, p, a, dustFor(t));
                }
            }
            case SPAWNS -> {
                for (BiomeTeam t : BiomeTeam.values()) {
                    List<BlockPosData> spawns = RegionsConfig.get().spawnsFor(t);
                    DustParticleOptions colour = dustFor(t);
                    for (BlockPosData s : spawns) emitMarker(level, p, s, colour, 6);
                }
            }
            case DEPOSITS -> {
                for (OreDeposit d : RegionsConfig.get().oreDeposits) emitMarker(level, p, d.pos(), null, HAPPY);
                for (BlockDeposit d : RegionsConfig.get().blockDeposits) emitMarker(level, p, d.pos(), null, HAPPY);
                for (MobDeposit d : RegionsConfig.get().mobDeposits) emitMarker(level, p, d.pos(), null, SOUL);
            }
            case LIFELINES -> {
                BlockPosData c = RegionsConfig.get().conduit;
                if (c != null) emitMarker(level, p, c, DUST_OCEAN, 12);
                BlockPosData f = RegionsConfig.get().furnace;
                if (f != null) emitMarker(level, p, f, DUST_NETHER, 12);
                BlockPosData co = RegionsConfig.get().composter;
                if (co != null) emitMarker(level, p, co, DUST_PLAINS, 12);
                for (Map.Entry<String, TraderRef> e : RegionsConfig.get().traders.entrySet()) {
                    BiomeTeam t = BiomeTeam.fromId(e.getKey()).orElse(null);
                    if (t == null) continue;
                    emitMarker(level, p, e.getValue().pos(), dustFor(t), 6);
                }
            }
        }
    }

    private static DustParticleOptions dustFor(BiomeTeam team) {
        return switch (team) {
            case OCEAN -> DUST_OCEAN;
            case NETHER -> DUST_NETHER;
            case PLAINS -> DUST_PLAINS;
            case MOUNTAIN -> DUST_MOUNTAIN;
        };
    }

    private static void emitAreaEdges(ServerLevel level, ServerPlayer p, Area area, DustParticleOptions dust) {
        BlockPosData hi = area.high();
        BlockPosData lo = area.low();
        if (hi == null || lo == null) return;
        if (!hi.dimensionOrDefault().equals(level.dimension().location().toString())) return;
        // 12 cuboid edges. Sample at 1 particle per block.
        // Bottom rectangle
        sampleEdge(level, p, dust, lo.x(), lo.y(), lo.z(), hi.x() + 1, lo.y(), lo.z());
        sampleEdge(level, p, dust, lo.x(), lo.y(), hi.z() + 1, hi.x() + 1, lo.y(), hi.z() + 1);
        sampleEdge(level, p, dust, lo.x(), lo.y(), lo.z(), lo.x(), lo.y(), hi.z() + 1);
        sampleEdge(level, p, dust, hi.x() + 1, lo.y(), lo.z(), hi.x() + 1, lo.y(), hi.z() + 1);
        // Top rectangle
        sampleEdge(level, p, dust, lo.x(), hi.y() + 1, lo.z(), hi.x() + 1, hi.y() + 1, lo.z());
        sampleEdge(level, p, dust, lo.x(), hi.y() + 1, hi.z() + 1, hi.x() + 1, hi.y() + 1, hi.z() + 1);
        sampleEdge(level, p, dust, lo.x(), hi.y() + 1, lo.z(), lo.x(), hi.y() + 1, hi.z() + 1);
        sampleEdge(level, p, dust, hi.x() + 1, hi.y() + 1, lo.z(), hi.x() + 1, hi.y() + 1, hi.z() + 1);
        // 4 vertical pillars
        sampleEdge(level, p, dust, lo.x(), lo.y(), lo.z(), lo.x(), hi.y() + 1, lo.z());
        sampleEdge(level, p, dust, hi.x() + 1, lo.y(), lo.z(), hi.x() + 1, hi.y() + 1, lo.z());
        sampleEdge(level, p, dust, lo.x(), lo.y(), hi.z() + 1, lo.x(), hi.y() + 1, hi.z() + 1);
        sampleEdge(level, p, dust, hi.x() + 1, lo.y(), hi.z() + 1, hi.x() + 1, hi.y() + 1, hi.z() + 1);
    }

    private static void sampleEdge(ServerLevel level, ServerPlayer p, DustParticleOptions dust,
                                   double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0) return;
        int samples = Math.max(1, (int) (len * EDGE_SAMPLES_PER_BLOCK));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            level.sendParticles(p, dust, true, x1 + dx * t, y1 + dy * t, z1 + dz * t, 1, 0, 0, 0, 0);
        }
    }

    private static void emitMarker(ServerLevel level, ServerPlayer p, BlockPosData pos,
                                   DustParticleOptions dust, int count) {
        if (!pos.dimensionOrDefault().equals(level.dimension().location().toString())) return;
        for (int i = 0; i < count; i++) {
            double x = pos.x() + 0.5 + (Math.random() - 0.5) * 0.6;
            double y = pos.y() + 1.2 + Math.random() * 0.4;
            double z = pos.z() + 0.5 + (Math.random() - 0.5) * 0.6;
            level.sendParticles(p, dust, true, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    private static void emitMarker(ServerLevel level, ServerPlayer p, BlockPosData pos,
                                   DustParticleOptions dust, SimpleParticleType type) {
        if (!pos.dimensionOrDefault().equals(level.dimension().location().toString())) return;
        if (type != null) {
            level.sendParticles(p, type, true, pos.x() + 0.5, pos.y() + 1.2, pos.z() + 0.5, 4, 0.2, 0.2, 0.2, 0.01);
        } else if (dust != null) {
            level.sendParticles(p, dust, true, pos.x() + 0.5, pos.y() + 1.2, pos.z() + 0.5, 4, 0.2, 0.2, 0.2, 0);
        }
    }

    @SuppressWarnings("unused")
    private static final SimpleParticleType KEEP_END_ROD = END_ROD;
}
