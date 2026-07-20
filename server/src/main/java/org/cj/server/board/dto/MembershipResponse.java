package org.cj.server.board.dto;

import java.time.Instant;
import java.util.UUID;

import org.cj.server.auth.entity.User;
import org.cj.server.board.entity.BoardMembership;
import org.cj.server.board.entity.MembershipStatus;
import org.cj.server.board.entity.Role;

/**
 * A member (or pending invite) of a board. Two shapes share one type:
 * <ul>
 *   <li><b>ACTIVE member</b> — {@code userId}/{@code name}/{@code email} identify a real
 *       account ({@code invitedEmail} may still show what was originally invited);</li>
 *   <li><b>PENDING invite</b> — only {@code invitedEmail} is set; the person hasn't signed
 *       up yet.</li>
 * </ul>
 * The display fields come from {@code app_user}, joined in the service — a membership row
 * itself only stores the user id.
 */
public record MembershipResponse(
        UUID id,
        UUID boardId,
        UUID userId,
        String name,
        String email,
        String invitedEmail,
        Role role,
        MembershipStatus status,
        Instant createdAt) {

    /** For an ACTIVE membership whose user was loaded; {@code user} may be null for PENDING. */
    public static MembershipResponse from(BoardMembership m, User user) {
        return new MembershipResponse(
                m.getId(),
                m.getBoardId(),
                m.getUserId(),
                user != null ? user.getName() : null,
                user != null ? user.getEmail() : null,
                m.getInvitedEmail(),
                m.getRole(),
                m.getStatus(),
                m.getCreatedAt());
    }
}
