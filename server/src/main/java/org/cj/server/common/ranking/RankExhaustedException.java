package org.cj.server.common.ranking;

/**
 * Thrown when {@link LexoRank#between} cannot produce a key within the length cap — the sign
 * that a column has been subdivided so many times in one spot that its rank space is
 * effectively exhausted. The fix is to re-balance (re-space) that column's ranks, which is an
 * M3 concern; in M2 this never trips. It's a {@link RuntimeException} so callers aren't forced
 * to handle a case that can't yet occur.
 */
public class RankExhaustedException extends RuntimeException {
    public RankExhaustedException(String prev, String next) {
        super("Rank space exhausted between '" + prev + "' and '" + next + "'; column needs re-balancing");
    }
}
