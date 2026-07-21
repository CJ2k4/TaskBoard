package org.cj.server.board.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.BoardActivity;

/** Data access for {@link BoardActivity}. Reads are always newest-first and page-limited. */
public interface BoardActivityRepository extends JpaRepository<BoardActivity, UUID> {

    /** The most recent entries for a board. {@code Pageable} caps how many. */
    List<BoardActivity> findByBoardIdOrderByCreatedAtDesc(UUID boardId, Pageable pageable);

    /** Older entries, for "load more": everything before a cursor timestamp, newest of those first. */
    List<BoardActivity> findByBoardIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID boardId, Instant before, Pageable pageable);
}
