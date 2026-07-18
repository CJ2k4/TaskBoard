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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.cj.server.auth.security.AuthPrincipal;
import org.cj.server.board.dto.BoardDetailResponse;
import org.cj.server.board.dto.BoardResponse;
import org.cj.server.board.dto.CreateBoardRequest;
import org.cj.server.board.dto.UpdateBoardRequest;
import org.cj.server.board.service.BoardService;

import jakarta.validation.Valid;

/**
 * Board endpoints under {@code /api/boards}. All require authentication (the default rule in
 * {@code SecurityConfig}); the current user comes from {@code @AuthenticationPrincipal} and is
 * passed to the service, which scopes every operation to boards that user owns.
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse create(@Valid @RequestBody CreateBoardRequest req,
                                @AuthenticationPrincipal AuthPrincipal me) {
        return BoardResponse.from(boardService.create(req.name(), me.userId()));
    }

    @GetMapping
    public List<BoardResponse> list(@AuthenticationPrincipal AuthPrincipal me) {
        return boardService.listOwned(me.userId()).stream().map(BoardResponse::from).toList();
    }

    /** Returns the whole board — columns and cards nested, in rank order. */
    @GetMapping("/{id}")
    public BoardDetailResponse get(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal me) {
        return boardService.getDetail(id, me.userId());
    }

    @PatchMapping("/{id}")
    public BoardResponse rename(@PathVariable UUID id,
                                @Valid @RequestBody UpdateBoardRequest req,
                                @AuthenticationPrincipal AuthPrincipal me) {
        return BoardResponse.from(boardService.rename(id, me.userId(), req.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthPrincipal me) {
        boardService.delete(id, me.userId());
        return ResponseEntity.noContent().build();
    }
}
