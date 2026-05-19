package com.academy.chatservice.controller;

import com.academy.chatservice.model.*;
import com.academy.chatservice.service.ChatService;
import com.academy.chatservice.service.LLMClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

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

    @PostMapping("/api/conversations/{id}/title")
    public ResponseEntity<Map<String, String>> generateTitle(
            @PathVariable Long id,
            @AuthenticationPrincipal String userEmail) {
        String title = chatService.generateTitle(id, userEmail);
        return ResponseEntity.ok(Map.of("title", title));
    }

    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long id,
            @AuthenticationPrincipal String userEmail) {
        chatService.deleteConversation(id, userEmail);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat/stream")
    public void chatStream(@Valid @RequestBody ChatRequest request,
                           @AuthenticationPrincipal String userEmail,
                           HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = response.getWriter();
        try {
            var prep = chatService.prepareStream(request, userEmail);
            sse(writer, objectMapper.writeValueAsString(
                    Map.of("type", "meta", "conversationId", prep.conversationId())));

            if (prep.clarificationMessage() != null) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", prep.clarificationMessage())));
                sse(writer, "{\"type\":\"done\"}");
                return;
            }

            var full = new StringBuilder();
            llmClient.generateStream(prep.prompt(), chunk -> {
                full.append(chunk);
                try {
                    sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", chunk)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            chatService.finalizeStream(prep.conversationId(), full.toString());

            if (!prep.docChunks().isEmpty()) {
                var files = prep.docChunks().stream()
                        .map(c -> c.filename())
                        .distinct()
                        .toList();
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "sources", "files", files)));
            }

            sse(writer, "{\"type\":\"done\"}");
        } catch (Exception e) {
            log.error("Error en /chat/stream: {}", e.getMessage(), e);
            Sentry.captureException(e);
            try { sse(writer, "{\"type\":\"error\"}"); } catch (Exception ignored) {}
        }
    }

    private void sse(PrintWriter writer, String data) throws IOException {
        writer.write("data:" + data + "\n\n");
        writer.flush();
        if (writer.checkError()) throw new IOException("client disconnected");
    }

    @PostMapping("/api/conversations/{id}/active-document")
    public ResponseEntity<Void> setActiveDocument(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body,
            @AuthenticationPrincipal String userEmail) {
        chatService.setActiveDocument(id, body.get("documentId"), userEmail);
        return ResponseEntity.noContent().build();
    }
}
