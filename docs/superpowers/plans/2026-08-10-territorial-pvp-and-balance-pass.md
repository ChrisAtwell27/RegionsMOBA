# Territorial PvP and Balance Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every lifeline lethal, pay Plains for meeting quota, allow class changes at home, and make enemy territory dangerous to occupy during peace.

**Architecture:** Three of the four changes are small edits to existing files. The fourth adds a `Retaliation` store — deliberately free of Minecraft types so its window logic is unit-testable — consumed by new rules inside `PvpManager.isPvpAllowedFor`, which is already the single chokepoint every PvP damage path flows through.

**Tech Stack:** Java 21, Fabric Loader 0.19.3, Fabric API 0.116.15+1.21.1, Minecraft 1.21.1, official Mojang mappings, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-10-territorial-pvp-and-balance-pass-design.md`

## Global Constraints

- Server-side only. Never import `net.minecraft.client.*`. No registry entries, no custom payload packets.
- Java 21, Minecraft 1.21.1, official Mojang mappings.
- This branch targets **Minecraft 1.21.1**, not 1.21.11 — check `gradle.properties`. The Resistance constant is `MobEffects.DAMAGE_RESISTANCE`, and damage is `p.hurt(source, amount)` not `hurtServer`.
- `ClassKits.kit(BiomeClass)` already exists and is public. Do NOT add an accessor; the spec is wrong on that point.
- Furnace damage must stay on `damageSources().wither()` — it deliberately bypasses the Nether team's permanent Fire Resistance.
- Territorial PvP **layers on top of** the phase rules. Global PvP during cold seasons still wins, so home-field advantage is a warm-phase phenomenon.
- Retaliation is directional and pair-scoped: being hit by one defender grants return fire against **that defender only**, never their teammates.
- `./gradlew build` must pass before every commit.

---

### Task 1: Lethal furnace

**Files:**
- Modify: `src/main/java/com/regionsmoba/lifeline/FurnaceLifeline.java`

- [ ] **Step 1: Retune the constants**

Replace:

```java
    public static final int DAMAGE_INTERVAL_TICKS = 30 * 20;
    public static final float DAMAGE_AMOUNT = 2.0f;        // 1 heart
    public static final float DAMAGE_FLOOR_HP = 2.0f;       // never below 1 heart
```

with:

```java
    public static final int DAMAGE_INTERVAL_TICKS = 2 * 20;
    public static final float DAMAGE_AMOUNT = 2.0f;        // 1 heart
```

`DAMAGE_FLOOR_HP` is deleted entirely.

- [ ] **Step 2: Remove the floor clamp**

In `applyUnlitPenalty`, replace:

```java
            float headroom = p.getHealth() - DAMAGE_FLOOR_HP;
            if (headroom <= 0) continue;
            float dmg = Math.min(DAMAGE_AMOUNT, headroom);
            p.hurt(level.damageSources().wither(), dmg);
```

with:

```java
            p.hurt(level.damageSources().wither(), DAMAGE_AMOUNT);
```

An unfueled furnace now kills a full-health player in 20 seconds.

- [ ] **Step 3: Wire onLit()**

`onLit()` sets `lastFurnaceDamageTick = -1` but has no callers, so after a relight the next outage begins damaging immediately instead of after a full interval. In `tick()`, the `lit` branch currently reads:

```java
        if (lit) {
            applyColdBurnRate(level, pos);
        } else {
```

Add the reset:

```java
        if (lit) {
            onLit();
            applyColdBurnRate(level, pos);
        } else {
```

Calling it every tick while lit is intentional and simpler than tracking the unlit→lit transition: the field stays at `-1` for as long as the furnace burns, so `applyUnlitPenalty`'s existing `if (ls.lastFurnaceDamageTick < 0)` arm-and-return branch grants a full fresh interval the moment it goes out.

- [ ] **Step 4: Update the class javadoc**

The header comment says "Every 30s when unlit: applies Weakness + 1 heart damage" and "never below 1 heart". Both are now false. Rewrite that block to describe 1 heart every 2 seconds with no floor, and note that this is lethal.

- [ ] **Step 5: Build and commit**

```bash
./gradlew build
git add src/main/java/com/regionsmoba/lifeline/FurnaceLifeline.java
git commit -m "feat(lifeline): make the Nether furnace outage lethal"
```

---

### Task 2: Quota payoff

**Files:**
- Modify: `src/main/java/com/regionsmoba/lifeline/PlainsQuota.java`

- [ ] **Step 1: Add the constant**

```java
    /** One full phase — the buff expires exactly as the next quota check lands. */
    public static final int QUOTA_REWARD_TICKS = 18000;
```

- [ ] **Step 2: Grant Resistance on the met branch**

The met branch currently reads:

```java
            if (met) {
                p.sendSystemMessage(Component.literal(
                                "Emerald Quota met — " + paid + " / " + quota + ".")
                        .withStyle(ChatFormatting.GREEN));
```

Replace with:

```java
            if (met) {
                p.addEffect(new MobEffectInstance(
                        MobEffects.DAMAGE_RESISTANCE, QUOTA_REWARD_TICKS, 0, true, false, true));
                p.sendSystemMessage(Component.literal(
                                "Emerald Quota met — " + paid + " / " + quota
                                        + ". Resistance I for 15 minutes.")
                        .withStyle(ChatFormatting.GREEN));
```

Add imports `net.minecraft.world.effect.MobEffectInstance` and `net.minecraft.world.effect.MobEffects` if absent. The constant is `MobEffects.DAMAGE_RESISTANCE` on 1.21.1.

- [ ] **Step 3: Build and commit**

```bash
./gradlew build
git add src/main/java/com/regionsmoba/lifeline/PlainsQuota.java
git commit -m "feat(lifeline): grant Resistance I for meeting the Plains quota"
```

---

### Task 3: Territorial PvP and retaliation

**Files:**
- Create: `src/main/java/com/regionsmoba/pvp/Retaliation.java`
- Create: `src/test/java/com/regionsmoba/pvp/RetaliationTest.java`
- Modify: `src/main/java/com/regionsmoba/pvp/PvpManager.java`
- Modify: `src/main/java/com/regionsmoba/match/MatchManager.java`

**Interfaces:**
- Produces: `Retaliation.grant(UUID victim, UUID attacker, long nowTick)`, `Retaliation.mayStrike(UUID a, UUID b, long nowTick)`, `Retaliation.clearAll()`, `Retaliation.WINDOW_TICKS`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/regionsmoba/pvp/RetaliationTest.java`. `Retaliation` holds only UUIDs and tick numbers, so it tests without a server.

```java
package com.regionsmoba.pvp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetaliationTest {

    private static final UUID DEFENDER = UUID.nameUUIDFromBytes("defender".getBytes());
    private static final UUID INTRUDER = UUID.nameUUIDFromBytes("intruder".getBytes());
    private static final UUID BYSTANDER = UUID.nameUUIDFromBytes("bystander".getBytes());

    @BeforeEach
    void reset() {
        Retaliation.clearAll();
    }

    @Test
    void noRightToStrikeBeforeBeingHit() {
        assertFalse(Retaliation.mayStrike(INTRUDER, DEFENDER, 100L));
    }

    @Test
    void beingHitGrantsReturnFireAgainstThatAttacker() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        assertTrue(Retaliation.mayStrike(INTRUDER, DEFENDER, 100L));
        assertTrue(Retaliation.mayStrike(INTRUDER, DEFENDER, 100L + Retaliation.WINDOW_TICKS - 1));
    }

    @Test
    void theRightExpires() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        assertFalse(Retaliation.mayStrike(INTRUDER, DEFENDER, 100L + Retaliation.WINDOW_TICKS + 1));
    }

    @Test
    void aLaterHitRefreshesTheWindow() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        Retaliation.grant(INTRUDER, DEFENDER, 200L);
        assertTrue(Retaliation.mayStrike(INTRUDER, DEFENDER, 200L + Retaliation.WINDOW_TICKS - 1));
    }

    @Test
    void theRightDoesNotExtendToTheAttackersTeammates() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        assertFalse(Retaliation.mayStrike(INTRUDER, BYSTANDER, 100L));
    }

    @Test
    void theRightIsDirectional() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        assertFalse(Retaliation.mayStrike(DEFENDER, INTRUDER, 100L));
    }

    @Test
    void clearAllRevokesEverything() {
        Retaliation.grant(INTRUDER, DEFENDER, 100L);
        Retaliation.clearAll();
        assertFalse(Retaliation.mayStrike(INTRUDER, DEFENDER, 100L));
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*RetaliationTest*'`
Expected: FAIL — `package com.regionsmoba.pvp` has no `Retaliation`.

- [ ] **Step 3: Implement Retaliation**

```java
package com.regionsmoba.pvp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the right to return fire.
 *
 * Territorial PvP lets a region's owners strike intruders during peace while
 * intruders may not strike back. This records the exception: once a defender
 * lands a permitted hit, the victim may fight back — against that defender
 * only, for a rolling window.
 *
 * Pair-scoped and directional on purpose. If one defender opening fire granted
 * the intruder a licence to fight the whole team, home-field advantage would
 * evaporate the moment anyone swung.
 *
 * Deliberately free of Minecraft types so the window logic is unit-testable.
 */
public final class Retaliation {

    /** 15 seconds, refreshed by each further hit. */
    public static final long WINDOW_TICKS = 300L;

    /** victim -> attacker -> tick at which the right lapses. */
    private static final Map<UUID, Map<UUID, Long>> rights = new HashMap<>();

    private Retaliation() {}

    /** Records that {@code victim} may strike {@code attacker} back. */
    public static void grant(UUID victim, UUID attacker, long nowTick) {
        rights.computeIfAbsent(victim, k -> new HashMap<>())
                .put(attacker, nowTick + WINDOW_TICKS);
    }

    /** True if {@code a} currently holds the right to strike {@code b}. */
    public static boolean mayStrike(UUID a, UUID b, long nowTick) {
        Map<UUID, Long> byAttacker = rights.get(a);
        if (byAttacker == null) return false;
        Long expiry = byAttacker.get(b);
        if (expiry == null) return false;
        if (expiry <= nowTick) {
            byAttacker.remove(b); // prune lazily; no tick loop needed
            return false;
        }
        return true;
    }

    public static void clearAll() {
        rights.clear();
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests '*RetaliationTest*'`
Expected: PASS — 7 tests.

- [ ] **Step 5: Add the territorial rules to PvpManager**

`isPvpAllowedFor` currently reads:

```java
    public static boolean isPvpAllowedFor(Player attacker, Player target) {
        if (attacker == target) return true;
        if (isInTrialChamber(target) || isInTrialChamber(attacker)) return true;
        return isPvpAllowed();
    }
```

Replace with:

```java
    public static boolean isPvpAllowedFor(Player attacker, Player target) {
        if (attacker == target) return true;
        if (isInTrialChamber(target) || isInTrialChamber(attacker)) return true;
        // Cold seasons and permanent PVP open everything, everywhere. Territory
        // only governs the peace — see the spec's rule ordering.
        if (isPvpAllowed()) return true;

        long now = tickOf(attacker);

        // Home defender: the region containing the VICTIM decides. Keying on the
        // victim lets a defender shoot inward from just outside their own border,
        // and correctly denies an intruder who swings while standing on the
        // defender's ground.
        BiomeTeam owner = regionOwnerAt(target);
        if (owner != null && teamOf(attacker) == owner) {
            Retaliation.grant(target.getUUID(), attacker.getUUID(), now);
            return true;
        }

        // Struck first: return fire against that attacker only.
        return Retaliation.mayStrike(attacker.getUUID(), target.getUUID(), now);
    }

    /** The team whose registered biome bounds contain this entity, or null. */
    private static BiomeTeam regionOwnerAt(Player player) {
        String dimension = player.level().dimension().location().toString();
        for (BiomeTeam team : BiomeTeam.values()) {
            Area area = RegionsConfig.get().biomeBounds(team);
            if (area == null || !area.isComplete()) continue;
            if (!area.dimension().equals(dimension)) continue;
            if (area.contains(player.getX(), player.getY(), player.getZ())) return team;
        }
        return null;
    }

    private static BiomeTeam teamOf(Player player) {
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        return state == null ? null : state.team;
    }

    private static long tickOf(Player player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0L : server.getTickCount();
    }
```

Add imports for `BiomeTeam`, `MatchPlayerState`, `TeamAssignments`, `MinecraftServer`. `Area` and `RegionsConfig` are already imported for the chamber check.

Note the `teamOf(attacker) == owner` comparison also correctly returns false when the attacker has no team (`null`), so non-match players never gain defender rights.

- [ ] **Step 6: Reset Retaliation between matches**

`MatchManager` calls `LifelineState.get().resetAll()` in three places. Add alongside each:

```java
        com.regionsmoba.pvp.Retaliation.clearAll();
```

Find the real call sites by searching for `LifelineState.get().resetAll()`.

- [ ] **Step 7: Build and commit**

```bash
./gradlew build
git add src/main/java/com/regionsmoba/pvp src/test/java/com/regionsmoba/pvp src/main/java/com/regionsmoba/match/MatchManager.java
git commit -m "feat(pvp): territorial PvP with pair-scoped retaliation"
```

---

### Task 4: Mid-match class change

**Files:**
- Modify: `src/main/java/com/regionsmoba/classes/KitGrant.java`
- Modify: `src/main/java/com/regionsmoba/command/NationsCommand.java`

**Interfaces:**
- Produces: `KitGrant.switchClass(ServerPlayer, BiomeClass from, BiomeClass to)`; `/nations class`

- [ ] **Step 1: Add KitGrant.switchClass**

`grant()` is a full reset — it wipes the inventory, clears every armor slot, and resets all ability state. Reusing it would delete everything the player earned. `switchClass` strips only the previous class's **named** kit items, so earned gear survives while ability items cannot be stacked across classes.

```java
    /**
     * Swaps a player's class in place, mid-match.
     *
     * Unlike {@link #grant}, the inventory is NOT wiped — only the previous
     * class's *named* kit items are removed. Unnamed kit items (generic tools,
     * plain armor) are left alone: they are fungible, and a spare wooden pickaxe
     * is not a balance problem. Every ability item carries a custom name, so
     * this is enough to stop a player holding several classes' abilities at once.
     */
    public static void switchClass(ServerPlayer player, BiomeClass from, BiomeClass to) {
        if (from != null) stripNamedKitItems(player, from);

        Cooldowns.get().clearForPlayer(player.getUUID());
        WarriorAbility.clearForPlayer(player.getUUID());
        MinerAbility.clearForPlayer(player.getUUID());
        BardAbility.clearForPlayer(player.getUUID());
        TinkererAbility.clearForPlayer(player.getUUID());
        WizardAbility.clearForPlayer(player.getUUID());
        SpyAbility.clearForPlayer(player.getUUID());
        NeptuneAbility.clearForPlayer(player.getUUID());
        DefenderAlertItem.clearForPlayer(player.getUUID());
        BuilderCache.clearForPlayer(player.getUUID());
        ArcherAbility.clearForPlayer(player.getUUID());
        EnchanterAbility.clearForPlayer(player.getUUID());
        BloodmageAbility.clearCurse(player);
        LumberjackAbility.clearForPlayer(player.getUUID());

        DamageModifiers.apply(player, to);
        BerserkerAbility.applyStack(player, to);

        for (ItemStack template : ClassKits.kit(to)) {
            ItemStack copy = template.copy();
            ClassKits.applyEnchantments(copy, to, player.registryAccess());
            EquipmentSlot slot = player.getEquipmentSlotForItem(copy);
            if (slot.isArmor() && player.getItemBySlot(slot).isEmpty()) {
                player.setItemSlot(slot, copy);
            } else {
                player.getInventory().add(copy);
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Removes every inventory stack whose custom name matches one in this kit. */
    private static void stripNamedKitItems(ServerPlayer player, BiomeClass biomeClass) {
        List<String> names = new java.util.ArrayList<>();
        for (ItemStack template : ClassKits.kit(biomeClass)) {
            Component name = template.get(DataComponents.CUSTOM_NAME);
            if (name != null) names.add(name.getString());
        }
        if (names.isEmpty()) return;

        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            for (String name : names) {
                if (ItemTags.hasName(stack, name)) {
                    inv.setItem(i, ItemStack.EMPTY);
                    break;
                }
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!slot.isArmor()) continue;
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) continue;
            for (String name : names) {
                if (ItemTags.hasName(worn, name)) {
                    player.setItemSlot(slot, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }
```

Add imports: `net.minecraft.core.component.DataComponents`, `net.minecraft.network.chat.Component`, `net.minecraft.world.entity.player.Inventory`, `com.regionsmoba.team.BiomeClass` (likely present), `java.util.List` (present).

Before writing, read `ItemTags.hasName` and confirm how it reads the custom name; mirror that mechanism when collecting names so the two agree. If `ItemTags` already exposes a name-reading helper, use it instead of `DataComponents.CUSTOM_NAME` directly.

- [ ] **Step 2: Add /nations class**

In `NationsCommand.register`, extend the tree:

```java
        dispatcher.register(Commands.literal(ROOT)
                .then(Commands.literal("join").executes(ctx -> join(ctx.getSource())))
                .then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource())))
                .then(Commands.literal("class").executes(ctx -> changeClass(ctx.getSource()))));
```

This root has no `requires()` and must keep it that way — this is a player command, and everything under `/regions` is operator-only.

- [ ] **Step 3: Implement the handler**

```java
    private static int changeClass(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();

        if (!MatchManager.get().isActive()) {
            player.sendSystemMessage(Component.literal("No match is running.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        if (state == null || state.team == null) {
            player.sendSystemMessage(Component.literal("You have no team yet.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (state.spectator) {
            player.sendSystemMessage(Component.literal("Spectators cannot change class.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        Area home = RegionsConfig.get().biomeBounds(state.team);
        String dimension = player.level().dimension().location().toString();
        boolean atHome = home != null && home.isComplete()
                && home.dimension().equals(dimension)
                && home.contains(player.getX(), player.getY(), player.getZ());
        if (!atHome) {
            player.sendSystemMessage(Component.literal(
                            "You must be inside your own region to change class.")
                    .withStyle(ChatFormatting.YELLOW));
            return 0;
        }

        LobbyFlow.openClassPicker(player, state.team);
        return 1;
    }
```

`LobbyFlow.openClassPicker` was made public by an earlier change. Add imports as needed.

- [ ] **Step 4: Route the picker through switchClass**

`ClassPickerMenu`'s pick handler currently calls `KitGrant.grant`. Read `LobbyFlow.onClassPicked` (or whatever handles the pick) and change it so that when the player **already has** a `biomeClass`, it calls `KitGrant.switchClass(player, oldClass, newClass)` instead of `grant`. A first-time pick (`biomeClass == null`) must keep using `grant`, so the initial kit still arrives on a clean inventory.

This is the step that actually makes class switching preserve gear — without it, `/nations class` opens a picker that then wipes the player.

- [ ] **Step 5: Build and commit**

```bash
./gradlew build
git add src/main/java/com/regionsmoba/classes/KitGrant.java src/main/java/com/regionsmoba/command/NationsCommand.java src/main/java/com/regionsmoba/lobby
git commit -m "feat(classes): allow mid-match class change from your own region"
```

---

## In-game verification

1. Let a Nether furnace burn out — the team should die in roughly 20 seconds, not stall at half a heart.
2. Meet the Plains quota — Resistance I appears and lasts a full phase.
3. Stand in your own biome, `/nations class`, switch — earned gear survives, the previous class's ability items are gone, and you cannot hold two classes' ability items.
4. Try `/nations class` from outside your region — rejected with a message.
5. During a **warm** phase, walk into an enemy region. You cannot hit the owner; the owner can hit you; once they do, you can hit them back but not their teammate; the right lapses about 15 seconds after their last hit.
6. Two players from different foreign teams inside a third team's region cannot fight each other.
7. During a **cold** season, confirm everyone can hit everyone everywhere — territorial rules stop applying.
