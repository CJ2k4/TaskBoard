package org.cj.server.board.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.Board;

/** Data access for {@link Board}. */
public interface BoardRepository extends JpaRepository<Board, UUID> {

    /** Boards owned by a user, newest first. Backs {@code GET /api/boards} in M2. */
    List<Board> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
