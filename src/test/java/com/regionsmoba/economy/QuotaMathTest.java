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
