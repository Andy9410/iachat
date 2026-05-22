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


        log.info("JWT subject: {}", jwt.getSubject());
        log.info("JWT claims keys: {}", jwt.getClaims().keySet());
        log.info("JWT claims map: {}", jwt.getClaims());

        String userEmail = jwt.getSubject();
        String firstName = (String) jwt.getClaims().get("firstName");

        log.info("userEmail={}, firstName={}", userEmail, firstName);


        log.info("userEmail={}, firstName={}", userEmail, firstName);

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
            llmClient.generateStream(prep.prompt(), chunk -> {
                full.append(chunk);
                try {
                    sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", chunk)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            chatService.finalizeStream(prep.conversationId(), full.toString());

            try {
                String userQuestion = request.message();
                String assistantAnswer = full.toString();
                String suggestionsPrompt =
                    "Basándote en la conversación y en la última respuesta del tutor, generá exactamente 3 preguntas de seguimiento cortas que el estudiante podría hacerse. " +
                    "Respondé ÚNICAMENTE con un array JSON de strings. Ejemplo: [\"¿Podés mostrarme un ejemplo?\",\"¿Cómo se aplica esto en código?\",\"¿Cuál es la diferencia con X?\"]\n\n" +
                    "Última pregunta del estudiante: " + userQuestion + "\n" +
                    "Última respuesta del tutor: " + assistantAnswer;
                String suggestionsRaw = llmClient.generate(suggestionsPrompt);
                // Extract JSON array from response (may have surrounding whitespace or text)
                int start = suggestionsRaw.indexOf('[');
                int end = suggestionsRaw.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    String jsonArray = suggestionsRaw.substring(start, end + 1);
                    List<String> questions = objectMapper.readValue(jsonArray,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    if (questions != null && !questions.isEmpty()) {
                        sse(writer, objectMapper.writeValueAsString(
                            Map.of("type", "suggestions", "questions", questions)));
                    }
                }
            } catch (Exception e) {
                log.warn("No se pudieron generar sugerencias de seguimiento: {}", e.getMessage());
            }

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
            @AuthenticationPrincipal Jwt jwt) {
        chatService.setActiveDocument(id, body.get("documentId"), jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
