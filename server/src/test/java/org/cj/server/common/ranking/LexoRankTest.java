package org.cj.server.common.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests for {@link LexoRank} — the single sharpest unit in the app. Every drag,
 * every insert, every reload's ordering depends on {@code between} always returning a key
 * strictly between its bounds. We test the invariant directly, plus heavy randomized stress.
 */
class LexoRankTest {

    @Test
    void firstRankIsNonEmpty() {
        String first = LexoRank.between(null, null);
        assertThat(first).isNotEmpty();
    }

    @Test
    void appendProducesAGreaterKey() {
        String a = LexoRank.between(null, null);
        String b = LexoRank.between(a, null);
        assertThat(b).isGreaterThan(a);
    }

    @Test
    void prependProducesASmallerKey() {
        String a = LexoRank.between(null, null);
        String b = LexoRank.between(null, a);
        assertThat(b).isLessThan(a);
    }

    @Test
    void resultIsStrictlyBetweenAdjacentBounds() {
        String mid = LexoRank.between("i", "j"); // adjacent digits — forces descent
        assertThat(mid).isGreaterThan("i");
        assertThat(mid).isLessThan("j");
    }

    @Test
    void resultIsStrictlyBetweenBoundsSharingAPrefix() {
        String mid = LexoRank.between("abc", "abe");
        assertThat(mid).isGreaterThan("abc");
        assertThat(mid).isLessThan("abe");
    }

    @Test
    void repeatedInsertBeforeTheSameUpperBoundStaysOrdered() {
        // Always insert between a fixed lower bound and the previous result: the gap shrinks
        // toward the lower bound and the key grows, but ordering must always hold.
        String lo = "a";
        String hi = "b";
        String prev = hi;
        for (int n = 0; n < 40; n++) {
            String mid = LexoRank.between(lo, prev);
            assertThat(mid).isGreaterThan(lo);
            assertThat(mid).isLessThan(prev);
            prev = mid;
        }
    }

    @Test
    void repeatedAppendIsMonotonicallyIncreasing() {
        String prev = LexoRank.between(null, null);
        for (int n = 0; n < 100; n++) {
            String next = LexoRank.between(prev, null);
            assertThat(next).isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    void randomizedInsertionsKeepTheListSorted() {
        // The real-world scenario: build up a list by repeatedly inserting a new item between
        // two neighbours (or at an end). The list, kept in insertion-position order, must stay
        // strictly sorted by rank the entire time.
        Random rnd = new Random(42);
        List<String> ranks = new ArrayList<>();
        ranks.add(LexoRank.between(null, null));

        for (int n = 0; n < 2000; n++) {
            int gap = rnd.nextInt(ranks.size() + 1); // 0..size = every slot incl. both ends
            String prev = gap == 0 ? null : ranks.get(gap - 1);
            String next = gap == ranks.size() ? null : ranks.get(gap);

            String rank = LexoRank.between(prev, next);
            if (prev != null) {
                assertThat(rank).isGreaterThan(prev);
            }
            if (next != null) {
                assertThat(rank).isLessThan(next);
            }
            ranks.add(gap, rank);
        }

        // Whole list is strictly increasing and unique.
        for (int i = 1; i < ranks.size(); i++) {
            assertThat(ranks.get(i)).isGreaterThan(ranks.get(i - 1));
        }
        assertThat(ranks).doesNotHaveDuplicates();
    }

    @Test
    void rejectsPrevGreaterThanOrEqualToNext() {
        assertThatThrownBy(() -> LexoRank.between("b", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LexoRank.between("m", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyBound() {
        assertThatThrownBy(() -> LexoRank.between("", "z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LexoRank.between("a", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsRankExhaustedWhenKeysGrowTooLong() {
        // Adversarial: keep inserting just below a shrinking upper bound against a fixed lower
        // bound. Keys grow ~1 digit every few inserts; eventually they hit the length cap and
        // the util signals that a re-balance is needed (an M3 concern) rather than looping.
        assertThatThrownBy(() -> {
            String lo = "a";
            String hi = "b";
            for (int n = 0; n < 10_000; n++) {
                hi = LexoRank.between(lo, hi);
            }
        }).isInstanceOf(RankExhaustedException.class);
    }
}
