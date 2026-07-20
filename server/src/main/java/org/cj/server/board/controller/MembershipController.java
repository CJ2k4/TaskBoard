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
import org.cj.server.board.dto.CreateInviteRequest;
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
}
