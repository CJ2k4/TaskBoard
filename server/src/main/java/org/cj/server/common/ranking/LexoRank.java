package org.cj.server.common.ranking;

/**
 * LexoRank-style fractional ordering keys. A "rank" is a short string; items are ordered by
 * plain lexicographic comparison of their ranks. To insert an item <em>between</em> two
 * neighbours we compute a rank string that sorts strictly between theirs — so inserting never
 * has to renumber siblings, and two clients can insert in the same place without colliding on
 * an integer position. This is the core drag-and-drop ordering primitive (see
 * {@code project-scope.md}); the server is always the authority that computes these.
 *
 * <h2>How it works</h2>
 * A rank string is read as a fraction in base-36 with an implicit leading "0.". The digits are
 * {@code 0-9a-z}, whose ASCII order matches their numeric value, so <b>string comparison equals
 * numeric comparison</b> (a shorter string behaves as if padded with the smallest digit
 * {@code '0'}). {@link #between} finds a value strictly between the two bounds: it walks the
 * digits, and at the first place the bounds differ it either drops in a midpoint digit or, if
 * the bounds are adjacent, descends one more place (growing the string) to make room.
 *
 * <p>{@code null} bounds mean "open end": {@code between(null, null)} is the very first rank,
 * {@code between(last, null)} appends, {@code between(null, first)} prepends.
 *
 * <p><b>Not handled here:</b> rank <em>exhaustion</em> (keys growing without bound after many
 * inserts in the same spot). If a computed key would exceed {@link #MAX_LENGTH} we throw
 * {@link RankExhaustedException}, which is M3's signal to re-balance the column. For M2's
 * modest insert volumes this never trips.
 */
public final class LexoRank {

    private static final int BASE = 36;

    /**
     * Safety cap on generated key length. Doubles as the rank-exhaustion detector: the DB
     * {@code rank} column is {@code varchar(64)}, and long before that a column should be
     * re-balanced (M3). Well beyond any realistic M2 insert depth.
     */
    static final int MAX_LENGTH = 48;

    private LexoRank() {
    }

    /**
     * A rank string strictly between {@code prev} and {@code next} (lexicographically). Either
     * bound may be {@code null} for an open end. When both are given, {@code prev} must be
     * strictly less than {@code next}.
     *
     * @throws IllegalArgumentException if a bound is empty, or {@code prev >= next}
     * @throws RankExhaustedException   if no key short enough can be produced (needs re-balance)
     */
    public static String between(String prev, String next) {
        validateBound(prev);
        validateBound(next);
        if (prev != null && next != null && prev.compareTo(next) >= 0) {
            throw new IllegalArgumentException(
                    "prev must be strictly less than next, got prev='" + prev + "', next='" + next + "'");
        }

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            if (sb.length() >= MAX_LENGTH) {
                throw new RankExhaustedException(prev, next);
            }
            int lower = digitAt(prev, i);           // 0 when prev is null or shorter than i
            int upper = upperDigitAt(next, i);      // BASE (=36) when next is an open upper bound

            if (lower == upper) {
                // Bounds share this digit — copy it and keep both constraints.
                sb.append(digit(lower));
                i++;
                continue;
            }
            int mid = (lower + upper) / 2;
            if (mid == lower) {
                // Bounds are adjacent (upper == lower + 1): no digit fits between them here.
                // Take the lower digit and descend; below this level the upper bound is open.
                sb.append(digit(lower));
                i++;
                next = null;
                continue;
            }
            // Room to insert: a midpoint digit strictly between the two bounds ends it.
            sb.append(digit(mid));
            return sb.toString();
        }
    }

    /** Digit value of {@code s} at index {@code i}, or 0 (the minimum) past its end / when null. */
    private static int digitAt(String s, int i) {
        if (s == null || i >= s.length()) {
            return 0;
        }
        return value(s.charAt(i));
    }

    /**
     * Upper-bound digit at {@code i}. A {@code null} next is an open upper bound → {@code BASE}
     * (one past the max digit). Past a non-null next's end the faithful value is 0, but that
     * only arises for degenerate bounds the length cap will catch.
     */
    private static int upperDigitAt(String next, int i) {
        if (next == null) {
            return BASE;
        }
        if (i >= next.length()) {
            return 0;
        }
        return value(next.charAt(i));
    }

    private static int value(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 10;
        }
        throw new IllegalArgumentException("Illegal rank digit: '" + c + "'");
    }

    private static char digit(int v) {
        return v < 10 ? (char) ('0' + v) : (char) ('a' + v - 10);
    }

    private static void validateBound(String bound) {
        if (bound != null && bound.isEmpty()) {
            throw new IllegalArgumentException("rank bound must be null (open) or non-empty");
        }
    }
}
