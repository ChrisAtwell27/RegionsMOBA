# Solo Test Mode and Admin Team Switch — Design

Date: 2026-08-10
Status: Approved for planning

## Problem

The mod cannot currently be playtested by one person.

`/regions start` calls `startInternal(server, Set.of())` — zero joiners (`MatchManager.java:136-137`). `TeamAssignments.reset(joiners)` then seeds `MatchPlayerState` only for that empty collection (`TeamAssignments.java:34-36`), so `TeamAssignments.state(you)` returns null. Every debug command that operates on player state rejects the operator with "is not in the current match". A match can be started but not entered.

The only other entry path is the `[Nations]` sign, which requires a join count that is a positive multiple of 4 — a minimum of four players.

Separately, the admin team-switch that does exist, `/regions debug team <player> <biome>` (`DebugCommands.java:241`), wipes `biomeClass` and leaves the player classless with no kit, and fails outright when the target has no state.

Finally, match-end suppression currently happens by accident. `MatchEndConditions.tick()` returns early unless at least two teams have ever had members (`MatchEndConditions.java:36-43`). That guard exists to stop a match ending seconds after start, not to support solo play. The moment team switching records joins for more than one team, a solo operator becomes "last team standing" and the match ends underneath them.

## Goals

Let one operator start a match, enter it, move between all four teams, and play indefinitely without the match auto-ending or declaring a winner. Make the win path triggerable on demand rather than only reachable by eliminating three teams.

## Non-goals

No invulnerability. Lifeline penalties still apply in test mode — a failed Plains quota still costs a life, and three failures still make the operator a spectator. Recovery is `/regions debug lives <player> 3`. Freezing life loss was considered and deliberately rejected: it would hide exactly the lifeline behavior a playtest exists to exercise.

Micro-games are out of scope and get their own spec.

## Design

### Test mode flag

New `com.regionsmoba.debug.TestMode`, modelled on `com.regionsmoba.protection.BuildMode` — the existing debug-toggle precedent: a `final` class, private constructor, static state, in-memory only.

```java
public final class TestMode {
    private static boolean active;
    private TestMode() {}
    public static boolean isActive() { return active; }
    public static void set(boolean on) { active = on; }
    public static void clear() { active = false; }
}
```

Unlike `BuildMode`, this is global rather than per-player: it suppresses a match-wide condition, not a per-operator permission.

`TestMode.clear()` is called from each of the three places `MatchManager` already calls `LifelineState.get().resetAll()` (match start, match-end restore, server stop). Test mode must never survive a server restart or silently persist into a real match.

### `/regions debug testmode on|off`

On, in order:

1. If already on, warn and return without side effects.
2. Require a player source. Fail with a clear message if run from console — the command adds *the sender* to the match, so a console sender has nothing to add.
3. If no match is active, `MatchManager.get().start(server)`.
4. `MatchManager.get().addMatchPlayer(uuid)` — a new method rather than mutating the set returned by the `matchPlayers()` getter, which currently exposes its internal field directly (`MatchManager.java:128-129`).
5. `TeamAssignments.get().join(uuid)` — new method; adds a fresh `MatchPlayerState` if absent, no-op if present. Distinct from `reset(joiners)`, which clears everything.
6. `TestMode.set(true)`.
7. `LobbyFlow.openTeamPicker(sender)` so the operator lands somewhere playable in one command.

**This order is load-bearing.** Step 3 starts a match, and match start is one of the paths that calls `TestMode.clear()`. Setting the flag before starting the match would silently clear it, leaving the operator in a match that still auto-ends — the exact failure the command exists to prevent, and one that looks like it worked until a winner is declared minutes later. The flag must be set after any match start, never before.

Off clears the flag only. The match keeps running and auto-end resumes, so the operator can flip test mode off to watch the real end condition fire.

### Auto-end suppression

One early return at the top of `MatchEndConditions.tick()`, after the existing `isActive` check:

```java
if (TestMode.isActive()) return;
```

This replaces an accident with an intention. The `teamsWithMembersEver < 2` guard stays — it still serves its original purpose — but is no longer what solo play depends on.

### Force-end with a winner

`/regions debug win <biome>` broadcasts the winner line and calls `MatchManager.get().abort()`, mirroring what `MatchEndConditions` does on a natural end so the two paths stay consistent. `/regions abort` already covers ending without a winner and is unchanged.

The biome argument uses the same string-plus-`BiomeTeam.fromId` pattern as the existing `/regions debug team` command.

### Team switch

`/regions debug team <player> <biome>` gains three behaviors:

**Auto-join.** If the target has no `MatchPlayerState` and a match is active, create it via `addMatchPlayer` + `join` instead of failing. This doubles as the join path for a second tester, so no separate join command is needed. If no match is active, it still fails as today.

**Teleport.** After the switch, `LobbyFlow.teleportToTeamSpawn(target, team)` (already `public`). Without this the player is left standing in what is now enemy territory.

**Reopen the class picker.** After clearing `biomeClass` and applying the new passives, call `LobbyFlow.openClassPicker(target, team)` so the player lands in a playable state in one step.

Existing behavior is otherwise preserved: clear the old team's passives, set the new team, apply the new passives.

### Visibility changes

`LobbyFlow.openTeamPicker(ServerPlayer)` and `LobbyFlow.openClassPicker(ServerPlayer, BiomeTeam)` are both currently `private static` (`LobbyFlow.java:126,132`). Both must become `public static` — `DebugCommands` lives in `com.regionsmoba.command.sub`, a different package, so package-private is insufficient.

## Files

| File | Change |
| --- | --- |
| `debug/TestMode.java` | new |
| `match/MatchManager.java` | add `addMatchPlayer(UUID)`; call `TestMode.clear()` in the three reset paths |
| `team/TeamAssignments.java` | add `join(UUID)` |
| `lobby/LobbyFlow.java` | widen two picker methods to `public` |
| `match/MatchEndConditions.java` | one early return |
| `command/sub/DebugCommands.java` | two new commands; three fixes to `setTeam` |

## Edge cases

Running `testmode on` twice warns and does nothing. Running it from console fails with a message naming the reason. Running `testmode off` when it is already off is a no-op with a message rather than an error.

`/regions abort` while test mode is on ends the match and clears the flag through the normal reset path, so the operator cannot end up with test mode set and no match running.

`/regions debug win <biome>` works whether or not test mode is on; it is a manual end trigger, not a test-mode feature.

A player switched to a team they are already on still gets teleported and re-prompted for a class. This is intentional — it doubles as a "put me back at spawn with a fresh class pick" reset.

## Verification

All of this is operator tooling with no pure logic worth unit-testing; it is verified in-game. The sequence that exercises every piece: `/regions debug testmode on` (match starts, team picker opens), pick a team, pick a class, `/regions debug team <self> mountain` (teleports, reopens picker), play past a cold-season boundary and confirm no winner is declared, then `/regions debug win plains` and confirm the winner broadcast and match end.
