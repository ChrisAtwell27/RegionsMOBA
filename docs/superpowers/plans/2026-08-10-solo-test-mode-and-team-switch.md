# Solo Test Mode and Admin Team Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one operator start a match, enter it, switch teams freely, and play indefinitely without the match auto-ending or declaring a winner.

**Architecture:** A global `TestMode` boolean gates one early return in `MatchEndConditions`. Two small plumbing methods (`MatchManager.addMatchPlayer`, `TeamAssignments.join`) make it possible to add a player to a running match, which is the thing that is currently impossible. Two new debug commands and three fixes to the existing team-switch sit on top.

**Tech Stack:** Java 21, Fabric Loader 0.19.2, Fabric API 0.141.3+1.21.11, Minecraft 1.21.11, official Mojang mappings, Brigadier commands.

**Spec:** `docs/superpowers/specs/2026-08-10-solo-test-mode-and-team-switch-design.md`

## Global Constraints

- Server-side only. Never import `net.minecraft.client.*`. Never register a block/item/entity/registry entry. Never send a custom payload packet.
- Java 21, Minecraft 1.21.11, official Mojang mappings.
- All `/regions` commands are already operator-gated at `RegionsCommand.java:40`. Do NOT add per-command permission checks.
- Follow the file's existing conventions: `CommandHelpers.ok` for successful mutations, `.info` for read-only status, `.warn` for no-op notices, `.fail` for errors. Utility classes have a private constructor.
- Test mode must NEVER survive a server restart and must never silently persist into a real match.
- **No invulnerability.** Lifeline penalties still apply in test mode. Do not add life-loss suppression.
- `./gradlew build` must pass before every commit.

---

### Task 1: Plumbing — TestMode, player join, auto-end guard

No user-visible behavior lands in this task; it builds everything Task 2 and 3 stand on. It must compile and leave existing behavior unchanged.

**Files:**
- Create: `src/main/java/com/regionsmoba/debug/TestMode.java`
- Modify: `src/main/java/com/regionsmoba/match/MatchManager.java`
- Modify: `src/main/java/com/regionsmoba/team/TeamAssignments.java`
- Modify: `src/main/java/com/regionsmoba/match/MatchEndConditions.java`
- Modify: `src/main/java/com/regionsmoba/lobby/LobbyFlow.java`

**Interfaces:**
- Consumes: nothing
- Produces: `TestMode.isActive()`, `TestMode.set(boolean)`, `TestMode.clear()`; `MatchManager.get().addMatchPlayer(UUID)`; `TeamAssignments.get().join(UUID)`; `LobbyFlow.openTeamPicker(ServerPlayer)` and `LobbyFlow.openClassPicker(ServerPlayer, BiomeTeam)` both `public`

- [ ] **Step 1: Create TestMode**

Create `src/main/java/com/regionsmoba/debug/TestMode.java`. Modelled on `com.regionsmoba.protection.BuildMode`, the existing debug-toggle precedent, but global rather than per-player because it suppresses a match-wide condition rather than a per-operator permission.

```java
package com.regionsmoba.debug;

/**
 * Suppresses automatic match end so one operator can play indefinitely.
 * Toggled by /regions debug testmode.
 *
 * In-memory and global — this gates a match-wide condition, not a per-player
 * permission (contrast BuildMode). Cleared on every match start, match end and
 * server stop so it can never survive a restart or leak into a scored match.
 */
public final class TestMode {

    private static boolean active;

    private TestMode() {}

    public static boolean isActive() {
        return active;
    }

    public static void set(boolean on) {
        active = on;
    }

    public static void clear() {
        active = false;
    }
}
```

- [ ] **Step 2: Add MatchManager.addMatchPlayer**

Add to `MatchManager`, next to the existing `matchPlayers()` getter:

```java
    /**
     * Adds one player to a running match. Used by the debug tooling to let an
     * operator enter a match started with no joiners. No-op if already present.
     */
    public void addMatchPlayer(UUID player) {
        matchPlayers.add(player);
    }
```

A dedicated method rather than mutating the set returned by `matchPlayers()`, which currently exposes its internal field directly.

- [ ] **Step 3: Clear TestMode in MatchManager's reset paths**

`MatchManager` calls `LifelineState.get().resetAll()` in three places (match start, match-end restore, server stop). Add alongside each:

```java
        com.regionsmoba.debug.TestMode.clear();
```

Find the three real call sites by searching for `LifelineState.get().resetAll()` — do not trust line numbers. Prefer a normal import over the fully-qualified name if the file's import block makes that natural.

- [ ] **Step 4: Add TeamAssignments.join**

Add to `TeamAssignments`, near the existing `reset(Collection<UUID>)`:

```java
    /**
     * Ensures one player has match state, without disturbing anyone else.
     * Distinct from {@link #reset(java.util.Collection)}, which clears everything.
     * No-op if the player already has state.
     */
    public void join(UUID player) {
        states.computeIfAbsent(player, k -> new MatchPlayerState());
    }
```

Confirm the backing field is named `states` by reading the class first; match whatever it actually is.

- [ ] **Step 5: Suppress auto-end**

In `MatchEndConditions.tick()`, immediately after the existing `if (!MatchManager.get().isActive() || server == null) return;` line, add:

```java
        if (TestMode.isActive()) return;
```

with `import com.regionsmoba.debug.TestMode;`. Leave the existing `teamsWithMembersEver < 2` guard exactly as it is — it still serves its original purpose of not ending a match seconds after start.

- [ ] **Step 6: Widen the two picker methods**

In `LobbyFlow`, change `private static void openTeamPicker(ServerPlayer sp)` to `public static void openTeamPicker(ServerPlayer sp)` and `private static void openClassPicker(ServerPlayer sp, BiomeTeam team)` to `public static void openClassPicker(ServerPlayer sp, BiomeTeam team)`. Both must be `public`, not package-private: `DebugCommands` lives in `com.regionsmoba.command.sub`, a different package. Change nothing else about either method.

- [ ] **Step 7: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. No behavior has changed yet — `TestMode.isActive()` is always false, so the new guard never fires.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/regionsmoba/debug/TestMode.java src/main/java/com/regionsmoba/match src/main/java/com/regionsmoba/team/TeamAssignments.java src/main/java/com/regionsmoba/lobby/LobbyFlow.java
git commit -m "feat(debug): add TestMode flag and mid-match player join plumbing"
```

---

### Task 2: `/regions debug testmode` and `/regions debug win`

**Files:**
- Modify: `src/main/java/com/regionsmoba/command/sub/DebugCommands.java`

**Interfaces:**
- Consumes: `TestMode.isActive/set`, `MatchManager.get().addMatchPlayer(UUID)`, `TeamAssignments.get().join(UUID)`, `LobbyFlow.openTeamPicker(ServerPlayer)`
- Produces: `/regions debug testmode on|off`, `/regions debug win <biome>`

- [ ] **Step 1: Register both commands**

Add to the `debug` command tree, following the same shape as the neighbouring `team` branch:

```java
                .then(Commands.literal("testmode")
                        .then(Commands.literal("on").executes(ctx -> testMode(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> testMode(ctx.getSource(), false))))
                .then(Commands.literal("win")
                        .then(Commands.argument("biome", StringArgumentType.word())
                                .suggests(CommandHelpers.BIOME_SUGGESTIONS)
                                .executes(ctx -> forceWin(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "biome")))))
```

- [ ] **Step 2: Implement the testmode handler**

```java
    private static int testMode(CommandSourceStack src, boolean on) {
        if (!on) {
            if (!TestMode.isActive()) {
                CommandHelpers.warn(src, "Test mode is already off.");
                return 0;
            }
            TestMode.set(false);
            CommandHelpers.ok(src, "Test mode off — automatic match end is live again.");
            return 1;
        }

        if (TestMode.isActive()) {
            CommandHelpers.warn(src, "Test mode is already on.");
            return 0;
        }

        ServerPlayer sender = src.getPlayer();
        if (sender == null) {
            CommandHelpers.fail(src, "Run this as a player — test mode adds the sender to the match.");
            return 0;
        }

        MatchManager match = MatchManager.get();
        boolean started = false;
        if (!match.isActive()) {
            match.start(src.getServer());
            started = true;
        }

        // Order matters: match start clears TestMode, so the flag is set AFTER.
        match.addMatchPlayer(sender.getUUID());
        TeamAssignments.get().join(sender.getUUID());
        TestMode.set(true);

        CommandHelpers.ok(src, started
                ? "Match started, test mode on — pick a team."
                : "Test mode on — you have joined the running match.");
        LobbyFlow.openTeamPicker(sender);
        return 1;
    }
```

**The comment about ordering is not decorative.** `MatchManager.start` runs the reset path that calls `TestMode.clear()`. Setting the flag before starting the match silently clears it, producing a match that still auto-ends — and it looks like it worked until a winner is declared minutes into a playtest. Never move `TestMode.set(true)` above the start call.

- [ ] **Step 3: Implement the force-win handler**

Mirrors what `MatchEndConditions` broadcasts on a natural end, so the manual and automatic paths read identically to players.

```java
    private static int forceWin(CommandSourceStack src, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }
        MatchManager match = MatchManager.get();
        if (!match.isActive()) {
            CommandHelpers.fail(src, "No match is running.");
            return 0;
        }
        BiomeTeam winner = team.get();
        Component msg = Component.literal("Match over — " + winner.displayName() + " wins!")
                .withStyle(winner.color(), ChatFormatting.BOLD);
        for (ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
        }
        match.abort();
        CommandHelpers.ok(src, "Match ended — " + winner.displayName() + " declared winner.");
        return 1;
    }
```

Add any missing imports: `com.regionsmoba.debug.TestMode`, `com.regionsmoba.lobby.LobbyFlow`, `net.minecraft.ChatFormatting`, `net.minecraft.network.chat.Component`. Several may already be present — check before adding.

- [ ] **Step 4: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/regionsmoba/command/sub/DebugCommands.java
git commit -m "feat(debug): add testmode toggle and force-win commands"
```

---

### Task 3: Team-switch fixes

**Files:**
- Modify: `src/main/java/com/regionsmoba/command/sub/DebugCommands.java` (the `setTeam` handler)

**Interfaces:**
- Consumes: `MatchManager.get().addMatchPlayer(UUID)`, `TeamAssignments.get().join(UUID)`, `LobbyFlow.teleportToTeamSpawn(ServerPlayer, BiomeTeam)`, `LobbyFlow.openClassPicker(ServerPlayer, BiomeTeam)`

- [ ] **Step 1: Rewrite setTeam**

Replace the existing `setTeam` body with:

```java
    private static int setTeam(CommandSourceStack src, ServerPlayer target, String biomeId) {
        Optional<BiomeTeam> team = BiomeTeam.fromId(biomeId);
        if (team.isEmpty()) {
            CommandHelpers.fail(src, "Unknown biome: " + biomeId);
            return 0;
        }

        MatchManager match = MatchManager.get();
        MatchPlayerState state = TeamAssignments.get().state(target.getUUID());
        if (state == null) {
            if (!match.isActive()) {
                CommandHelpers.fail(src, target.getGameProfile().getName() + " is not in the current match.");
                return 0;
            }
            // Auto-join: doubles as the join path for a second tester.
            match.addMatchPlayer(target.getUUID());
            TeamAssignments.get().join(target.getUUID());
            state = TeamAssignments.get().state(target.getUUID());
        }

        if (state.team != null) TeamPassives.clear(target, state.team);
        state.team = team.get();
        state.biomeClass = null;
        TeamPassives.apply(target, team.get());
        LobbyFlow.teleportToTeamSpawn(target, team.get());
        LobbyFlow.openClassPicker(target, team.get());

        CommandHelpers.ok(src, target.getGameProfile().getName() + " → " + team.get().displayName()
                + " (teleported; pick a class)");
        return 1;
    }
```

Switching a player to the team they are already on is allowed and intentional — it doubles as a "return me to spawn with a fresh class pick" reset.

- [ ] **Step 2: Verify the build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/regionsmoba/command/sub/DebugCommands.java
git commit -m "fix(debug): team switch auto-joins, teleports and reopens class picker"
```

---

## In-game verification

No unit tests: this is operator tooling with no pure logic worth testing in isolation, per the spec. The sequence below exercises every piece and is the acceptance test.

1. `/regions debug testmode on` — a match starts and the team picker opens.
2. Pick a team, then pick a class. Confirm the kit is granted.
3. `/regions debug team <self> mountain` — you teleport to the Mountain spawn and the class picker reopens.
4. Play past a cold-season boundary. Confirm no winner is declared and the match keeps running.
5. `/regions debug testmode off`, then `on` again — confirm the toggle messages are correct and no second match starts.
6. `/regions debug win plains` — the winner broadcast fires and the match ends.
7. `/regions debug testmode on` again, then `/regions abort` — confirm test mode does not persist into the next match.
