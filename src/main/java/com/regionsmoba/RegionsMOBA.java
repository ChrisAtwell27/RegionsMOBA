package com.regionsmoba;

import com.regionsmoba.chamber.TrialChamber;
import com.regionsmoba.classes.AbilityHooks;
import com.regionsmoba.command.NationsCommand;
import com.regionsmoba.command.RegionsCommand;
import com.regionsmoba.command.click.ClickHandler;
import com.regionsmoba.command.click.PendingRegistrationStore;
import com.regionsmoba.config.RegionsConfig;
import com.regionsmoba.death.DeathHandler;
import com.regionsmoba.debug.VisualizationOverlays;
import com.regionsmoba.deposit.DepositBreakHook;
import com.regionsmoba.deposit.DepositTracker;
import com.regionsmoba.economy.TraderInteraction;
import com.regionsmoba.events.MassEventTargeting;
import com.regionsmoba.hud.LifelineBars;
import com.regionsmoba.lifeline.BloodTributeLifeline;
import com.regionsmoba.lifeline.ComposterLifeline;
import com.regionsmoba.lifeline.ConduitLifeline;
import com.regionsmoba.lifeline.FurnaceLifeline;
import com.regionsmoba.lifeline.PlainsQuota;
import com.regionsmoba.lobby.LobbyFlow;
import com.regionsmoba.match.MatchEndConditions;
import com.regionsmoba.match.MatchManager;
import com.regionsmoba.protection.BlockProtection;
import com.regionsmoba.pvp.PvpDamageHook;
import com.regionsmoba.timeline.ColdSeasonEffects;
import com.regionsmoba.timeline.DrownedSpawning;
import com.regionsmoba.timeline.NetherColdWater;
import com.regionsmoba.timeline.OceanWaterFreeze;
import com.regionsmoba.timeline.Timeline;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegionsMOBA implements ModInitializer {
    public static final String MOD_ID = "regionsmoba";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        RegionsConfig.load();
        RegionsCommand.registerAll();
        NationsCommand.registerAll();
        ClickHandler.register();
        LobbyFlow.register();
        PvpDamageHook.register();
        ConduitLifeline.register();
        ComposterLifeline.register();
        BloodTributeLifeline.register();
        BlockProtection.register();
        DepositBreakHook.register();
        DeathHandler.register();
        AbilityHooks.register();
        TraderInteraction.register();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> MatchManager.get().attachServer(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionsConfig.save();
            MatchManager.get().onServerStop();
            PendingRegistrationStore.clearAll();
        });

        // Drive the match timeline + per-second cold-season effects + lifeline tickers
        // from the server tick. Phase-change side-effects (Blood Tribute reset/eval) fire
        // here too so the Timeline tick stays the single source of truth for transitions.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tick = server.getTickCount();
            Timeline.PhaseChange change = Timeline.get().tick();
            if (change != null) {
                BloodTributeLifeline.onPhaseChange(change, server);
                PlainsQuota.onPhaseChange(server, change.from(), change.to());
            }
            ColdSeasonEffects.tick(server, tick);
            NetherColdWater.tick(server, tick);
            OceanWaterFreeze.tick(server, tick);
            DrownedSpawning.tick(server, tick);
            FurnaceLifeline.tick(server, tick);
            DepositTracker.get().tick(server, tick);
            MassEventTargeting.get().tick(server);
            TrialChamber.get().tick(server, tick);
            AbilityHooks.tick(server);
            VisualizationOverlays.tick(server, tick);
            LifelineBars.get().tick(server, tick);
            MatchEndConditions.tick(server, tick);
        });

        LOGGER.info("RegionsMOBA initialized");
    }
}
