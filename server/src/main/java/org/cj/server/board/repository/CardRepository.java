package org.cj.server.board.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.Card;

/**
 * Data access for {@link Card}.
 *
 * <p>Since the bin (soft delete) landed, <b>every board-facing query names
 * {@code DeletedAtIsNull} explicitly</b>. That verbosity is the point: the filter is visible at
 * each call site, so nobody has to remember that a bare {@code findByColumnId} would quietly
 * hand back binned cards. The only queries that look at binned rows are the bin listing and the
 * retention purge, and they say {@code DeletedAtIsNotNull} just as loudly.
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    /** A column's live cards in display (rank) order. */
    List<Card> findByColumnIdAndDeletedAtIsNullOrderByRankAsc(UUID columnId);

    /**
     * Every live card on a board in rank order, via the denormalized {@code board_id} — one query
     * that avoids joining through columns. Backs the whole-board aggregate read (M2.6).
     */
    List<Card> findByBoardIdAndDeletedAtIsNullOrderByRankAsc(UUID boardId);

    /** The last live card in a column — its rank is the lower bound when appending a new card. */
    Optional<Card> findFirstByColumnIdAndDeletedAtIsNullOrderByRankDesc(UUID columnId);

    /** Whether a column has any live card — used to block deleting a non-empty column. */
    boolean existsByColumnIdAndDeletedAtIsNull(UUID columnId);

    /**
     * Whether a column still holds cards in the bin. Deleting the column would cascade those
     * rows away (the FK is {@code ON DELETE CASCADE}) and silently break the restore guarantee,
     * so {@code ColumnService} refuses while this is true.
     */
    boolean existsByColumnIdAndDeletedAtIsNotNull(UUID columnId);

    /** How many live cards a board has — for the dashboard overview card. */
    long countByBoardIdAndDeletedAtIsNull(UUID boardId);

    /** A board's bin, most recently binned first. */
    List<Card> findByBoardIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID boardId);

    /**
     * Permanently remove every card binned before {@code cutoff} — the retention purge. Returns
     * how many rows went, which the job logs.
     */
    long deleteByDeletedAtBefore(Instant cutoff);
}
