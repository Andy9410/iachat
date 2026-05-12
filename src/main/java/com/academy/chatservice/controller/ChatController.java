package com.academy.chatservice.controller;

import com.academy.chatservice.model.*;
import com.academy.chatservice.service.ChatService;
import com.academy.chatservice.service.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, LLMClient llmClient, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
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

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request,
                                 @AuthenticationPrincipal String userEmail,
                                 HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");

        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                var prep = chatService.prepareStream(request, userEmail);
                emitter.send(SseEmitter.event().data(
                        objectMapper.writeValueAsString(Map.of("type", "meta", "conversationId", prep.conversationId()))
                ));
                var full = new StringBuilder();
                llmClient.generateStream(prep.prompt(), chunk -> {
                    full.append(chunk);
                    try {
                        emitter.send(SseEmitter.event().data(
                                objectMapper.writeValueAsString(Map.of("type", "chunk", "text", chunk))
                        ));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                chatService.finalizeStream(prep.conversationId(), full.toString());
                emitter.send(SseEmitter.event().data("{\"type\":\"done\"}"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Error en /chat/stream: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().data("{\"type\":\"error\"}"));
                } catch (Exception ignored) {}
                emitter.complete();
            }
        });
        return emitter;
    }
}
