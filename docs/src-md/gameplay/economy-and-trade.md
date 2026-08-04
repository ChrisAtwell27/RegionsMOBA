# Economy and Trade

## Emeralds

Emeralds come from three sources:

- **Plains composter.** Right-click the composter with a crop to convert 1 crop into 1 emerald. Plains-only.
- **Regional traders.** Every biome's trader buys that region's raw surplus for emeralds at roughly 10:1 — an order of magnitude worse than the composter.
- **Trial Chamber waves.** The winning team of each wave receives 16 emeralds, on top of other loot. See [Trial Chamber](trial-chamber.md).

The composter's 1:1 rate makes Plains the cheapest source of emeralds, not the only one.

## Traders

Each biome has one trader. Right-click to open a trade menu.

| Biome | Trader | Sells |
| --- | --- | --- |
| Plains | Villager | Crops, food, farming tools |
| Ocean | Villager | Aquatic goods, water-related items |
| Mountain | Villager | Ores, ingots, mining tools |
| Nether | Piglin | Nether materials, fire-resistant gear |

Traders are protected and cannot be killed.

Map authors register a biome's trader with either `/regions spawntrader <biome>` (one-step: spawns the right entity for the biome at the operator's position and registers it) or `/regions settrader <biome>` (click-register an entity already in the world — useful if you want a custom-named trader or one with pre-set trades).

### Plains trades

Plains has no buy offers. Its trader only sells — the composter is where Plains earns emeralds.

| Give | Get |
| --- | --- |
| 1 Emerald | 6 Bread |
| 1 Emerald | 8 Baked Potato |
| 2 Emeralds | 1 Hay Block |
| 3 Emeralds | 1 Iron Hoe |

### Mountain trades

| Give | Get |
| --- | --- |
| 16 Cobblestone | 1 Emerald |
| 10 Raw Iron | 1 Emerald |
| 16 Coal | 1 Emerald |
| 4 Emeralds | 5 Iron Ingot |
| 12 Emeralds | 1 Diamond |
| 3 Emeralds | 1 Iron Pickaxe |

### Ocean trades

| Give | Get |
| --- | --- |
| 12 Raw Cod | 1 Emerald |
| 8 Prismarine Shard | 1 Emerald |
| 16 Kelp | 1 Emerald |
| 2 Emeralds | 8 Prismarine Bricks |
| 3 Emeralds | 1 Turtle Helmet |
| 5 Emeralds | 1 Heart of the Sea |

### Nether trades

| Give | Get |
| --- | --- |
| 6 Nether Quartz | 1 Emerald |
| 4 Blaze Rod | 1 Emerald |
| 8 Magma Cream | 1 Emerald |
| 3 Emeralds | 8 Obsidian |
| 6 Emeralds | 1 Netherite Scrap |
| 4 Emeralds | 8 Fire Charge |

## Quota

Plains must pay an emerald quota into the composter before each cold season begins — this is the Plains lifeline. See [Plains](../teams/plains.md).

Pay by right-clicking the composter while holding emeralds:

- A normal right-click pays with your entire held stack, capped at whatever is still owed.
- A sneak right-click pays exactly 1 emerald.

It never takes more than what's owed — once the quota is met, further clicks leave your held emeralds untouched.

**Formula:** `plainsQuotaBase × living Plains members × season number`, season counted from 1 at the first cold season. `plainsQuotaBase` defaults to 24 and is tunable in `config/regionsmoba.json`. The season number climbs every cold season, so the quota ramps up over the course of a match even with a stable Plains population. If Plains has no living members when the check runs, the quota is skipped.

**Check:** Evaluated the instant the match turns cold. The paid counter resets to 0 immediately after, met or not — nothing carries over into the next season. Falling short costs every living (non-spectator) Plains player 1 life, the same penalty Mountain pays for a missed Blood Tribute.

## Trade flow

1. Plains converts crops to emeralds at the composter, 1:1, and pays its emerald quota there too.
2. Every other trader buys its region's raw surplus for emeralds, roughly 10:1.
3. Any player can spend emeralds at any biome's trader — not just their own team's.
4. Trial Chamber waves add emeralds straight to the winning team, no trader involved.
