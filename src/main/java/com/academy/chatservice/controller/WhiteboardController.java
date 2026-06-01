package com.academy.chatservice.controller;

import com.academy.chatservice.model.WhiteboardDto;
import com.academy.chatservice.model.WhiteboardRequest;
import com.academy.chatservice.service.WhiteboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WhiteboardController {

    private final WhiteboardService whiteboardService;

    public WhiteboardController(WhiteboardService whiteboardService) {
        this.whiteboardService = whiteboardService;
    }

    @GetMapping("/api/conversations/{conversationId}/whiteboards")
    public ResponseEntity<List<WhiteboardDto>> list(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.list(conversationId, jwt.getSubject()));
    }

    @GetMapping("/api/conversations/{conversationId}/whiteboards/active")
    public ResponseEntity<WhiteboardDto> active(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.active(conversationId, jwt.getSubject()));
    }

    @PostMapping("/api/conversations/{conversationId}/whiteboards")
    public ResponseEntity<WhiteboardDto> create(
            @PathVariable Long conversationId,
            @RequestBody WhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.createOrGet(conversationId, jwt.getSubject(), request));
    }

    @PutMapping("/api/whiteboards/{whiteboardId}")
    public ResponseEntity<WhiteboardDto> update(
            @PathVariable String whiteboardId,
            @RequestBody WhiteboardRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(whiteboardService.update(whiteboardId, jwt.getSubject(), request));
    }

    @DeleteMapping("/api/whiteboards/{whiteboardId}")
    public ResponseEntity<Void> delete(
            @PathVariable String whiteboardId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        whiteboardService.delete(whiteboardId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
