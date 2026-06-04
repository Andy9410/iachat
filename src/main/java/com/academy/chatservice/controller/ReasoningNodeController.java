package com.academy.chatservice.controller;

import com.academy.chatservice.model.ReasoningNodeDto;
import com.academy.chatservice.service.ReasoningNodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ReasoningNodeController {

    private final ReasoningNodeService reasoningNodeService;

    public ReasoningNodeController(ReasoningNodeService reasoningNodeService) {
        this.reasoningNodeService = reasoningNodeService;
    }

    /** Returns the full reasoning tree for a conversation, ordered by level + orderIndex. */
    @GetMapping("/api/conversations/{conversationId}/reasoning/tree")
    public ResponseEntity<List<ReasoningNodeDto>> getTree(
            @PathVariable Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(reasoningNodeService.getTree(conversationId, jwt.getSubject()));
    }

    /** Creates a single reasoning node (also callable directly from the frontend). */
    @PostMapping("/api/conversations/{conversationId}/reasoning/nodes")
    public ResponseEntity<ReasoningNodeDto> create(
            @PathVariable Long conversationId,
            @RequestBody ReasoningNodeDto request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(reasoningNodeService.create(
                conversationId,
                request.whiteboardId(),
                request.parentNodeId(),
                request.nodeType(),
                request.title(),
                request.description(),
                request.status(),
                request.orderIndex(),
                jwt.getSubject()
        ));
    }

    /** Updates only the status of an existing node. */
    @PatchMapping("/api/reasoning/nodes/{nodeId}/status")
    public ResponseEntity<ReasoningNodeDto> updateStatus(
            @PathVariable Long nodeId,
            @RequestBody Map<String, String> body,
            @RequestParam Long conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(reasoningNodeService.updateStatus(
                nodeId, conversationId, body.get("status"), jwt.getSubject()));
    }
}
