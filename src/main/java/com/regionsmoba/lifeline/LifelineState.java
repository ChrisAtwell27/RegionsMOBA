package com.regionsmoba.lifeline;

/**
 * In-memory runtime state for the four lifelines. Reset on match start / end.
 * Read by ConduitLifeline / FurnaceLifeline / BloodTributeLifeline; the death
 * handler (slice 7) reads {@link #oceanPermadeath} when an Ocean player dies.
 */
public final class LifelineState {

    public static final int CONDUIT_MAX_HP = 200;

    private static final LifelineState INSTANCE = new LifelineState();

    public static LifelineState get() {
        return INSTANCE;
    }

    /** Ocean — current Conduit HP. Decrements on enemy break; respawn handled in ConduitLifeline. */
    public int conduitHp = CONDUIT_MAX_HP;

    /** Ocean — once true, every ocean player's NEXT death is final regardless of lives. */
    public boolean oceanPermadeath;

    /** Nether — last server tick at which we applied the unlit-furnace damage tick. */
    public long lastFurnaceDamageTick = -1;

    /** Mountain — true if the current cold season's tribute is satisfied. Reset at WARM→COLD. */
    public boolean bloodTributeSatisfied;

    /** Mountain — server tick when current cold season started (for status display). */
    public long bloodTributeStartTick = -1;

    /** Emeralds deposited into the composter toward the current phase's quota. */
    public int plainsQuotaPaid = 0;

    /** Cold-season counter. Starts at 0; incremented before each quota check. */
    public int plainsQuotaSeason = 0;

    private LifelineState() {}

    public void resetAll() {
        conduitHp = CONDUIT_MAX_HP;
        oceanPermadeath = false;
        lastFurnaceDamageTick = -1;
        bloodTributeSatisfied = false;
        bloodTributeStartTick = -1;
        plainsQuotaPaid = 0;
        plainsQuotaSeason = 0;
    }
}
