package org.cj.server.board.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.Board;

/** Data access for {@link Board}. */
public interface BoardRepository extends JpaRepository<Board, UUID> {

    /**
     * Boards by id, newest first. Backs {@code GET /api/boards}: since M4 the visible set is
     * "boards I'm an active member of", so the ids come from {@code board_membership} rather
     * than from an owner column.
     */
    List<Board> findByIdInOrderByCreatedAtDesc(Collection<UUID> ids);
}
