package org.cj.server.board.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.BoardColumn;

/** Data access for {@link BoardColumn}. */
public interface BoardColumnRepository extends JpaRepository<BoardColumn, UUID> {

    /** A board's columns in display (rank) order. */
    List<BoardColumn> findByBoardIdOrderByRankAsc(UUID boardId);

    /** The last column on a board — its rank is the lower bound when appending a new column. */
    Optional<BoardColumn> findFirstByBoardIdOrderByRankDesc(UUID boardId);
}
