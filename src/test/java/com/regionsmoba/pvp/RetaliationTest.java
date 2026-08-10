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

    /** Home-field advantage would evaporate if one defender's swing armed the whole team. */
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
