# Plains Emerald Quota and Lifeline Boss Bars — Design

Date: 2026-08-04
Status: Approved for planning

## Problem

Two gaps, coupled tightly enough to specify together.

Plains has no lifeline. `docs/src-md/teams/plains.md` states "None structural" and describes its defense as purely economic. Every other team carries a recurring survival obligation — Ocean defends a 200 HP conduit, Nether keeps a furnace lit, Mountain pays a Blood Tribute each cold season. Plains has nothing to fail at, so it has no pressure curve and no comeback tension.

The emerald economy does not circulate. `docs/src-md/gameplay/economy-and-trade.md` makes Plains the sole emerald source and expects other teams to obtain currency by trading directly with Plains players. Minecraft has no enforced player-to-player trade UI, so this is drop-items-and-trust, and a Plains team that simply refuses to trade permanently starves every other team's trader. Separately, no trade offers exist anywhere in the codebase, so all four traders are currently decorative.

Neither team's lifeline state is visible in-game. Conduit HP, furnace fuel, and Blood Tribute progress are all readable only via `/regions debug state`.

## Goals

Give Plains a recurring, escalating obligation that fits its economic identity. Make emeralds circulate through mechanisms the server can enforce. Surface every team's lifeline state and the match clock on the HUD, without requiring a client mod.

## Non-goals

Class kits and abilities remain out of scope; they are tracked separately. Trial Chamber loot composition is unchanged apart from the note in "Resolved contradictions" below. Win conditions remain undefined and are not addressed here.

---

## Part 1 — Economy

### Emerald sources

Emeralds enter the world through three taps.

The Plains composter converts crops at **1 crop → 1 emerald**. This is existing behavior in `ComposterLifeline.java:87-91` and is unchanged.

Each biome trader buys that region's raw surplus at roughly **10:1**. This is new. It gives every team a self-serve trickle of currency that does not depend on Plains cooperating.

The Trial Chamber drops 16 emeralds to the winning team of each wave. This already exists at `TrialChamber.java:57` and is retained.

The 10:1 versus 1:1 gap is the load-bearing number. Plains keeps its monopoly economically rather than absolutely: Mountain *can* mint emeralds, just an order of magnitude less efficiently than Plains can. Anchored on vanilla villager rates, where a farmer pays 1 emerald for 20 wheat.

### Trade tables

Offers are defined as per-team constants in code. Each trader both buys its region's raw goods and sells refined goods.

**Plains — Villager.** Sells food and farming goods; this is where other teams spend their trickle, since Plains owns the food supply.

| Direction | Offer |
| --- | --- |
| Sell | 1 emerald → 6 bread |
| Sell | 1 emerald → 8 baked potato |
| Sell | 2 emeralds → 1 hay bale |
| Sell | 3 emeralds → 1 iron hoe |

**Mountain — Villager.**

| Direction | Offer |
| --- | --- |
| Buy | 16 cobblestone → 1 emerald |
| Buy | 10 raw iron → 1 emerald |
| Buy | 16 coal → 1 emerald |
| Sell | 4 emeralds → 5 iron ingots |
| Sell | 12 emeralds → 1 diamond |
| Sell | 3 emeralds → 1 iron pickaxe |

**Ocean — Villager.**

| Direction | Offer |
| --- | --- |
| Buy | 12 cod → 1 emerald |
| Buy | 8 prismarine shard → 1 emerald |
| Buy | 16 kelp → 1 emerald |
| Sell | 2 emeralds → 8 prismarine bricks |
| Sell | 3 emeralds → 1 Aqua Affinity book |
| Sell | 5 emeralds → 1 Depth Strider I book |

**Nether — Piglin.**

| Direction | Offer |
| --- | --- |
| Buy | 6 quartz → 1 emerald |
| Buy | 4 blaze rods → 1 emerald |
| Buy | 8 magma cream → 1 emerald |
| Sell | 3 emeralds → 8 obsidian |
| Sell | 6 emeralds → 1 netherite scrap |
| Sell | 4 emeralds → 1 fire resistance potion |

**No-arbitrage invariant.** No trader may buy and sell the same item, and **any round trip through two offers must lose at least half its value**. The value test is the operative rule; verify it whenever a table is edited.

Items linked by smelting are permitted where the round trip is checked and loss-making. Mountain is the live case: it buys raw iron at 10 → 1 emerald and sells 5 iron ingots for 4 emeralds, so 40 raw iron round-trips to 5 ingots, an 87% loss. The reverse leg does not exist, since no trader buys iron ingots and ingots do not smelt back. Recheck this specific pair if either row changes.

Offers are set to effectively unlimited uses (`maxUses` high enough not to bind within a match). Traders remain protected and unkillable per `docs/src-md/reference/protected-areas.md`.

### Trade delivery mechanism

Offers are **not** attached to the entity as villager trades. Verified against the 1.21.11 mapped jar: `Piglin` implements `CrossbowAttackMob, InventoryCarrier` and does **not** implement `Merchant`, so the Nether Piglin cannot hold villager-style offers. Vanilla piglin bartering is a separate loot-table system and is not usable here. There is no `SimpleMerchant` class in this version.

Instead, the mod supplies its own `Merchant` implementation and opens a trade screen directly:

- A `RegionsMerchant implements net.minecraft.world.item.trading.Merchant` holds a fixed `MerchantOffers` for one team.
- Right-clicking a registered trader entity opens `new MerchantMenu(containerId, playerInventory, merchant)` through `player.openMenu(...)`.

This works for any entity type, so the Piglin is retained as documented, and it sidesteps villager professions, levelling, and restock behavior entirely. All four traders behave identically. The merchant screen is vanilla protocol, so vanilla clients are unaffected.

Offers are built with `MerchantOffer(ItemCost costA, ItemStack result, int maxUses, int xp, float priceMultiplier)`, where `ItemCost(ItemLike item, int count)` expresses the buy side.

### Resulting loop

Each team sells regional surplus for a trickle. Every team needs food, and Plains owns food. Teams pay Plains emeralds for food. Plains burns emeralds on quota. Plains buys iron, fire gear, and aquatic goods from the other three traders to equip players who start with wooden tools.

Plains' farm output splits between quota (survival) and purchasing (power). That split is the intended strategic squeeze.

---

## Part 2 — Plains quota lifeline

### Payment

Right-clicking the registered composter while holding emeralds deposits from the held stack toward the current phase's quota. Sneak-right-click deposits exactly one, for fine control near the threshold.

Only as many emeralds as the quota still needs are consumed; any remainder stays in the player's hand rather than being burnt. If the quota is already met, the click is rejected with a message and consumes nothing. Overpayment beyond the quota is therefore impossible.

This reuses the existing `UseBlockCallback` handler in `ComposterLifeline.java:56` with no new block and no ambiguity: a crop in hand converts to emeralds, an emerald in hand pays quota. Non-Plains players are rejected by the existing team check at `ComposterLifeline.java:75-79`.

Deposited emeralds are consumed. This is the sink that stops emeralds accumulating without bound.

### The check

The quota is evaluated at each **cold-season start**, on the WARM→COLD phase edge — the same hook `BloodTributeLifeline.onPhaseChange` already uses, wired from `RegionsMOBA.java:67`.

```text
quota(k) = quotaBase × plainsMembers × k
```

`k` is the cold-season number starting at 1. `plainsQuotaSeason` is initialised to 0 at match start and incremented **before** the quota is computed, so the first check evaluates with `k = 1`.

`plainsMembers` is the count of non-spectator Plains players. The authoritative value is the count at the moment of the check; the boss bar displays a live projection using the current count, which can therefore shift during a phase if a Plains player is eliminated. The bar is an estimate, the check is the truth.

`quotaBase` defaults to **24** and is configurable.

With the default and 3 Plains members, quotas run 72, 144, 216, 288 at the 15:00, 45:00, 75:00 and 105:00 cold seasons.

`quotaBase = 24` is a reasoned starting point, not a validated one. Emerald throughput depends almost entirely on the map author's farm size and on whether anyone picked Farmer, whose instant-regrow passive bypasses the cold-season crop freeze. It is configurable precisely so it can be tuned against a real map after playtesting rather than by recompiling.

Scaling on team size is required, not cosmetic: Plains headcount is `total players / 4`, so a flat quota would be trivial in a 16-player lobby and unreachable in an 8-player one.

After the check the counter resets to zero regardless of outcome. Overpayment does not carry forward.

### Outcomes

If the paid total meets or exceeds the quota, the team is safe for that cold season and a confirmation broadcasts to Plains players.

If it falls short, **every non-spectator Plains player loses 1 life**, via the existing `Lives.loseLife(p, "Emerald Quota unmet")`. This deliberately matches Mountain's Blood Tribute penalty at `BloodTributeLifeline.java:98` so players learn one punishment rule rather than two.

If Plains has zero non-spectator members at the check, the check is skipped with no penalty, mirroring the `startingMembers > 0` guard in `PermanentLossTracker.java:77`.

### State

Quota state is in-memory on `LifelineState`, alongside the existing conduit and Blood Tribute fields, and is reset by `MatchManager` on match start and abort like every other lifeline singleton.

- `plainsQuotaPaid` (long) — emeralds deposited in the current phase
- `plainsQuotaSeason` (int) — the cold-season counter `k`, incremented at each check

---

## Part 3 — Lifeline boss bars

Boss bars are vanilla protocol (`ClientboundBossEventPacket`, the same mechanism behind `/bossbar` and the Wither), so this works on unmodified vanilla clients and preserves the mod's server-only property.

Verified against the 1.21.11 mapped jar: `net.minecraft.server.level.ServerBossEvent` provides `setProgress(float)`, `setName(Component)`, `setColor(BossBarColor)`, `setOverlay(BossBarOverlay)`, `addPlayer(ServerPlayer)`, `removePlayer(ServerPlayer)`, `removeAllPlayers()` and `setVisible(boolean)`. Colors available are PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE. Overlays are PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20.

### Visibility

Bars are team-scoped. A typical player carries two: the global timer plus their own team's lifeline. Mountain carries one in warm and two in cold, since its bar only exists while the tribute is active. Spectators receive the timer plus all four lifeline bars.

### The bars

**Game timer** — all match players and spectators.

Name renders as `Warm 1 — 12:34`, or `Cold 3 — 07:12  ·  PvP permanent` once past 60:00 so the permanent-PvP transition is visible rather than silent. Progress is time remaining in the phase over `Timeline.PHASE_TICKS` (18000), draining across the phase. Colour is YELLOW in warm and BLUE in cold, overlay PROGRESS.

**Ocean — Conduit.** Name `Conduit — 147 / 200`, progress `hp / LifelineState.CONDUIT_MAX_HP`, colour BLUE, overlay NOTCHED_20 so each notch is exactly 10 HP and chip damage stays legible. At 0 HP the bar turns RED and renames to `CONDUIT DESTROYED — next death is final`.

**Plains — Quota.** Name `Quota — 48 / 72 emeralds`, or `Quota met` once satisfied. Progress is `paid / quota`, clamped to 1.0. Colour GREEN, switching to RED if still short with under 60 seconds to the check. Overlay PROGRESS.

**Mountain — Blood Tribute.** Hidden during warm phases via `setVisible(false)`. On cold-season start it appears RED, named `Blood Tribute — 07:23 left`, with progress counting down over the cold phase. On satisfaction it snaps to full and GREEN, named `Blood Tribute paid`.

This has a useful side effect. `BloodTributeLifeline.satisfy()` at line 133 deliberately sends no broadcast, so a Mountain player currently has no way to know the tribute landed without running a debug command. The bar turning green becomes that confirmation, and no chat broadcast needs to be added.

**Nether — Furnace.** Name `Furnace — 4:12 remaining` while lit, with a `2x burn` suffix during cold seasons. Progress is remaining burn time normalised against a fixed window, `furnaceBarWindowTicks`, defaulting to **6000** (five minutes): the bar reads full whenever more than five minutes of fuel remain and drains across the final five.

This makes it an urgency gauge rather than a fuel gauge, which is the actionable reading, and it requires no stored state. The rejected alternative — normalising against the last observed peak so the bar always starts full on refuel — is more literally accurate per refuel but jumps confusingly when a partly-burnt furnace is topped up.

Colour is YELLOW while lit and RED when out, where the name becomes `FURNACE OUT — 1 heart / 30s`. During cold the bar visibly drains at double rate, making the 2× burn rule self-evident.

Burn time is read through the existing `FurnaceAccessor.regionsmoba$getLitTimeRemaining()` at `mixin/FurnaceAccessor.java:16`. No new mixin is required.

### Lifecycle

Bars are created on match start and receive players as teams are picked in `LobbyFlow.onTeamPicked` (`LobbyFlow.java:89`). Players are removed on spectator transition, on disconnect, and on match end via `removeAllPlayers()`.

**Respawn re-attachment is mandatory.** Respawning replaces the `ServerPlayer` instance, so a bar attached to the old object is silently lost. Bars must be re-added on `ServerPlayerEvents.AFTER_RESPAWN` alongside the existing `TeamPassives.apply` call at `DeathHandler.java:82`. Without this, every player loses their HUD the first time they die.

### Update cadence

Every boss bar setter sends a packet to every viewer. Updating four bars' names each tick for a full lobby is heavy traffic for a display whose smallest unit is one second.

Bars update from the existing `END_SERVER_TICK` chain in `RegionsMOBA.java:64-79` at a **10-tick cadence**, and only push `setName` or `setProgress` when the rendered string or the progress value has actually changed since the last push.

---

## Configuration

Two keys are added to `config/regionsmoba.json`, both with the defaults above:

- `plainsQuotaBase` (int, default 24)
- `furnaceBarWindowTicks` (int, default 6000)

Trade tables are code constants for this iteration. Making them config-driven is a reasonable later change but is not required to ship this.

## Commands

Two debug subcommands are added under the existing operator-gated `/regions debug` tree:

- `/regions debug quota <amount>` — set the current paid total
- `/regions debug quota info` — print paid, required, and the current season counter

`/regions debug composter uses <count>` is **replaced** by these. It is currently a no-op stub that warns and returns 1 (`DebugCommands.java:610-615`); the quota counter is the tracked value it was gesturing at.

## Documentation updates

- `gameplay/economy-and-trade.md` — rewrite the Emeralds section for the three taps; add the four trade tables; add the quota
- `teams/plains.md` — replace "Lifeline: None structural" with the quota
- `teams/index.md` — update the Plains row of the comparison table
- `index.md` — update the Plains one-liner and the Teams table
- `reference/commands.md` — add the two quota debug commands, remove `composter uses`
- New page under `reference/` documenting the boss bars and what each one shows

## Resolved contradictions

This design settles two conflicts found in the 2026-08-04 audit.

The docs state that no team but Plains can generate emeralds, while `TrialChamber.java:57` already drops 16 emeralds per wave win. Under the three-tap model this is intended rather than a violation, and the docs are corrected to match the code.

`teams/plains.md` describes Plains as having no lifeline while every other team has one. Plains now has one.

## Risks

The quota number is unvalidated and depends on map-specific farm throughput; this is why it is configurable. A Farmer on the Plains roster may roughly double output and skew the first playtest.

Cross-team trading still depends on players choosing to trade for food. If teams self-sufficiency-farm instead, the Plains trader carries less traffic than intended and the loop weakens. The trickle from each team's own trader is deliberately thin to discourage this.

Five boss bars for spectators is visually crowded. If it proves unreadable, reduce spectators to the timer plus a single rotating lifeline bar.
