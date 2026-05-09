package com.academy.chatservice.controller;

import com.academy.chatservice.model.*;
import com.academy.chatservice.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
                                             @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(chatService.process(request, userEmail));
    }

    @GetMapping("/api/conversations")
    public ResponseEntity<List<ConversationSummaryDto>> myConversations(
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(chatService.getMyConversations(userEmail));
    }

    @GetMapping("/api/conversations/{id}/messages")
    public ResponseEntity<List<MessageDto>> conversationMessages(
            @PathVariable Long id,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(chatService.getConversationMessages(id, userEmail));
    }

    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long id,
            @AuthenticationPrincipal String userEmail) {
        chatService.deleteConversation(id, userEmail);
        return ResponseEntity.noContent().build();
    }
}
