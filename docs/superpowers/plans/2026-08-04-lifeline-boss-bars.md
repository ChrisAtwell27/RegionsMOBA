# Lifeline Boss Bars Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show every team its lifeline state and the match clock as vanilla boss bars, with no client mod.

**Architecture:** All string and progress computation lives in a pure `BarText` class so it can be unit-tested without a server. A single `LifelineBars` manager owns five `ServerBossEvent` instances, updates them from the existing `END_SERVER_TICK` chain at a 10-tick cadence, and only pushes packets when a rendered value actually changed.

**Tech Stack:** Java 21, Fabric Loader 0.19.2, Fabric API 0.141.3+1.21.11, Minecraft 1.21.11 with official Mojang mappings, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-04-plains-quota-and-lifeline-bars-design.md`

**Prerequisite:** `docs/superpowers/plans/2026-08-04-plains-quota-and-economy.md` must be complete. Task 4 of this plan consumes `PlainsQuota.paid()` and `PlainsQuota.currentQuota()`, and Task 1 assumes the JUnit source set added by that plan's Task 1 already exists.

## Global Constraints

- Server-side only. No client mod. Never import `net.minecraft.client.*`. Boss bars are vanilla protocol (`ClientboundBossEventPacket`) and must stay that way.
- Minecraft 1.21.11, Java 21, official Mojang mappings.
- Verified API: `net.minecraft.server.level.ServerBossEvent(Component, BossEvent.BossBarColor, BossEvent.BossBarOverlay)` with `setProgress(float)`, `setName(Component)`, `setColor(...)`, `setVisible(boolean)`, `addPlayer(ServerPlayer)`, `removePlayer(ServerPlayer)`, `removeAllPlayers()`. Colors: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE. Overlays: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20.
- Update cadence is **10 ticks**. Never push a setter when the value is unchanged.
- Bars are team-scoped. Spectators see the timer plus all four lifeline bars.
- Respawn replaces the `ServerPlayer` instance — bars must be re-attached on `AFTER_RESPAWN` or every player loses their HUD on first death.

---

### Task 1: Pure bar formatting

**Files:**
- Create: `src/main/java/com/regionsmoba/hud/BarText.java`
- Create: `src/test/java/com/regionsmoba/hud/BarTextTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `BarText.mmss(int ticks)`, `BarText.progress(float current, float max)`, `BarText.furnaceProgress(int litTicks, int windowTicks)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/regionsmoba/hud/BarTextTest.java`:

```java
package com.regionsmoba.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarTextTest {

    @Test
    void formatsTicksAsMinutesAndSeconds() {
        assertEquals("00:00", BarText.mmss(0));
        assertEquals("00:01", BarText.mmss(20));
        assertEquals("07:23", BarText.mmss(8860));
        assertEquals("15:00", BarText.mmss(18000));
    }

    @Test
    void negativeTicksClampToZero() {
        assertEquals("00:00", BarText.mmss(-40));
    }

    @Test
    void progressIsClampedToUnitRange() {
        assertEquals(0.0f, BarText.progress(0, 200));
        assertEquals(0.5f, BarText.progress(100, 200));
        assertEquals(1.0f, BarText.progress(300, 200));
        assertEquals(0.0f, BarText.progress(-5, 200));
    }

    @Test
    void progressIsZeroWhenMaxIsNonPositive() {
        assertEquals(0.0f, BarText.progress(50, 0));
    }

    @Test
    void furnaceBarReadsFullBeyondTheWindow() {
        assertEquals(1.0f, BarText.furnaceProgress(20000, 6000));
        assertEquals(1.0f, BarText.furnaceProgress(6000, 6000));
        assertEquals(0.5f, BarText.furnaceProgress(3000, 6000));
        assertEquals(0.0f, BarText.furnaceProgress(0, 6000));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BarTextTest*'`
Expected: FAIL — compilation error, `package com.regionsmoba.hud does not exist`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/regionsmoba/hud/BarText.java`:

```java
package com.regionsmoba.hud;

/**
 * Pure formatting and normalisation for the lifeline boss bars. Free of Minecraft
 * types so it can be unit-tested without bootstrapping a server.
 */
public final class BarText {

    private static final int TICKS_PER_SECOND = 20;

    private BarText() {}

    /** Renders a tick count as mm:ss, clamping negatives to zero. */
    public static String mmss(int ticks) {
        int total = Math.max(0, ticks) / TICKS_PER_SECOND;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    /** Clamped current/max, returning 0 when max is non-positive. */
    public static float progress(float current, float max) {
        if (max <= 0.0f) return 0.0f;
        float p = current / max;
        if (p < 0.0f) return 0.0f;
        if (p > 1.0f) return 1.0f;
        return p;
    }

    /**
     * Furnace burn time normalised against a fixed window: full whenever more than
     * the window remains, draining across the final window. An urgency gauge, not
     * a fuel gauge — fuel items vary from 1600 to 20000 ticks, so there is no
     * natural full.
     */
    public static float furnaceProgress(int litTicks, int windowTicks) {
        return progress(litTicks, windowTicks);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BarTextTest*'`
Expected: PASS — 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/BarText.java src/test/java/com/regionsmoba/hud/BarTextTest.java
git commit -m "test(hud): add pure boss bar formatting"
```

---

### Task 2: Bar manager skeleton and the game timer

**Files:**
- Create: `src/main/java/com/regionsmoba/hud/LifelineBars.java`
- Modify: `src/main/java/com/regionsmoba/RegionsMOBA.java` (tick chain, around lines 64-79)

**Interfaces:**
- Consumes: `BarText`, `Timeline`, `MatchManager`, `MatchPhase`
- Produces: `LifelineBars.get()`, `.tick(MinecraftServer, long globalTick)`, `.addPlayer(ServerPlayer, BiomeTeam)`, `.removePlayer(ServerPlayer)`, `.clearAll()`

- [ ] **Step 1: Write the manager with the timer bar only**

Create `src/main/java/com/regionsmoba/hud/LifelineBars.java`:

```java
package com.regionsmoba.hud;

import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.timeline.MatchPhase;
import com.regionsmoba.timeline.Timeline;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * Owns every boss bar the mod shows. Bars are team-scoped: a player carries the
 * global timer plus their own team's lifeline. Updates run on a 10-tick cadence
 * and only push when a rendered value changed, because every setter sends a
 * packet to every viewer.
 */
public final class LifelineBars {

    private static final int UPDATE_INTERVAL_TICKS = 10;

    private static LifelineBars instance;

    private final ServerBossEvent timer = new ServerBossEvent(
            Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);

    private String lastTimerName = "";
    private float lastTimerProgress = -1.0f;

    private LifelineBars() {}

    public static LifelineBars get() {
        if (instance == null) instance = new LifelineBars();
        return instance;
    }

    /** Adds a player to the timer and to their team's lifeline bar. */
    public void addPlayer(ServerPlayer player, BiomeTeam team) {
        timer.addPlayer(player);
    }

    public void removePlayer(ServerPlayer player) {
        timer.removePlayer(player);
    }

    public void clearAll() {
        timer.removeAllPlayers();
        lastTimerName = "";
        lastTimerProgress = -1.0f;
        instance = null;
    }

    public void tick(MinecraftServer server, long globalTick) {
        if (!MatchManager.get().isActive()) return;
        if (globalTick % UPDATE_INTERVAL_TICKS != 0) return;
        updateTimer();
    }

    private void updateTimer() {
        Timeline tl = Timeline.get();
        MatchPhase phase = tl.phase();
        int remaining = Timeline.PHASE_TICKS - tl.tickInPhase();

        String name = (phase == MatchPhase.COLD ? "Cold" : "Warm")
                + " " + seasonNumber(tl, phase) + " — " + BarText.mmss(remaining)
                + (tl.isPvpPermanent() ? "  ·  PvP permanent" : "");
        float progress = BarText.progress(remaining, Timeline.PHASE_TICKS);

        if (!name.equals(lastTimerName)) {
            timer.setName(Component.literal(name).withStyle(ChatFormatting.WHITE));
            lastTimerName = name;
        }
        if (progress != lastTimerProgress) {
            timer.setProgress(progress);
            lastTimerProgress = progress;
        }
        timer.setColor(phase == MatchPhase.COLD
                ? BossEvent.BossBarColor.BLUE
                : BossEvent.BossBarColor.YELLOW);
    }

    /** 1-based index of the current warm or cold phase. */
    private static int seasonNumber(Timeline tl, MatchPhase phase) {
        int phasesElapsed = (int) (tl.totalTicks() / Timeline.PHASE_TICKS);
        return phasesElapsed / 2 + 1;
    }
}
```

Before implementing, read `Timeline.java` and confirm the exact accessor names for `tickInPhase`, `totalTicks`, and the permanent-PvP predicate. `totalTicks()` exists at `Timeline.java:47` but is currently unused; the permanent-PvP check may be named differently — `PvpManager.isPvpPermanent()` is one candidate. Adjust the three call sites above to whatever the code actually exposes rather than adding new accessors unless none exist.

- [ ] **Step 2: Wire the tick**

In `RegionsMOBA.java`, inside the `END_SERVER_TICK` handler, add after the existing `VisualizationOverlays.tick(...)` call:

```java
            LifelineBars.get().tick(server, globalTick);
```

Match the local variable name already used for the tick counter at that site.

- [ ] **Step 3: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java src/main/java/com/regionsmoba/RegionsMOBA.java
git commit -m "feat(hud): add boss bar manager with match timer"
```

---

### Task 3: Player attachment lifecycle

This task is separated because it is the one most likely to be silently wrong: bars attached to a stale `ServerPlayer` vanish on respawn with no error.

**Files:**
- Modify: `src/main/java/com/regionsmoba/hud/LifelineBars.java`
- Modify: `src/main/java/com/regionsmoba/lobby/LobbyFlow.java:89` (team pick)
- Modify: `src/main/java/com/regionsmoba/death/DeathHandler.java:82` (after respawn)
- Modify: `src/main/java/com/regionsmoba/match/MatchManager.java` (the reset blocks at lines 166, 276, 331)

- [ ] **Step 1: Track per-team membership in the manager**

Replace the placeholder `addPlayer` body from Task 2 with real routing. Add a field holding one bar per team and route on the team argument:

```java
    private final java.util.Map<BiomeTeam, ServerBossEvent> lifelines =
            new java.util.EnumMap<>(BiomeTeam.class);

    public void addPlayer(ServerPlayer player, BiomeTeam team) {
        timer.addPlayer(player);
        ServerBossEvent bar = lifelines.get(team);
        if (bar != null) bar.addPlayer(player);
    }

    public void removePlayer(ServerPlayer player) {
        timer.removePlayer(player);
        for (ServerBossEvent bar : lifelines.values()) bar.removePlayer(player);
    }

    /** Spectators watch every lifeline plus the timer. */
    public void addSpectator(ServerPlayer player) {
        timer.addPlayer(player);
        for (ServerBossEvent bar : lifelines.values()) bar.addPlayer(player);
    }
```

The `lifelines` map is populated by Tasks 4 through 7; until then it is empty and `addPlayer` degrades to timer-only, which still builds and runs.

Update `clearAll()` to call `removeAllPlayers()` on every entry in `lifelines` as well as on `timer`.

- [ ] **Step 2: Attach on team pick**

In `LobbyFlow.onTeamPicked`, immediately after the existing `TeamPassives.apply(...)` call, add:

```java
        LifelineBars.get().addPlayer(sp, team);
```

Match the local variable names already in scope at that site.

- [ ] **Step 3: Re-attach on respawn**

In `DeathHandler`'s `AFTER_RESPAWN` handler, after the existing `TeamPassives.apply(newPlayer, state.team);` at line 82, add:

```java
            LifelineBars.get().addPlayer(newPlayer, state.team);
```

In the spectator branch above it, which currently calls `teleportToLobby(newPlayer)` and returns, add before the return:

```java
                LifelineBars.get().addSpectator(newPlayer);
```

- [ ] **Step 4: Clear on match start and end**

In each of the three reset blocks in `MatchManager` (lines 166, 276, 331), add alongside the other singleton resets:

```java
        LifelineBars.get().clearAll();
```

- [ ] **Step 5: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual verification**

Start a match, pick a team, confirm the timer bar appears and counts down. **Die once and confirm the bar is still present after respawn** — this is the regression this task exists to prevent. Run `/regions abort` and confirm the bar disappears.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java src/main/java/com/regionsmoba/lobby/LobbyFlow.java src/main/java/com/regionsmoba/death/DeathHandler.java src/main/java/com/regionsmoba/match/MatchManager.java
git commit -m "feat(hud): attach boss bars across team pick, respawn and match end"
```

---

### Task 4: Ocean conduit bar

**Files:**
- Modify: `src/main/java/com/regionsmoba/hud/LifelineBars.java`

- [ ] **Step 1: Register the bar and update it**

In the `LifelineBars` constructor, add:

```java
        lifelines.put(BiomeTeam.OCEAN, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_20));
```

Add a tracked-last-value field pair per bar, following the `lastTimerName` / `lastTimerProgress` pattern, then add to `tick()` after `updateTimer()`:

```java
    private void updateConduit() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.OCEAN);
        if (bar == null) return;
        LifelineState ls = LifelineState.get();
        int hp = ls.conduitHp;
        boolean dead = hp <= 0;

        String name = dead
                ? "CONDUIT DESTROYED — next death is final"
                : "Conduit — " + hp + " / " + LifelineState.CONDUIT_MAX_HP;
        float progress = BarText.progress(hp, LifelineState.CONDUIT_MAX_HP);

        if (!name.equals(lastConduitName)) {
            bar.setName(Component.literal(name).withStyle(
                    dead ? ChatFormatting.DARK_RED : ChatFormatting.AQUA));
            lastConduitName = name;
        }
        if (progress != lastConduitProgress) {
            bar.setProgress(progress);
            lastConduitProgress = progress;
        }
        bar.setColor(dead ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE);
    }
```

Read `LifelineState.java` first and use the actual field name for conduit HP — the shape above assumes `conduitHp` and the constant `CONDUIT_MAX_HP` at line 10.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Join Ocean, run `/regions debug conduit hp 150`, confirm the bar reads `Conduit — 150 / 200` at three-quarters fill. Run `/regions debug conduit hp 0` and confirm it turns red with the permadeath text.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java
git commit -m "feat(hud): add Ocean conduit boss bar"
```

---

### Task 5: Nether furnace bar

**Files:**
- Modify: `src/main/java/com/regionsmoba/hud/LifelineBars.java`

- [ ] **Step 1: Register and update**

Constructor:

```java
        lifelines.put(BiomeTeam.NETHER, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS));
```

Update method, called from `tick()`:

```java
    private void updateFurnace(MinecraftServer server) {
        ServerBossEvent bar = lifelines.get(BiomeTeam.NETHER);
        if (bar == null) return;

        int litTicks = furnaceLitTicks(server);
        boolean out = litTicks <= 0;
        boolean cold = Timeline.get().phase() == MatchPhase.COLD;

        String name = out
                ? "FURNACE OUT — 1 heart / 30s"
                : "Furnace — " + BarText.mmss(litTicks) + " remaining" + (cold ? "  (2x burn)" : "");
        float progress = BarText.furnaceProgress(
                litTicks, RegionsConfig.get().furnaceBarWindowTicks);

        if (!name.equals(lastFurnaceName)) {
            bar.setName(Component.literal(name).withStyle(
                    out ? ChatFormatting.RED : ChatFormatting.GOLD));
            lastFurnaceName = name;
        }
        if (progress != lastFurnaceProgress) {
            bar.setProgress(progress);
            lastFurnaceProgress = progress;
        }
        bar.setColor(out ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
    }

    /** Remaining burn ticks on the registered furnace, or 0 if unlit/missing. */
    private static int furnaceLitTicks(MinecraftServer server) {
        BlockPosData ref = RegionsConfig.get().furnace;
        if (ref == null) return 0;
        ServerLevel level = server.getLevel(ref.dimensionKey());
        if (level == null) return 0;
        BlockEntity be = level.getBlockEntity(ref.toBlockPos());
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) return 0;
        return ((FurnaceAccessor) (Object) furnace).regionsmoba$getLitTimeRemaining();
    }
```

This reuses the existing accessor at `mixin/FurnaceAccessor.java:16`. No new mixin is required. Mirror the null-guard sequence in `FurnaceLifeline.tick` at lines 56-62 rather than inventing a new one.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Join Nether. Run `/regions debug furnace fuel 3000` and confirm the bar reads half full with about 2:30 remaining. Run `/regions debug furnace unlit` and confirm it turns red with the outage text. Run `/regions debug season cold` with the furnace lit and confirm the bar visibly drains at double rate.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java
git commit -m "feat(hud): add Nether furnace boss bar"
```

---

### Task 6: Mountain blood tribute bar

**Files:**
- Modify: `src/main/java/com/regionsmoba/hud/LifelineBars.java`

- [ ] **Step 1: Register and update**

Constructor:

```java
        lifelines.put(BiomeTeam.MOUNTAIN, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS));
```

Update method:

```java
    private void updateBloodTribute() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.MOUNTAIN);
        if (bar == null) return;

        Timeline tl = Timeline.get();
        boolean active = tl.phase() == MatchPhase.COLD;
        bar.setVisible(active);
        if (!active) return;

        boolean satisfied = LifelineState.get().bloodTributeSatisfied;
        int remaining = Timeline.PHASE_TICKS - tl.tickInPhase();

        String name = satisfied
                ? "Blood Tribute paid"
                : "Blood Tribute — " + BarText.mmss(remaining) + " left";
        float progress = satisfied ? 1.0f : BarText.progress(remaining, Timeline.PHASE_TICKS);

        if (!name.equals(lastTributeName)) {
            bar.setName(Component.literal(name).withStyle(
                    satisfied ? ChatFormatting.GREEN : ChatFormatting.RED));
            lastTributeName = name;
        }
        if (progress != lastTributeProgress) {
            bar.setProgress(progress);
            lastTributeProgress = progress;
        }
        bar.setColor(satisfied ? BossEvent.BossBarColor.GREEN : BossEvent.BossBarColor.RED);
    }
```

Read `LifelineState.java` and `BloodTributeLifeline.java` first to confirm the actual field name for the satisfied flag — the shape above assumes `bloodTributeSatisfied`.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Join Mountain during a warm phase and confirm no tribute bar is shown. Run `/regions debug season cold` and confirm a red countdown bar appears. Run `/regions debug bloodtribute satisfy` and confirm it snaps to full and green — this is now the only in-game confirmation that the tribute landed, since `BloodTributeLifeline.satisfy()` sends no broadcast.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java
git commit -m "feat(hud): add Mountain blood tribute boss bar"
```

---

### Task 7: Plains quota bar

**Files:**
- Modify: `src/main/java/com/regionsmoba/hud/LifelineBars.java`

**Interfaces:**
- Consumes: `PlainsQuota.paid()`, `PlainsQuota.currentQuota()` from the economy plan's Task 4

- [ ] **Step 1: Register and update**

Constructor:

```java
        lifelines.put(BiomeTeam.PLAINS, new ServerBossEvent(
                Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS));
```

Update method:

```java
    private static final int QUOTA_WARNING_TICKS = 20 * 60;

    private void updateQuota() {
        ServerBossEvent bar = lifelines.get(BiomeTeam.PLAINS);
        if (bar == null) return;

        int paid = PlainsQuota.paid();
        int quota = PlainsQuota.currentQuota();
        boolean met = quota > 0 && paid >= quota;

        Timeline tl = Timeline.get();
        int toCheck = Timeline.PHASE_TICKS - tl.tickInPhase();
        boolean urgent = !met && tl.phase() == MatchPhase.WARM && toCheck <= QUOTA_WARNING_TICKS;

        String name = met
                ? "Quota met"
                : "Quota — " + paid + " / " + quota + " emeralds";
        float progress = BarText.progress(paid, quota);

        if (!name.equals(lastQuotaName)) {
            bar.setName(Component.literal(name).withStyle(
                    urgent ? ChatFormatting.RED : ChatFormatting.GREEN));
            lastQuotaName = name;
        }
        if (progress != lastQuotaProgress) {
            bar.setProgress(progress);
            lastQuotaProgress = progress;
        }
        bar.setColor(urgent ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN);
    }
```

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Join Plains, run `/regions debug quota set 0` and confirm the bar reads `Quota — 0 / N emeralds` empty. Pay emeralds at the composter and confirm the bar fills as the chat message reports payment. Run `/regions debug quota set 9999` and confirm it reads `Quota met` at full green. Run `/regions debug seasontimer 30` during a warm phase with the quota unmet and confirm the bar turns red.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/hud/LifelineBars.java
git commit -m "feat(hud): add Plains quota boss bar"
```

---

### Task 8: Documentation

**Files:**
- Create: `docs/src-md/reference/boss-bars.md`
- Modify: `mkdocs.yml` (nav, Reference section)

- [ ] **Step 1: Write the page**

Create `docs/src-md/reference/boss-bars.md` documenting: which bars each team sees, what the timer shows including the permanent-PvP suffix, the conduit HP readout, the furnace urgency-window behavior and why it is a five-minute window rather than a fuel gauge, the tribute bar's hidden-during-warm behavior and green-on-satisfied state, and the quota bar including the red warning state. Transcribe the exact rendered strings from `LifelineBars.java` rather than paraphrasing.

- [ ] **Step 2: Add it to the nav**

In `mkdocs.yml`, under the `Reference:` nav section, add an entry for `reference/boss-bars.md`. `mkdocs.yml` lists pages explicitly, so a page not added to nav will not appear on the site.

- [ ] **Step 3: Verify**

Run `mkdocs build --strict` if mkdocs is installed locally. Expected: no warnings about pages missing from nav.

- [ ] **Step 4: Commit**

```bash
git add docs/src-md/reference/boss-bars.md mkdocs.yml
git commit -m "docs: document lifeline boss bars"
```
