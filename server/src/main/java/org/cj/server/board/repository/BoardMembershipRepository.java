package org.cj.server.board.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cj.server.board.entity.BoardMembership;

/**
 * Data access for {@link BoardMembership}. M2 uses it only to write the owner row and to
 * confirm it in tests; membership-role lookups become central in M4.
 */
public interface BoardMembershipRepository extends JpaRepository<BoardMembership, UUID> {

    Optional<BoardMembership> findByBoardIdAndUserId(UUID boardId, UUID userId);

    boolean existsByBoardIdAndUserId(UUID boardId, UUID userId);
}
