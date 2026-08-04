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
