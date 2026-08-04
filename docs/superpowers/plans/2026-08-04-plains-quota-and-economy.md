# Plains Quota and Trader Economy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Plains a recurring emerald quota lifeline and make emeralds circulate through server-enforced trader offers.

**Architecture:** Pure arithmetic is isolated in `QuotaMath` so it can be unit-tested without a running server. Quota state lives on the existing `LifelineState` singleton and is evaluated on the WARM→COLD phase edge, reusing the hook `BloodTributeLifeline` already uses. Trades are delivered by a mod-supplied `Merchant` implementation opened on entity right-click, not by attaching villager offers, because Piglins cannot hold them.

**Tech Stack:** Java 21, Fabric Loader 0.19.2, Fabric API 0.141.3+1.21.11, Minecraft 1.21.11 with official Mojang mappings, Gradle with fabric-loom, JUnit 5 (added by Task 1).

**Spec:** `docs/superpowers/specs/2026-08-04-plains-quota-and-lifeline-bars-design.md`

## Global Constraints

- Server-side only. No client mod. Never import `net.minecraft.client.*`, never register a new block/item/entity/registry entry, never send a custom payload packet. Everything must be expressible in vanilla protocol.
- Minecraft 1.21.11, Java 21, official Mojang mappings.
- `quotaBase` default is **24**. Quota formula is `quotaBase × plainsMembers × k`, `k` starting at 1.
- Composter conversion stays **1 crop → 1 emerald**. Trader buy rates are approximately **10:1**.
- No-arbitrage invariant: no trader may buy and sell the same item, and any two-offer round trip must lose at least half its value. The value test is the operative rule. Smelt-linked pairs are allowed where the round trip is checked and loss-making — Mountain's raw iron buy against its iron ingot sell is the one live case, at an 87% loss.
- Quota failure penalty is **1 life per non-spectator Plains player**, matching `BloodTributeLifeline.java:98`.
- Follow existing code style: `private` constructors on utility classes, singletons via `get()`, chat via `p.sendSystemMessage(Component.literal(msg).withStyle(color))`.

---

### Task 1: Test infrastructure

The repo currently has no test source set at all. This task adds one and proves it runs.

**Files:**
- Modify: `build.gradle:10-16` (repositories), `build.gradle:30-39` (dependencies), and append a `test` task block
- Create: `src/test/java/com/regionsmoba/economy/QuotaMathTest.java`
- Create: `src/main/java/com/regionsmoba/economy/QuotaMath.java`

**Interfaces:**
- Consumes: nothing
- Produces: `com.regionsmoba.economy.QuotaMath` with `public static int quotaFor(int quotaBase, int plainsMembers, int season)` and `public static int depositAmount(int held, int paid, int quota)`; a working `./gradlew test` task

- [ ] **Step 1: Add mavenCentral and JUnit to build.gradle**

In `repositories`, add the line `mavenCentral()`.

In `dependencies`, add:

```groovy
	testImplementation 'org.junit.jupiter:junit-jupiter:5.11.3'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

Append at the end of the file:

```groovy
test {
	useJUnitPlatform()
	testLogging {
		events "passed", "skipped", "failed"
	}
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/regionsmoba/economy/QuotaMathTest.java`:

```java
package com.regionsmoba.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotaMathTest {

    @Test
    void quotaScalesWithMembersAndSeason() {
        assertEquals(72, QuotaMath.quotaFor(24, 3, 1));
        assertEquals(144, QuotaMath.quotaFor(24, 3, 2));
        assertEquals(216, QuotaMath.quotaFor(24, 3, 3));
    }

    @Test
    void quotaIsZeroWhenPlainsHasNoLivingMembers() {
        assertEquals(0, QuotaMath.quotaFor(24, 0, 1));
    }

    @Test
    void quotaIsZeroBeforeTheFirstColdSeason() {
        assertEquals(0, QuotaMath.quotaFor(24, 3, 0));
    }

    @Test
    void depositConsumesOnlyWhatTheQuotaStillNeeds() {
        assertEquals(64, QuotaMath.depositAmount(64, 0, 72));
        assertEquals(22, QuotaMath.depositAmount(64, 50, 72));
    }

    @Test
    void depositConsumesNothingOnceQuotaIsMet() {
        assertEquals(0, QuotaMath.depositAmount(64, 72, 72));
        assertEquals(0, QuotaMath.depositAmount(64, 99, 72));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test`
Expected: FAIL — compilation error, `package com.regionsmoba.economy does not exist`.

- [ ] **Step 4: Write minimal implementation**

Create `src/main/java/com/regionsmoba/economy/QuotaMath.java`:

```java
package com.regionsmoba.economy;

/**
 * Pure arithmetic for the Plains emerald quota. Kept free of Minecraft types so
 * it can be unit-tested without bootstrapping a server.
 */
public final class QuotaMath {

    private QuotaMath() {}

    /**
     * Quota for cold season {@code season} (1-based). Returns 0 when Plains has no
     * living members or the match has not reached its first cold season, which
     * callers treat as "skip the check".
     */
    public static int quotaFor(int quotaBase, int plainsMembers, int season) {
        if (plainsMembers <= 0 || season < 1) return 0;
        return quotaBase * plainsMembers * season;
    }

    /** Emeralds to consume from a held stack, never overshooting the quota. */
    public static int depositAmount(int held, int paid, int quota) {
        int needed = quota - paid;
        if (needed <= 0) return 0;
        return Math.min(held, needed);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test`
Expected: PASS — 5 tests.

- [ ] **Step 6: Verify the mod still builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add build.gradle src/test src/main/java/com/regionsmoba/economy/QuotaMath.java
git commit -m "test: add JUnit source set and quota arithmetic"
```

---

### Task 2: Config keys

**Files:**
- Modify: `src/main/java/com/regionsmoba/config/RegionsConfig.java` (field block at lines 33-54)

**Interfaces:**
- Consumes: nothing
- Produces: `RegionsConfig.get().plainsQuotaBase` (int, default 24) and `RegionsConfig.get().furnaceBarWindowTicks` (int, default 6000)

- [ ] **Step 1: Add the fields**

In the field block of `RegionsConfig.java`, alongside the existing registration fields, add:

```java
    /** Emeralds per Plains member for the first cold season; scales linearly per season. */
    public int plainsQuotaBase = 24;

    /** Ticks of furnace burn time that render as a full Nether boss bar. */
    public int furnaceBarWindowTicks = 6000;
```

Gson populates these from JSON when present and leaves the field initialiser when absent, so existing config files keep working with no migration step.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/regionsmoba/config/RegionsConfig.java
git commit -m "feat(config): add plainsQuotaBase and furnaceBarWindowTicks"
```

---

### Task 3: Quota state on LifelineState

**Files:**
- Modify: `src/main/java/com/regionsmoba/lifeline/LifelineState.java`

**Interfaces:**
- Consumes: nothing
- Produces: `LifelineState.get().plainsQuotaPaid` (int) and `LifelineState.get().plainsQuotaSeason` (int), both reset by the existing `LifelineState` reset path

- [ ] **Step 1: Add the fields**

Add to `LifelineState`, next to the existing conduit and blood-tribute fields:

```java
    /** Emeralds deposited into the composter toward the current phase's quota. */
    public int plainsQuotaPaid = 0;

    /** Cold-season counter. Starts at 0; incremented before each quota check. */
    public int plainsQuotaSeason = 0;
```

- [ ] **Step 2: Reset them wherever the class already resets state**

Find the existing reset method in `LifelineState` (the one `MatchManager` calls on match start and abort) and add:

```java
        plainsQuotaPaid = 0;
        plainsQuotaSeason = 0;
```

If `LifelineState` has no reset method and `MatchManager` instead replaces the singleton, no change is needed here — confirm which pattern is used by reading `MatchManager.java:166` and the surrounding reset block before editing.

- [ ] **Step 3: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/lifeline/LifelineState.java
git commit -m "feat(lifeline): add Plains quota state fields"
```

---

### Task 4: Quota payment at the composter

**Files:**
- Modify: `src/main/java/com/regionsmoba/lifeline/ComposterLifeline.java` (the `onUseBlock` handler, around lines 74-93)

**Interfaces:**
- Consumes: `QuotaMath.depositAmount`, `QuotaMath.quotaFor`, `LifelineState.plainsQuotaPaid`, `LifelineState.plainsQuotaSeason`, `RegionsConfig.plainsQuotaBase`
- Produces: `PlainsQuota.currentQuota()` and `PlainsQuota.pay(ServerPlayer, int)` — see Step 2

- [ ] **Step 1: Create the quota helper**

Create `src/main/java/com/regionsmoba/lifeline/PlainsQuota.java`:

```java
package com.regionsmoba.lifeline;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.economy.QuotaMath;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;

import java.util.UUID;

/**
 * Plains lifeline: an emerald quota that must be paid into the composter before
 * each cold season begins. Failure costs every living Plains player one life,
 * mirroring the Mountain Blood Tribute penalty.
 */
public final class PlainsQuota {

    private PlainsQuota() {}

    /** Non-spectator Plains players currently in the match. */
    public static int livingMembers() {
        int n = 0;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s != null && s.team == BiomeTeam.PLAINS && !s.spectator) n++;
        }
        return n;
    }

    /**
     * Quota for the season currently being worked toward. Display uses this live
     * projection; the authoritative value is recomputed at the check.
     */
    public static int currentQuota() {
        LifelineState ls = LifelineState.get();
        return QuotaMath.quotaFor(
                RegionsConfig.get().plainsQuotaBase,
                livingMembers(),
                ls.plainsQuotaSeason + 1);
    }

    public static int paid() {
        return LifelineState.get().plainsQuotaPaid;
    }

    /** Records a payment. Returns emeralds actually consumed. */
    public static int pay(int held) {
        LifelineState ls = LifelineState.get();
        int take = QuotaMath.depositAmount(held, ls.plainsQuotaPaid, currentQuota());
        ls.plainsQuotaPaid += take;
        return take;
    }
}
```

- [ ] **Step 2: Add the emerald branch to the composter handler**

In `ComposterLifeline.onUseBlock`, the existing flow rejects non-Plains players, then checks whether the held item is a crop. Insert an emerald branch **before** the crop check, immediately after the Plains team check that ends at line 79.

```java
        ItemStack held = sp.getMainHandItem();

        if (held.is(Items.EMERALD)) {
            int want = sp.isShiftKeyDown() ? 1 : held.getCount();
            int quota = PlainsQuota.currentQuota();
            if (quota <= 0) {
                tell(sp, "No quota is active right now.", ChatFormatting.GRAY);
                return InteractionResult.SUCCESS;
            }
            int taken = PlainsQuota.pay(want);
            if (taken == 0) {
                tell(sp, "Quota already met — " + PlainsQuota.paid() + " / " + quota + ".",
                        ChatFormatting.GREEN);
                return InteractionResult.SUCCESS;
            }
            held.shrink(taken);
            tell(sp, "Paid " + taken + " emeralds — " + PlainsQuota.paid() + " / " + quota + ".",
                    ChatFormatting.GREEN);
            return InteractionResult.SUCCESS;
        }
```

The existing `ItemStack held = sp.getMainHandItem();` line further down (line 81) becomes a duplicate declaration — delete the later one and let the crop check use the variable declared here.

Add imports for `com.regionsmoba.lifeline.PlainsQuota` is unnecessary (same package); ensure `net.minecraft.world.item.Items` is already imported (it is, at the existing emerald grant).

- [ ] **Step 3: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/regionsmoba/lifeline/PlainsQuota.java src/main/java/com/regionsmoba/lifeline/ComposterLifeline.java
git commit -m "feat(lifeline): pay Plains quota by right-clicking composter with emeralds"
```

---

### Task 5: Quota check on the cold-season edge

**Files:**
- Modify: `src/main/java/com/regionsmoba/lifeline/PlainsQuota.java`
- Modify: `src/main/java/com/regionsmoba/RegionsMOBA.java:67` (the phase-change block)

**Interfaces:**
- Consumes: `Timeline.PhaseChange`, `MatchPhase`, `Lives.loseLife`
- Produces: `PlainsQuota.onPhaseChange(MinecraftServer, MatchPhase from, MatchPhase to)`

- [ ] **Step 1: Add the check**

Append to `PlainsQuota`:

```java
    /**
     * Evaluates the quota on the WARM -> COLD edge, then resets the counter.
     * Short payment costs every non-spectator Plains player one life.
     */
    public static void onPhaseChange(MinecraftServer server, MatchPhase from, MatchPhase to) {
        if (to != MatchPhase.COLD) return;
        LifelineState ls = LifelineState.get();
        ls.plainsQuotaSeason++;

        int quota = QuotaMath.quotaFor(
                RegionsConfig.get().plainsQuotaBase, livingMembers(), ls.plainsQuotaSeason);
        int paid = ls.plainsQuotaPaid;
        ls.plainsQuotaPaid = 0;

        if (quota <= 0) return;

        boolean met = paid >= quota;
        for (UUID id : MatchManager.get().matchPlayers()) {
            MatchPlayerState s = TeamAssignments.get().state(id);
            if (s == null || s.team != BiomeTeam.PLAINS || s.spectator) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (met) {
                p.sendSystemMessage(Component.literal(
                                "Emerald Quota met — " + paid + " / " + quota + ".")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                Lives.loseLife(p, "Emerald Quota unmet");
            }
        }
    }
```

Add imports: `net.minecraft.ChatFormatting`, `net.minecraft.network.chat.Component`, `net.minecraft.server.MinecraftServer`, `net.minecraft.server.level.ServerPlayer`, `com.regionsmoba.timeline.MatchPhase`.

- [ ] **Step 2: Wire it into the tick loop**

In `RegionsMOBA.java`, the existing block calls `BloodTributeLifeline.onPhaseChange` when `Timeline.tick()` returns a non-null `PhaseChange`. Add directly beneath that call, inside the same `if`:

```java
                PlainsQuota.onPhaseChange(server, change.from(), change.to());
```

Match the local variable name already used for the `PhaseChange` record at that site.

- [ ] **Step 3: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Start a dev server, register a map, start a match, put a player on Plains, then run `/regions debug season cold`. Expected: the player loses a life and sees "You lost a life: Emerald Quota unmet". Pay emeralds at the composter first and repeat — expected: "Emerald Quota met".

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/regionsmoba/lifeline/PlainsQuota.java src/main/java/com/regionsmoba/RegionsMOBA.java
git commit -m "feat(lifeline): evaluate Plains quota at each cold season start"
```

---

### Task 6: Trade tables

**Files:**
- Create: `src/main/java/com/regionsmoba/economy/TradeTables.java`

**Interfaces:**
- Consumes: `BiomeTeam`
- Produces: `TradeTables.offersFor(BiomeTeam team)` returning a fresh `MerchantOffers`

- [ ] **Step 1: Write the tables**

Create `src/main/java/com/regionsmoba/economy/TradeTables.java`:

```java
package com.regionsmoba.economy;

import com.regionsmoba.team.BiomeTeam;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ItemLike;

/**
 * Per-team trader offers.
 *
 * Buy offers convert a region's raw surplus into emeralds at roughly 10:1, an
 * order of magnitude worse than the Plains composter's 1 crop -> 1 emerald. That
 * gap is what preserves the Plains monopoly economically rather than absolutely.
 *
 * No-arbitrage invariant: no table may buy and sell the same item or its direct
 * smelting product, and any two-offer round trip must lose at least half its
 * value. Re-verify by hand whenever a row changes.
 */
public final class TradeTables {

    /** High enough that no offer locks out within a match. */
    private static final int MAX_USES = 9999;

    private TradeTables() {}

    public static MerchantOffers offersFor(BiomeTeam team) {
        MerchantOffers offers = new MerchantOffers();
        switch (team) {
            case PLAINS -> {
                sell(offers, 1, Items.BREAD, 6);
                sell(offers, 1, Items.BAKED_POTATO, 8);
                sell(offers, 2, Items.HAY_BLOCK, 1);
                sell(offers, 3, Items.IRON_HOE, 1);
            }
            case MOUNTAIN -> {
                buy(offers, Items.COBBLESTONE, 16, 1);
                buy(offers, Items.RAW_IRON, 10, 1);
                buy(offers, Items.COAL, 16, 1);
                sell(offers, 4, Items.IRON_INGOT, 5);
                sell(offers, 12, Items.DIAMOND, 1);
                sell(offers, 3, Items.IRON_PICKAXE, 1);
            }
            case OCEAN -> {
                buy(offers, Items.COD, 12, 1);
                buy(offers, Items.PRISMARINE_SHARD, 8, 1);
                buy(offers, Items.KELP, 16, 1);
                sell(offers, 2, Items.PRISMARINE_BRICKS, 8);
                sell(offers, 3, Items.TURTLE_HELMET, 1);
                sell(offers, 5, Items.HEART_OF_THE_SEA, 1);
            }
            case NETHER -> {
                buy(offers, Items.QUARTZ, 6, 1);
                buy(offers, Items.BLAZE_ROD, 4, 1);
                buy(offers, Items.MAGMA_CREAM, 8, 1);
                sell(offers, 3, Items.OBSIDIAN, 8);
                sell(offers, 6, Items.NETHERITE_SCRAP, 1);
                sell(offers, 4, Items.FIRE_CHARGE, 8);
            }
        }
        return offers;
    }

    /** costCount of item -> emeraldsOut emeralds. */
    private static void buy(MerchantOffers offers, ItemLike item, int costCount, int emeraldsOut) {
        offers.add(new MerchantOffer(
                new ItemCost(item, costCount),
                new ItemStack(Items.EMERALD, emeraldsOut),
                MAX_USES, 0, 0.0f));
    }

    /** emeraldsIn emeralds -> resultCount of item. */
    private static void sell(MerchantOffers offers, int emeraldsIn, ItemLike item, int resultCount) {
        offers.add(new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldsIn),
                new ItemStack(item, resultCount),
                MAX_USES, 0, 0.0f));
    }
}
```

Note the Ocean and Nether sell rows differ from the spec's illustrative examples: enchanted-book offers need `EnchantmentInstance` plumbing that is not worth the complexity here, so they are replaced with plain items at equivalent price points. Update the spec's tables to match this file after the task lands.

- [ ] **Step 2: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/regionsmoba/economy/TradeTables.java
git commit -m "feat(economy): add per-team trader offer tables"
```

---

### Task 7: Merchant implementation and trade screen

**Files:**
- Create: `src/main/java/com/regionsmoba/economy/RegionsMerchant.java`
- Create: `src/main/java/com/regionsmoba/economy/TraderInteraction.java`
- Modify: `src/main/java/com/regionsmoba/RegionsMOBA.java` (add registration alongside the other `register()` calls around line 50)

**Interfaces:**
- Consumes: `TradeTables.offersFor`, `RegionsConfig.get().traders`
- Produces: `TraderInteraction.register()`

- [ ] **Step 1: Write the merchant**

Create `src/main/java/com/regionsmoba/economy/RegionsMerchant.java`:

```java
package com.regionsmoba.economy;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * A fixed-offer Merchant owned by the mod rather than by an entity.
 *
 * Piglin does not implement Merchant and there is no SimpleMerchant in 1.21.11,
 * so trades cannot be attached to the Nether trader as villager offers. Supplying
 * our own Merchant and opening a MerchantMenu against it works for any entity
 * type and gives all four traders identical behavior.
 */
public final class RegionsMerchant implements Merchant {

    private final MerchantOffers offers;
    private Player tradingPlayer;

    public RegionsMerchant(MerchantOffers offers) {
        this.offers = offers;
    }

    @Override
    public void setTradingPlayer(Player player) {
        this.tradingPlayer = player;
    }

    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers newOffers) {
        // Fixed tables; nothing may override them at runtime.
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
```

- [ ] **Step 2: Write the interaction handler**

Create `src/main/java/com/regionsmoba/economy/TraderInteraction.java`:

```java
package com.regionsmoba.economy;

import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.config.TraderRef;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeTeam;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.Map;

/** Opens a team's trade screen when its registered trader entity is right-clicked. */
public final class TraderInteraction {

    private TraderInteraction() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            if (!MatchManager.get().isActive()) return InteractionResult.PASS;

            BiomeTeam team = teamOfTrader(entity.getUUID());
            if (team == null) return InteractionResult.PASS;

            Component title = Component.literal(team.displayName() + " Trader");
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new MerchantMenu(
                            id, inv, new RegionsMerchant(TradeTables.offersFor(team))),
                    title));
            return InteractionResult.SUCCESS;
        });
    }

    private static BiomeTeam teamOfTrader(java.util.UUID entityId) {
        for (Map.Entry<BiomeTeam, TraderRef> e : RegionsConfig.get().traders.entrySet()) {
            TraderRef ref = e.getValue();
            if (ref != null && entityId.equals(ref.uuid())) return e.getKey();
        }
        return null;
    }
}
```

Before implementing, read `src/main/java/com/regionsmoba/config/TraderRef.java` and the `traders` field declaration in `RegionsConfig.java` to confirm the map's key type and the accessor name for the stored UUID. Adjust `teamOfTrader` to match — the shape above assumes `Map<BiomeTeam, TraderRef>` with a `uuid()` accessor.

- [ ] **Step 3: Register it**

In `RegionsMOBA.onInitialize`, add alongside the other registration calls:

```java
        TraderInteraction.register();
```

- [ ] **Step 4: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual verification**

On a dev server: start a match, run `/regions spawntrader mountain`, then right-click the spawned villager. Expected: a trade screen listing three buy offers and three sell offers. Repeat with `/regions spawntrader nether` and confirm the **Piglin** opens the same screen — this is the case the whole approach exists for.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/regionsmoba/economy/RegionsMerchant.java src/main/java/com/regionsmoba/economy/TraderInteraction.java src/main/java/com/regionsmoba/RegionsMOBA.java
git commit -m "feat(economy): open per-team trade screens on trader right-click"
```

---

### Task 8: Debug commands

**Files:**
- Modify: `src/main/java/com/regionsmoba/command/sub/DebugCommands.java` — command tree around lines 186-191, and the `composter uses` handler at lines 605-615

**Interfaces:**
- Consumes: `PlainsQuota`
- Produces: `/regions debug quota set <amount>` and `/regions debug quota info`

- [ ] **Step 1: Replace the composter-uses branch in the command tree**

Remove the `debug composter uses <count>` literal chain and add in its place:

```java
                .then(Commands.literal("quota")
                        .then(Commands.literal("set")
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> quotaSet(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "amount")))))
                        .then(Commands.literal("info")
                                .executes(ctx -> quotaInfo(ctx.getSource()))))
```

- [ ] **Step 2: Replace the handler methods**

Delete the `composterUses` no-op handler and add:

```java
    private static int quotaSet(CommandSourceStack src, int amount) {
        LifelineState.get().plainsQuotaPaid = amount;
        CommandHelpers.feedback(src, "Plains quota paid set to " + amount + ".");
        return 1;
    }

    private static int quotaInfo(CommandSourceStack src) {
        CommandHelpers.feedback(src, "Plains quota: " + PlainsQuota.paid()
                + " / " + PlainsQuota.currentQuota()
                + "  (season " + (LifelineState.get().plainsQuotaSeason + 1)
                + ", members " + PlainsQuota.livingMembers() + ")");
        return 1;
    }
```

Match the exact feedback helper name used by neighbouring handlers in this file — read one existing handler before writing these and copy its signature.

- [ ] **Step 3: Verify it builds**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual verification**

Run `/regions debug quota info` on a live match. Expected: a line showing paid, required, season, and member count. Then `/regions debug quota set 50` and re-run info to confirm the value changed. Confirm `/regions debug composter uses` no longer exists.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/regionsmoba/command/sub/DebugCommands.java
git commit -m "feat(debug): replace composter-uses stub with quota commands"
```

---

### Task 9: Documentation

**Files:**
- Modify: `docs/src-md/gameplay/economy-and-trade.md`
- Modify: `docs/src-md/teams/plains.md`
- Modify: `docs/src-md/teams/index.md`
- Modify: `docs/src-md/index.md`
- Modify: `docs/src-md/reference/commands.md`

- [ ] **Step 1: Rewrite the emerald and trade sections**

In `economy-and-trade.md`, replace the "Emeralds" section's claim that no other team can generate emeralds with the three taps: the composter at 1 crop → 1 emerald, each biome trader buying regional surplus at roughly 10:1, and the Trial Chamber's 16-emerald wave drop. Add the four trade tables exactly as implemented in `TradeTables.java` — read that file and transcribe, do not re-derive. Add a "Quota" section describing payment, the formula, the ramp, and the one-life penalty.

- [ ] **Step 2: Update the Plains pages**

In `teams/plains.md`, replace "None structural" under Lifeline with the emerald quota and its penalty. In `teams/index.md`, change the Plains row's Lifeline cell from "None structural. Emerald monopoly." to name the quota. In `index.md`, update the Plains entry in the Teams table to match.

- [ ] **Step 3: Update the command reference**

In `reference/commands.md`, remove `/regions debug composter uses <count>` and add `/regions debug quota set <amount>` and `/regions debug quota info`.

- [ ] **Step 4: Verify the site builds**

Run: `mkdocs build --strict` if mkdocs is installed locally; otherwise confirm no nav entries were added or removed, since `mkdocs.yml` lists pages explicitly and this task adds none.

- [ ] **Step 5: Commit**

```bash
git add docs/src-md
git commit -m "docs: document emerald quota and trader tables"
```

---

## Follow-on

Boss bars are specified in the same design document but planned separately in `docs/superpowers/plans/2026-08-04-lifeline-boss-bars.md`. The Plains quota bar depends on `PlainsQuota.paid()` and `PlainsQuota.currentQuota()` from Task 4 of this plan, so run this plan first.
