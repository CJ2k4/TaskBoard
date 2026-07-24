package org.cj.server.board.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.Card;

/** Data access for {@link Card}. */
public interface CardRepository extends JpaRepository<Card, UUID> {

    /** A column's cards in display (rank) order. */
    List<Card> findByColumnIdOrderByRankAsc(UUID columnId);

    /**
     * Every card on a board in rank order, via the denormalized {@code board_id} — one query
     * that avoids joining through columns. Backs the whole-board aggregate read (M2.6).
     */
    List<Card> findByBoardIdOrderByRankAsc(UUID boardId);

    /** The last card in a column — its rank is the lower bound when appending a new card. */
    Optional<Card> findFirstByColumnIdOrderByRankDesc(UUID columnId);

    /** Whether a column has any card — used to block deleting a non-empty column. */
    boolean existsByColumnId(UUID columnId);

    /** How many cards a board has — for the dashboard overview card. */
    long countByBoardId(UUID boardId);
}
