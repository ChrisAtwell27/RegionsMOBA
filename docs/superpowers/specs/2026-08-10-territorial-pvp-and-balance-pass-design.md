# Territorial PvP and Balance Pass — Design

Date: 2026-08-10
Status: Approved for planning

## Problem

A design walkthrough surfaced four structural problems.

**Nether's lifeline cannot hurt them.** `FurnaceLifeline` damages at 1 heart every 30 seconds with a hard floor of 2 HP, so an unfueled furnace is mathematically incapable of killing anyone. Every other team's lifeline can cost lives.

**Plains' emerald monopoly is a tax, not an advantage.** Plains is the only team whose currency has a mandatory sink. With three members the quota runs 72 → 144 → 216 → 288, roughly 720 emeralds across four cold seasons spent entirely on avoiding a penalty, while every other team converts surplus straight into gear.

**Class choice is locked at match start.** A player who picks badly, or whose team needs a different role after the first cold season, has no recourse.

**PvP flips globally on a known clock, which rewards pre-positioning over fighting.** Nether's Rift Walker has no PvP gating and may legally insert a four-player squad at an enemy spawn during a warm phase; Bloodmage Terraform, Bard Buffboxes and Tinkerer PowerPads can all be placed during peace. The optimal opening is therefore never "fight" — it is "camp inside enemy territory and strike at the bell."

## Goals

Make every lifeline capable of costing lives. Turn Plains' quota into a payoff rather than pure penalty avoidance. Allow class changes at a meaningful cost. Make enemy territory dangerous to occupy, so pre-positioning carries risk instead of being free.

## Non-goals

No change to the warm/cold phase rhythm, the cold-season environmental effects, or the Trial Chamber's always-on PvP. No rebalancing of individual class abilities. Micro-games remain out of scope.

---

## 1. Lethal furnace

In `lifeline/FurnaceLifeline.java`:

- `DAMAGE_INTERVAL_TICKS` becomes `40` (2 seconds), from `600`.
- The `DAMAGE_FLOOR_HP` constant and the headroom clamp that uses it are **deleted**. Damage is applied in full.
- `DAMAGE_AMOUNT` stays `2.0f` (one heart).
- The damage source stays `damageSources().wither()`, which is deliberate — it bypasses the Nether team's permanent Fire Resistance passive. Do not change it to a fire source.
- Weakness application is unchanged.

An unfueled furnace now kills a full-health Nether player in 20 seconds, and it does so to the whole team at once.

**Also fix the latent `onLit()` bug.** `FurnaceLifeline.onLit()` resets `lastFurnaceDamageTick` but has no callers, so after a relight the next outage begins damaging immediately rather than after a full interval. Wire it to fire when the furnace transitions from unlit to lit. At a 2-second interval the practical difference is small, but the method is dead code that documents an intent the code does not honor.

## 2. Quota payoff

In `lifeline/PlainsQuota.onPhaseChange`, on the quota-met branch, every non-spectator Plains player receives:

```java
new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 18000, 0, true, false, true)
```

The constant is `MobEffects.DAMAGE_RESISTANCE` — this branch targets Minecraft 1.21.1 (see gradle.properties), where the constant still carries the older name. It was renamed to `RESISTANCE` in later versions.

18000 ticks is exactly `Timeline.PHASE_TICKS`, so the buff expires precisely as the next quota check arrives. Reapplied fresh each time quota is met; a team that keeps paying keeps the buff continuously.

The existing "quota met" chat message stays, extended to mention the buff.

## 3. Mid-match class change

New `/nations class`, registered in `command/NationsCommand.java` alongside the existing `join` and `leave`. That command root is deliberately ungated (no `requires()`), which is required here — this is a player feature, and everything under `/regions` is operator-only.

Preconditions, each with its own failure message:

- a match is active
- the player has a `MatchPlayerState` and a team
- the player is not a spectator
- the player is standing inside **their own** team's `biomeBounds`

On success, open the existing class picker for the player's team.

### Kit handling on switch — the part that needs care

`KitGrant.grant()` is a full reset: it clears the entire inventory, clears every armor slot, and clears all per-class ability state. Reusing it for a class switch would delete every diamond and enchanted tool the player had earned, and nobody would ever use the feature. Simply not clearing turns class switching into an infinite kit farm.

Add `KitGrant.switchClass(ServerPlayer player, BiomeClass from, BiomeClass to)`:

1. **Strip only the previous class's *named* kit items.** Enumerate `from`'s kit, collect the custom names of stacks that carry one, and remove matching inventory stacks via `ItemTags.hasName(stack, name)`.
2. **Clear per-class ability state** — the same `clearForPlayer(uuid)` calls `KitGrant.grant` already makes, plus `Cooldowns.get().clearForPlayer(uuid)`. This is mandatory: without it a Warrior's in-flight Frenzy would persist onto a Wizard.
3. **Grant the new kit** without wiping the inventory, copying stacks the same way `grant` does (kit stacks in `ClassKits` are shared templates and must be copied).

Unnamed kit items — generic wooden tools, plain leather armor — are deliberately **not** stripped. They are fungible and low value, and accumulating a spare wooden pickaxe is not a balance problem. The exploit that matters is holding several classes' *ability* items at once (wand plus Frenzy plus Tidebringer), and every one of those carries a custom name.

`ClassKits.kit(BiomeClass)` already exists and is public — no accessor needs adding.

`KitGrant.grant()` is unchanged and still used for class pick, respawn, and the debug command.

**No cooldown.** Having to walk home to your own biome is the cost, and stripping the previous ability items removes the incentive to swap opportunistically. Revisit if playtesting shows counter-picking is a problem.

## 4. Territorial PvP

Layered on top of the existing phase rules, not replacing them. Cold seasons still open global PvP for everyone everywhere.

All PvP damage already flows through one chokepoint: `PvpDamageHook` resolves the human attacker and calls `PvpManager.isPvpAllowedFor(attacker, target)`. The territorial rules go there and nowhere else.

### Resolution order for "may A hit B?"

1. A is B (self-damage) → **allow**. Existing.
2. Either party is inside `chamberBounds` → **allow**. Existing.
3. `PvpManager.isPvpAllowed()` is true — cold season, permanent PvP past 60:00, or an operator override → **allow**. Existing.
4. **Territorial.** Let `R` be the region containing **B's position**, resolved by testing each team's `RegionsConfig.biomeBounds(team)` with `Area.contains(x, y, z)`. If `R` exists and A's team owns `R` → **allow**.
5. **Retaliation.** If A holds a live retaliation flag against B specifically → **allow**.
6. Otherwise → **deny**.

Rule 4 keys on the **victim's** position, not the attacker's. That is deliberate: it lets a defender standing just outside their own border shoot inward, and it correctly denies an intruder who attacks a defender while both stand on the defender's ground.

**Home-field advantage is a warm-phase phenomenon.** Rule 3 sits above rule 4, so once a cold season opens global PvP, everyone can hit everyone everywhere and the territorial asymmetry stops applying. This follows directly from layering rather than replacing, and it is intended: cold seasons remain the open-war window, while territory governs the peace. If home advantage should persist through cold seasons, rules 3 and 4 would need to swap order — that is a different design and is not what this spec builds.

### Retaliation

New `pvp/Retaliation.java`, in-memory, reset alongside the other per-match singletons.

State is a per-pair expiry: `Map<UUID victim, Map<UUID attacker, Long expiryTick>>`. Whenever rule 4 permits a hit, record that B may strike A for the next **300 ticks (15 seconds)**, refreshed on every subsequent permitted hit.

The flag is directional and pair-scoped. An intruder struck by one defender may return fire against **that defender only** — not the defender's teammates. A single defender opening fire must not cancel home-field advantage for their whole team.

Expired entries are pruned lazily on lookup; no tick loop is required.

### Consequences that fall out of these rules

Two intruders from different foreign teams standing in a third team's region cannot fight each other: neither owns the ground, so neither passes rule 4, and no retaliation flag exists. Only the owning team may open fire.

Neutral land with no registered region grants nothing under rule 4, so it stays safe during warm phases.

Rift Walker's alpha strike is neutralised as intended — a squad inserted during peace can be shot on arrival and cannot shoot back until fired upon, and then only at whoever fired.

**Mountain's Blood Tribute becomes substantially easier**, because Mountain may now legally kill intruders during warm phases rather than only during the 15 minutes of cold. This is a real buff that falls out of the PvP change rather than being chosen. It is accepted: it partly defuses the anti-comeback loop where a losing Mountain team fails tribute precisely because it cannot win fights.

## Files

| File | Change |
| --- | --- |
| `lifeline/FurnaceLifeline.java` | interval 600 → 40, delete floor and clamp, wire `onLit()` |
| `lifeline/PlainsQuota.java` | grant `MobEffects.DAMAGE_RESISTANCE` for 18000 ticks on quota met |
| `command/NationsCommand.java` | add `class` subcommand |
| `classes/KitGrant.java` | add `switchClass(player, from, to)` |
| `classes/ClassKits.java` | add public kit accessor |
| `pvp/PvpManager.java` | territorial and retaliation rules in `isPvpAllowedFor` |
| `pvp/Retaliation.java` | new |
| `match/MatchManager.java` | reset `Retaliation` in the three existing reset paths |

## Verification

The pure logic worth unit-testing is the retaliation window (set, still-live, expired, pair isolation) and region ownership resolution. Both can be tested without a server if the retaliation store is kept free of Minecraft types.

Everything else is in-game:

1. Let a Nether furnace burn out; confirm the team dies in roughly 20 seconds rather than stalling at half a heart.
2. Meet quota; confirm Resistance I appears and lasts a full phase.
3. Stand in your own biome, run `/nations class`, switch; confirm earned gear survives and the previous class's ability items are gone.
4. During a **warm** phase, walk into an enemy region. Confirm you cannot hit the owner, the owner can hit you, and after they hit you, you can hit them back — but not their teammate.
5. Confirm the retaliation right expires about 15 seconds after their last hit.
6. Confirm two players from different foreign teams cannot fight each other inside a third team's region.
