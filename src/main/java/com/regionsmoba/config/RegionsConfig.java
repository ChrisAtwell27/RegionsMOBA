package com.regionsmoba.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.regionsmoba.RegionsMOBA;
import com.regionsmoba.team.BiomeTeam;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent map configuration. Mirrors the registered areas, positions, targets,
 * and deposits described in docs/src-md/reference/commands.md. JSON-backed at
 * config/regionsmoba.json.
 *
 * Single-threaded: all reads and writes happen on the server thread. Every mutation
 * triggers a save to disk; loss tolerance is one tick at most.
 */
public final class RegionsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static RegionsConfig instance;
    private static Path configPath;

    public Area lobby;
    public BlockPosData lobbySpawn;

    public Map<String, Area> biomeBounds = new HashMap<>();
    public Map<String, Area> protectedAreas = new HashMap<>();
    public Area chamberBounds;
    public List<BlockPosData> chamberSpawns = new ArrayList<>();

    public BlockPosData conduit;
    public BlockPosData furnace;
    public BlockPosData composter;

    public Map<String, TraderRef> traders = new HashMap<>();
    public Map<String, List<BlockPosData>> spawns = new HashMap<>();

    public BlockPosData wardenSpawn;
    public Map<Integer, BlockPosData> guardianSpawns = new HashMap<>();
    public BlockPosData witherSpawn;

    public List<OreDeposit> oreDeposits = new ArrayList<>();
    public List<BlockDeposit> blockDeposits = new ArrayList<>();
    public List<MobDeposit> mobDeposits = new ArrayList<>();

    /**
     * Every [Nations] sign the server has seen — recorded when one is saved with a
     * valid header, and again on each right-click so signs that predate the mod get
     * picked up. Persisted because {@code /nations join} has to find lobbies without
     * anyone having clicked a sign first, and the in-memory queue registry only
     * knows signs that already have joiners.
     */
    public List<BlockPosData> nationsSigns = new ArrayList<>();

    /** Emeralds per Plains member for the first cold season; scales linearly per season. */
    public int plainsQuotaBase = 24;

    /** Ticks of furnace burn time that render as a full Nether boss bar. */
    public int furnaceBarWindowTicks = 6000;

    private RegionsConfig() {}

    public static RegionsConfig get() {
        if (instance == null) {
            throw new IllegalStateException("RegionsConfig accessed before load()");
        }
        return instance;
    }

    public static void load() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("regionsmoba.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                RegionsConfig loaded = GSON.fromJson(json, RegionsConfig.class);
                if (loaded == null) loaded = new RegionsConfig();
                normalize(loaded);
                instance = loaded;
                RegionsMOBA.LOGGER.info("Loaded config from {}", configPath);
            } catch (IOException | JsonSyntaxException e) {
                RegionsMOBA.LOGGER.error("Failed to read {}; starting with empty config", configPath, e);
                instance = new RegionsConfig();
            }
        } else {
            instance = new RegionsConfig();
            save();
            RegionsMOBA.LOGGER.info("Created new config at {}", configPath);
        }
    }

    /** Replace null collections after Gson load. */
    private static void normalize(RegionsConfig c) {
        if (c.biomeBounds == null) c.biomeBounds = new HashMap<>();
        if (c.protectedAreas == null) c.protectedAreas = new HashMap<>();
        if (c.chamberSpawns == null) c.chamberSpawns = new ArrayList<>();
        if (c.traders == null) c.traders = new HashMap<>();
        if (c.spawns == null) c.spawns = new HashMap<>();
        if (c.guardianSpawns == null) c.guardianSpawns = new HashMap<>();
        if (c.oreDeposits == null) c.oreDeposits = new ArrayList<>();
        if (c.blockDeposits == null) c.blockDeposits = new ArrayList<>();
        if (c.mobDeposits == null) c.mobDeposits = new ArrayList<>();
        if (c.nationsSigns == null) c.nationsSigns = new ArrayList<>();
    }

    /** Record a [Nations] sign position if it isn't known yet. No-op when already present. */
    public static void rememberNationsSign(BlockPosData pos) {
        if (pos == null || instance == null) return;
        if (instance.nationsSigns.contains(pos)) return;
        instance.nationsSigns.add(pos);
        save();
    }

    /** Drop a sign position that is no longer a valid [Nations] sign. */
    public static void forgetNationsSign(BlockPosData pos) {
        if (pos == null || instance == null) return;
        if (instance.nationsSigns.remove(pos)) save();
    }

    public static void save() {
        if (instance == null || configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(instance));
        } catch (IOException e) {
            RegionsMOBA.LOGGER.error("Failed to save {}", configPath, e);
        }
    }

    /** Wipe every registration. Used by /regions reset all. */
    public static void resetAll() {
        instance = new RegionsConfig();
        save();
        com.regionsmoba.lobby.NationsLobbyRegistry.get().dropAll();
    }

    public Area biomeBounds(BiomeTeam team) {
        return biomeBounds.get(team.id());
    }

    /**
     * Every dimension referenced by any registration. Drives map-repair scope —
     * snapshot tracks block changes only in dimensions that have at least one
     * registered area, position, target, or deposit. Cross-dimension setups
     * (overworld + nether biome, etc.) are supported.
     */
    public java.util.Set<String> referencedDimensions() {
        java.util.Set<String> dims = new java.util.HashSet<>();
        addAreaDim(dims, lobby);
        addPosDim(dims, lobbySpawn);
        for (Area a : biomeBounds.values()) addAreaDim(dims, a);
        for (Area a : protectedAreas.values()) addAreaDim(dims, a);
        addAreaDim(dims, chamberBounds);
        for (BlockPosData p : chamberSpawns) addPosDim(dims, p);
        addPosDim(dims, conduit);
        addPosDim(dims, furnace);
        addPosDim(dims, composter);
        for (TraderRef t : traders.values()) if (t != null) addPosDim(dims, t.pos());
        for (java.util.List<BlockPosData> list : spawns.values())
            for (BlockPosData p : list) addPosDim(dims, p);
        addPosDim(dims, wardenSpawn);
        for (BlockPosData p : guardianSpawns.values()) addPosDim(dims, p);
        addPosDim(dims, witherSpawn);
        for (OreDeposit d : oreDeposits) addPosDim(dims, d.pos());
        for (BlockDeposit d : blockDeposits) addPosDim(dims, d.pos());
        for (MobDeposit d : mobDeposits) addPosDim(dims, d.pos());
        return dims;
    }

    private static void addAreaDim(java.util.Set<String> dims, Area a) {
        if (a == null) return;
        if (a.high() != null) dims.add(a.high().dimensionOrDefault());
        if (a.low() != null) dims.add(a.low().dimensionOrDefault());
    }

    private static void addPosDim(java.util.Set<String> dims, BlockPosData p) {
        if (p != null) dims.add(p.dimensionOrDefault());
    }

    public List<BlockPosData> spawnsFor(BiomeTeam team) {
        return spawns.computeIfAbsent(team.id(), k -> new ArrayList<>());
    }

    public TraderRef trader(BiomeTeam team) {
        return traders.get(team.id());
    }

    /** Counts of every registration. Used by /regions status. */
    public Counts counts() {
        int areas = (lobby != null ? 1 : 0) + biomeBounds.size() + protectedAreas.size() + (chamberBounds != null ? 1 : 0);
        int positions = (lobbySpawn != null ? 1 : 0)
                + spawns.values().stream().mapToInt(List::size).sum()
                + chamberSpawns.size()
                + (wardenSpawn != null ? 1 : 0)
                + guardianSpawns.size()
                + (witherSpawn != null ? 1 : 0);
        int targets = (conduit != null ? 1 : 0) + (furnace != null ? 1 : 0) + (composter != null ? 1 : 0) + traders.size();
        int deposits = oreDeposits.size() + blockDeposits.size() + mobDeposits.size();
        return new Counts(areas, positions, targets, deposits);
    }

    public record Counts(int areas, int positions, int targets, int deposits) {}
}
