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
import org.cj.server.board.dto.ColumnResponse;
import org.cj.server.board.dto.CreateColumnRequest;
import org.cj.server.board.dto.UpdateColumnRequest;
import org.cj.server.board.service.ColumnService;

import jakarta.validation.Valid;

/**
 * Column endpoints. Create is nested under its board ({@code /api/boards/{boardId}/columns});
 * update/delete address the column directly by id. All authenticated; ownership is enforced in
 * {@link ColumnService} via the board guard.
 */
@RestController
public class ColumnController {

    private final ColumnService columnService;

    public ColumnController(ColumnService columnService) {
        this.columnService = columnService;
    }

    @PostMapping("/api/boards/{boardId}/columns")
    @ResponseStatus(HttpStatus.CREATED)
    public ColumnResponse create(@PathVariable UUID boardId,
                                 @Valid @RequestBody CreateColumnRequest req,
                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ColumnResponse.from(columnService.create(boardId, me.userId(), req.title()));
    }

    @PatchMapping("/api/columns/{id}")
    public ColumnResponse rename(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateColumnRequest req,
                                 @AuthenticationPrincipal AuthPrincipal me) {
        return ColumnResponse.from(columnService.rename(id, me.userId(), req.title()));
    }

    @DeleteMapping("/api/columns/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal me) {
        columnService.delete(id, me.userId());
        return ResponseEntity.noContent().build();
    }
}
