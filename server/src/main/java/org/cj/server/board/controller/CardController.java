package org.cj.server.board.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.board.dto.CardResponse;
import org.cj.server.board.dto.CreateCardRequest;
import org.cj.server.board.dto.MoveCardRequest;
import org.cj.server.board.dto.UpdateCardRequest;
import org.cj.server.board.service.CardService;

import jakarta.validation.Valid;

/**
 * Card endpoints. Create is nested under its column ({@code /api/columns/{columnId}/cards});
 * update/delete address the card directly by id. All authenticated; ownership is enforced in
 * {@link CardService} via the board guard.
 */
@RestController
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping("/api/columns/{columnId}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse create(@PathVariable UUID columnId,
                               @Valid @RequestBody CreateCardRequest req,
                               @AuthenticationPrincipal AuthPrincipal me) {
        return CardResponse.from(cardService.create(columnId, me.userId(), req.title(), req.description()));
    }

    @PatchMapping("/api/cards/{id}")
    public CardResponse update(@PathVariable UUID id,
                               @Valid @RequestBody UpdateCardRequest req,
                               @AuthenticationPrincipal AuthPrincipal me) {
        return CardResponse.from(cardService.update(
                id, me.userId(), req.title(), req.description(), req.label(), req.assigneeId()));
    }

    /**
     * Move a card (M3). The body is intent — target column + optional neighbour — and the
     * response carries the server-resolved {@code rank}/{@code columnId} for the client to
     * reconcile to.
     */
    @PatchMapping("/api/cards/{id}/move")
    public CardResponse move(@PathVariable UUID id,
                             @Valid @RequestBody MoveCardRequest req,
                             @AuthenticationPrincipal AuthPrincipal me) {
        return CardResponse.from(cardService.move(id, me.userId(), req));
    }

    @DeleteMapping("/api/cards/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal me) {
        cardService.delete(id, me.userId());
        return ResponseEntity.noContent().build();
    }
}
