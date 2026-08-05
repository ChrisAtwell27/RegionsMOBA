package com.regionsmoba.classes;

import com.regionsmoba.classes.impl.AcrobatAbility;
import com.regionsmoba.classes.impl.AlchemistAbility;
import com.regionsmoba.classes.impl.ArcherAbility;
import com.regionsmoba.classes.impl.BardAbility;
import com.regionsmoba.classes.impl.BerserkerAbility;
import com.regionsmoba.classes.impl.BloodmageAbility;
import com.regionsmoba.classes.impl.BloodmageTerraform;
import com.regionsmoba.classes.impl.BuilderAbility;
import com.regionsmoba.classes.impl.DefenderAbility;
import com.regionsmoba.classes.impl.EnchanterAbility;
import com.regionsmoba.classes.impl.FarmerAbility;
import com.regionsmoba.classes.impl.HealerAbility;
import com.regionsmoba.classes.impl.ImmobilizerAbility;
import com.regionsmoba.classes.impl.LumberjackAbility;
import com.regionsmoba.classes.impl.MinerAbility;
import com.regionsmoba.classes.impl.NeptuneGroundFreeze;
import com.regionsmoba.classes.impl.RiftWalkerAbility;
import com.regionsmoba.classes.impl.ScoutAbility;
import com.regionsmoba.classes.impl.SirenAbility;
import com.regionsmoba.classes.impl.TinkererAbility;
import com.regionsmoba.classes.impl.VampireAbility;
import com.regionsmoba.classes.impl.WarriorAbility;
import com.regionsmoba.classes.impl.WizardAbility;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.team.BiomeClass;
import com.regionsmoba.team.MatchPlayerState;
import com.regionsmoba.team.TeamAssignments;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central wiring point for class-ability event listeners and per-tick work.
 *
 * Ability implementations live in {@link com.regionsmoba.classes.impl} and are
 * registered/ticked through here so {@link com.regionsmoba.RegionsMOBA} only has
 * to call {@code AbilityHooks.register()} once during mod init and
 * {@code AbilityHooks.tick(server)} from the END_SERVER_TICK loop.
 *
 * Four event surfaces are wired:
 *   UseItemCallback           — right-click ability items, dispatched by class.
 *   AttackEntityCallback      — left-click abilities (Healer's focused heal).
 *   ALLOW_DAMAGE              — Acrobat fall immunity.
 *   AFTER_DAMAGE              — post-hit bonuses (Berserker/Spy/Lumberjack/Archer/
 *                               Warrior), Vampire HP steal, Scout combat tag.
 *   AFTER_DEATH               — Warrior Frenzy kill credit.
 *
 * Every listener short-circuits when no match is active so vanilla play on the
 * same server is untouched.
 */
public final class AbilityHooks {

    /**
     * Server tick of each player's most recent melee attack, used to tell a
     * connecting swing apart from a left-click ability cast. Bounded by the
     * online player count and cleared with the match.
     */
    private static final Map<UUID, Integer> LAST_ATTACK_TICK = new HashMap<>();

    private AbilityHooks() {}

    public static void register() {
        // Right-click at air. UseItemCallback only fires when the crosshair is on
        // nothing — a right-click aimed at a block goes down the UseBlockCallback
        // path below and never reaches here, so both are wired.
        UseItemCallback.EVENT.register((player, level, hand) ->
                onRightClick(player, level.isClientSide(), hand, null));

        // Right-click at a block. Claiming the click here also suppresses the
        // block interaction (chest opening, composter placement, …), which is what
        // we want: ability items are never meant to place or activate anything.
        // The hit result is forwarded because placement abilities (Buffbox,
        // PowerPads) need the aimed face to know where the block goes.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                onRightClick(player, level.isClientSide(), hand, hitResult));

        AttackEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
            if (level.isClientSide()) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer healer)) return InteractionResult.PASS;
            // Note the attack so the swing handler below can tell a connecting
            // melee swing apart from a left-click cast. The vanilla client sends
            // the interact packet before the swing packet, so this lands first.
            MinecraftServer attackServer = healer.level().getServer();
            if (attackServer != null) {
                LAST_ATTACK_TICK.put(healer.getUUID(), attackServer.getTickCount());
            }
            if (!(target instanceof Player victim)) return InteractionResult.PASS;
            BiomeClass bc = classOf(healer);
            if (bc != BiomeClass.OCEAN_HEALER) return InteractionResult.PASS;
            // Consuming the swing is the point here: the Healer heals teammates
            // instead of hitting them.
            return HealerAbility.tryLeftClickOnTeammate(healer, victim)
                    ? InteractionResult.SUCCESS_SERVER
                    : InteractionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) return true;
            if (classOf(player) != BiomeClass.MOUNTAIN_ACROBAT) return true;
            return !AcrobatAbility.cancelsFallDamage(source);
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, source, baseDamage, damageTaken, blocked) -> {
                    if (blocked) return;
                    onAfterDamage(entity, source, damageTaken);
                });

        ServerLivingEntityEvents.AFTER_DEATH.register(WarriorAbility::onEnemyDeath);

        // Placed class blocks are owner-gated: a Bard's own team can't break
        // their Buffbox. Runs beside BlockProtection's BEFORE listener; Fabric
        // ANDs the results, so either one can veto.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!MatchManager.get().isActive()) return true;
            if (!(world instanceof ServerLevel level)) return true;
            if (!(player instanceof ServerPlayer serverPlayer)) return true;
            return BardAbility.allowBreak(serverPlayer, level, pos);
        });

        // Post-break bonus drops. Runs alongside DepositBreakHook's AFTER
        // listener; both read the captured pre-break state, so listener order
        // between them doesn't matter.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!MatchManager.get().isActive()) return;
            if (!(world instanceof ServerLevel level)) return;
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            // Ownership bookkeeping runs for any breaker, not just the owner's class.
            BardAbility.onBroken(serverPlayer, level, pos);
            TinkererAbility.onBroken(serverPlayer, level, pos);

            BiomeClass bc = classOf(serverPlayer);
            if (bc == null) return;
            switch (bc) {
                case MOUNTAIN_MINER -> MinerAbility.onBlockBroken(level, serverPlayer, pos, state);
                case PLAINS_LUMBERJACK -> LumberjackAbility.onBlockBroken(level, serverPlayer, pos, state);
                default -> {}
            }
        });
    }

    private static InteractionResult onRightClick(Player player, boolean clientSide, InteractionHand hand,
                                                  BlockHitResult hit) {
        if (clientSide) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        // An empty hand still dispatches: the Immobilizer's stun has no ability
        // item and is cast bare-handed.
        ItemStack stack = player.getItemInHand(hand);
        // SUCCESS_SERVER: the ability consumed the click; don't also run the
        // vanilla use behaviour of the underlying item (drinking, placing, …).
        return dispatchRightClick(serverPlayer, stack, hit)
                ? InteractionResult.SUCCESS_SERVER
                : InteractionResult.PASS;
    }

    /**
     * Routes a right-click to the ability class that owns the player's kit.
     * Returns true when the ability claimed the click. {@code hit} is null when
     * the click was aimed at air.
     */
    private static boolean dispatchRightClick(ServerPlayer player, ItemStack stack, BlockHitResult hit) {
        BiomeClass bc = classOf(player);
        if (bc == null) return false;
        return switch (bc) {
            case OCEAN_DEFENDER -> DefenderAbility.tryRightClick(player, stack);
            case OCEAN_SIREN -> SirenAbility.tryRightClick(player, stack);
            case OCEAN_HEALER -> HealerAbility.tryRightClick(player, stack);
            case NETHER_ALCHEMIST -> AlchemistAbility.tryRightClick(player, stack);
            case NETHER_WIZARD -> WizardAbility.tryRightClick(player, stack);
            // Corrupt and the curse field are separate items on the same kit.
            case NETHER_BLOODMAGE -> BloodmageAbility.tryRightClick(player, stack)
                    || BloodmageTerraform.tryRightClick(player, stack);
            case NETHER_ENCHANTER -> EnchanterAbility.tryRightClick(player, stack);
            case NETHER_VAMPIRE -> VampireAbility.tryRightClick(player, stack);
            case NETHER_RIFT_WALKER -> RiftWalkerAbility.tryRightClick(player, stack);
            case PLAINS_FARMER -> FarmerAbility.tryRightClick(player, stack);
            case PLAINS_SCOUT -> ScoutAbility.tryRightClick(player, stack);
            case MOUNTAIN_BUILDER -> BuilderAbility.tryRightClick(player, stack);
            case MOUNTAIN_WARRIOR -> WarriorAbility.tryRightClick(player, stack);
            case MOUNTAIN_BERSERKER -> BerserkerAbility.tryRightClick(player, stack);
            case OCEAN_IMMOBILIZER -> ImmobilizerAbility.tryRightClick(player);
            case MOUNTAIN_MINER -> MinerAbility.tryRightClick(player, stack);
            case PLAINS_LUMBERJACK -> LumberjackAbility.tryRightClick(player, stack);
            case PLAINS_BARD -> BardAbility.tryRightClick(player, stack, hit);
            case MOUNTAIN_TINKERER -> TinkererAbility.tryRightClick(player, stack, hit);
            default -> false;
        };
    }

    /**
     * Every main-hand swing, routed here by {@link com.regionsmoba.mixin.PlayerSwingMixin}.
     * Fabric's attack callbacks only fire on a swing that connects; left-click
     * abilities are cast at empty air just as often.
     */
    public static void onSwing(ServerPlayer player) {
        if (!MatchManager.get().isActive()) return;
        // A swing that landed on an entity is an attack, not a cast. Without
        // this, an Immobilizer punching someone would burn the 30-second stun
        // cooldown on the AoE slow every single hit.
        MinecraftServer server = player.level().getServer();
        if (server != null
                && Integer.valueOf(server.getTickCount()).equals(LAST_ATTACK_TICK.get(player.getUUID()))) {
            return;
        }
        BiomeClass bc = classOf(player);
        if (bc == null) return;
        switch (bc) {
            case OCEAN_IMMOBILIZER -> ImmobilizerAbility.trySwing(player);
            case NETHER_WIZARD -> WizardAbility.trySwing(player);
            default -> {}
        }
    }

    private static void onAfterDamage(LivingEntity victim, DamageSource source, float damageTaken) {
        if (!MatchManager.get().isActive()) return;

        // Post-hit flat/scaled bonuses that need attacker *and* victim state.
        BonusDamageHook.apply(victim, source, damageTaken);

        if (source.getEntity() instanceof ServerPlayer attacker
                && source.getDirectEntity() == attacker
                && victim instanceof Player victimPlayer
                && attacker != victim
                && classOf(attacker) == BiomeClass.NETHER_VAMPIRE) {
            VampireAbility.onMeleeHit(attacker, victimPlayer);
        }

        if (source.getEntity() instanceof ServerPlayer attacker
                && source.getDirectEntity() == attacker
                && victim instanceof Player victimPlayer
                && attacker != victim
                && classOf(attacker) == BiomeClass.PLAINS_LUMBERJACK) {
            LumberjackAbility.onAxeHit(attacker, victimPlayer);
        }

        if (source.getEntity() instanceof ServerPlayer attacker
                && source.getDirectEntity() == attacker
                && victim instanceof Player victimPlayer
                && attacker != victim
                && classOf(attacker) == BiomeClass.NETHER_BLOODMAGE) {
            BloodmageAbility.onMeleeHit(attacker, victimPlayer);
        }

        if (victim instanceof ServerPlayer hurtPlayer
                && classOf(hurtPlayer) == BiomeClass.PLAINS_SCOUT) {
            ScoutAbility.onScoutHit(hurtPlayer);
        }
    }

    public static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        Cooldowns.get().tick(tick);
        if (!MatchManager.get().isActive()) return;

        NeptuneGroundFreeze.tick(server, tick);
        WarriorAbility.tick(server, tick);
        BerserkerAbility.tick(server, tick);
        MinerAbility.tick(server, tick);
        LumberjackAbility.tick(server, tick);
        BardAbility.tick(server, tick);
        TinkererAbility.tick(server, tick);
        BloodmageTerraform.tick(server, tick);

        for (UUID id : MatchManager.get().matchPlayers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            MatchPlayerState state = TeamAssignments.get().state(id);
            if (state == null || state.spectator || state.biomeClass == null) continue;
            switch (state.biomeClass) {
                case MOUNTAIN_ACROBAT -> AcrobatAbility.tick(player);
                case OCEAN_DEFENDER -> DefenderAbility.tick(player, tick);
                case PLAINS_ARCHER -> ArcherAbility.tick(player);
                default -> {}
            }
        }
    }

    /** Drops the attack-tick bookkeeping. Called from match teardown. */
    public static void clearAll() {
        LAST_ATTACK_TICK.clear();
    }

    private static BiomeClass classOf(ServerPlayer player) {
        MatchPlayerState state = TeamAssignments.get().state(player.getUUID());
        return state == null ? null : state.biomeClass;
    }
}
