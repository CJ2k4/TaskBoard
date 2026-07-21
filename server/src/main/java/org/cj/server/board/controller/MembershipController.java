package org.cj.server.board.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.board.dto.CreateInviteLinkRequest;
import org.cj.server.board.dto.CreateInviteRequest;
import org.cj.server.board.dto.InviteLinkResponse;
import org.cj.server.board.dto.JoinResult;
import org.cj.server.board.dto.MembershipResponse;
import org.cj.server.board.dto.UpdateMembershipRequest;
import org.cj.server.board.service.MembershipService;

import jakarta.validation.Valid;

/**
 * Sharing endpoints (M4). Invites and the member list are nested under their board;
 * role-change and removal address the membership row directly by id — the project's
 * create-under-parent / mutate-by-id shape.
 */
@RestController
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/api/boards/{boardId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse invite(@PathVariable UUID boardId,
                                     @Valid @RequestBody CreateInviteRequest req,
                                     @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.invite(boardId, me.userId(), req);
    }

    @GetMapping("/api/boards/{boardId}/members")
    public List<MembershipResponse> members(@PathVariable UUID boardId,
                                            @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.listMembers(boardId, me.userId());
    }

    @PatchMapping("/api/memberships/{id}")
    public MembershipResponse changeRole(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateMembershipRequest req,
                                         @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.changeRole(id, me.userId(), req);
    }

    @DeleteMapping("/api/memberships/{id}")
    public ResponseEntity<Void> remove(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal me) {
        membershipService.remove(id, me.userId());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- invite links (M6)

    /** Create or rotate the board's shareable link (owner only). */
    @PostMapping("/api/boards/{boardId}/invite-link")
    public InviteLinkResponse createInviteLink(@PathVariable UUID boardId,
                                               @Valid @RequestBody CreateInviteLinkRequest req,
                                               @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.createOrRotateLink(boardId, me.userId(), req.role());
    }

    /** The board's current link, or a null-token response if none (owner only). */
    @GetMapping("/api/boards/{boardId}/invite-link")
    public InviteLinkResponse inviteLink(@PathVariable UUID boardId,
                                         @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.getLink(boardId, me.userId());
    }

    /** Disable the board's link (owner only). */
    @DeleteMapping("/api/boards/{boardId}/invite-link")
    public ResponseEntity<Void> disableInviteLink(@PathVariable UUID boardId,
                                                  @AuthenticationPrincipal AuthPrincipal me) {
        membershipService.disableLink(boardId, me.userId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Redeem a link: the signed-in caller joins the board it points to. Not owner-scoped — holding
     * the token is the authorization — but still authenticated (the default security rule), so an
     * anonymous visitor is bounced to log in first and returns here.
     */
    @PostMapping("/api/invite-links/{token}/accept")
    public JoinResult acceptInviteLink(@PathVariable UUID token,
                                       @AuthenticationPrincipal AuthPrincipal me) {
        return membershipService.acceptLink(token, me.userId());
    }
}
