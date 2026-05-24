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
import org.springframework.security.oauth2.jwt.Jwt;

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
                                             @AuthenticationPrincipal Jwt jwt) {

        String userEmail = jwt.getSubject();
        String firstName = (String) jwt.getClaims().get("firstName");

        return ResponseEntity.ok(chatService.process(request, userEmail,firstName));
    }

    @GetMapping("/api/conversations")
    public ResponseEntity<List<ConversationSummaryDto>> myConversations(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(chatService.getMyConversations(jwt.getSubject()));
    }

    @GetMapping("/api/conversations/{id}/messages")
    public ResponseEntity<MessagePageDto> conversationMessages(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(required = false) Long before,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(chatService.getConversationMessages(id, jwt.getSubject(), limit, before));
    }

    @PostMapping("/api/conversations/{id}/title")
    public ResponseEntity<Map<String, String>> generateTitle(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        String title = chatService.generateTitle(id, jwt.getSubject());
        return ResponseEntity.ok(Map.of("title", title));
    }

    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        chatService.deleteConversation(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/chat/stream")
    public void chatStream(@Valid @RequestBody ChatRequest request,
                           @AuthenticationPrincipal Jwt jwt,
                           HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");


        String userEmail = jwt.getSubject();
        String firstName = jwt.getClaim("firstName");


        PrintWriter writer = response.getWriter();
        try {
            var prep = chatService.prepareStream(request, userEmail, firstName);
            sse(writer, objectMapper.writeValueAsString(
                    Map.of("type", "meta", "conversationId", prep.conversationId())));

            if (prep.clarificationMessage() != null) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", prep.clarificationMessage())));
                sse(writer, "{\"type\":\"done\"}");
                return;
            }

            var full = new StringBuilder();
            var sentContent = new StringBuilder();
            boolean[] markerFound = {false};

            llmClient.generateStream(prep.prompt(), chunk -> {
                if (markerFound[0]) {
                    full.append(chunk);
                    return;
                }
                int prevLen = full.length();
                full.append(chunk);

                int markerIdx = full.indexOf("|||");
                String toSend;
                if (markerIdx >= 0) {
                    markerFound[0] = true;
                    // Only forward text before the marker; if marker spans chunks, send nothing new
                    toSend = markerIdx >= prevLen ? full.substring(prevLen, markerIdx) : "";
                } else {
                    toSend = chunk;
                }

                if (!toSend.isEmpty()) {
                    sentContent.append(toSend);
                    try {
                        sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", toSend)));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            // Extract ||| suggestions block appended by the LLM
            String rawResponse = full.toString();
            String cleanResponse = rawResponse;
            List<String> suggestions = List.of();
            try {
                int markerIdx = rawResponse.lastIndexOf("|||");
                if (markerIdx >= 0) {
                    String after = rawResponse.substring(markerIdx + 3).trim();
                    // Normalize: wrap in [] if the LLM omitted the brackets
                    if (!after.startsWith("[")) after = "[" + after + "]";
                    int end = after.lastIndexOf(']');
                    if (end >= 0) {
                        String jsonArray = after.substring(0, end + 1);
                        suggestions = objectMapper.readValue(jsonArray,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        cleanResponse = rawResponse.substring(0, markerIdx).stripTrailing();
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudo parsear bloque de sugerencias: {}", e.getMessage());
            }

            chatService.finalizeStream(prep.conversationId(), cleanResponse);

            // Patch client if marker leaked (split-token edge case)
            if (markerFound[0] && !sentContent.toString().stripTrailing().equals(cleanResponse)) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "replace", "text", cleanResponse)));
            }

            if (!suggestions.isEmpty()) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "suggestions", "questions", suggestions)));
            }

            if (!prep.docChunks().isEmpty()) {
                var citations = prep.docChunks().stream()
                        .filter(c -> {
                            // deduplicate by (filename, pageNumber)
                            return true;
                        })
                        .map(c -> Map.of(
                                "filename", c.filename(),
                                "pageNumber", c.pageNumber() != null ? c.pageNumber() : 0,
                                "documentId", c.documentId() != null ? c.documentId() : 0
                        ))
                        .distinct()
                        .toList();
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "sources", "citations", citations)));
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
            @AuthenticationPrincipal Jwt jwt) {
        chatService.setActiveDocument(id, body.get("documentId"), jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/conversations/{id}/archived-context")
    public ResponseEntity<Map<String, Object>> getArchivedContext(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(chatService.getArchivedContext(id, jwt.getSubject()));
    }
}
