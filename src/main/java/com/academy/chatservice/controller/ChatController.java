package com.academy.chatservice.controller;

import com.academy.chatservice.model.*;
import com.academy.chatservice.model.WhiteboardAction;
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
import java.util.ArrayList;
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

    @PostMapping("/api/conversations")
    public ResponseEntity<ConversationSummaryDto> createConversation(
            @RequestBody(required = false) ConversationCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String title = request != null ? request.title() : null;
        return ResponseEntity.ok(chatService.createConversation(jwt.getSubject(), title));
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

            if (chatService.shouldForceExerciseBreakdown(prep)) {
                Object toolResult = chatService.executeExerciseBreakdownFallback(prep);
                String payload = objectMapper.writeValueAsString(toolResult);
                log.info("[TOOLS] emitting forced exercise_breakdown conversation_id={}", prep.conversationId());
                chatService.finalizeStream(prep.conversationId(), payload, List.of());
                sse(writer, payload);
                sse(writer, "{\"type\":\"done\"}");
                return;
            }

            boolean useRegisteredTools = llmClient.supportsToolCalling() && chatService.shouldUseRegisteredTools(prep);
            if (useRegisteredTools) {
                try {
                    var toolAwareResponse = chatService.generateWithRegisteredTools(prep.prompt());
                    if (toolAwareResponse.hasToolCalls()) {
                        var toolCall = toolAwareResponse.toolCalls().get(0);
                        Object toolResult = chatService.executeToolCall(toolCall, userEmail, prep.conversationId());

                        // Whiteboard actions: emit SSE action, then give LLM a second round to inject content
                        if (toolResult instanceof WhiteboardAction action) {
                            log.info("[TOOLS] emitting whiteboard action conversation_id={} tool={} action={}",
                                    prep.conversationId(), toolCall.name(), action.type());
                            sse(writer, objectMapper.writeValueAsString(
                                    java.util.Map.of("type", "action", "action", action)));

                            String actionContext = "\n\n[ACCIÓN EJECUTADA: " + toolCall.name() + "]\n"
                                    + objectMapper.writeValueAsString(action);

                            // If whiteboard was just opened, give LLM another chance to inject content
                            String roundTwoPrompt = prep.prompt() + actionContext;
                            if ("OPEN_WHITEBOARD".equals(action.type())) {
                                Object wbId = action.payload().get("whiteboardId");
                                roundTwoPrompt += "\n\nLa pizarra ya está abierta (whiteboardId=" + wbId
                                        + ", conversationId=" + prep.conversationId() + ")."
                                        + " Ahora inyectá el contenido completo usando inject_whiteboard_content."
                                        + " Fragmentá en bloques: TITLE, TEXT, STEP, FORMULA según corresponda."
                                        + " Después de inyectar, confirmá brevemente al estudiante.";
                                try {
                                    var injectResponse = chatService.generateWithRegisteredTools(roundTwoPrompt);
                                    if (injectResponse.hasToolCalls()) {
                                        var injectCall = injectResponse.toolCalls().get(0);
                                        Object injectResult = chatService.executeToolCall(injectCall, userEmail, prep.conversationId());
                                        if (injectResult instanceof WhiteboardAction injectAction) {
                                            log.info("[TOOLS] emitting inject action conversation_id={} tool={} action={}",
                                                    prep.conversationId(), injectCall.name(), injectAction.type());
                                            sse(writer, objectMapper.writeValueAsString(
                                                    java.util.Map.of("type", "action", "action", injectAction)));
                                            roundTwoPrompt += "\n\n[ACCIÓN EJECUTADA: " + injectCall.name() + "]\n"
                                                    + objectMapper.writeValueAsString(injectAction);
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("[TOOLS] Falló segundo round de tools para OPEN_WHITEBOARD: {}", e.getMessage());
                                }
                            }

                            try {
                                full.append(llmClient.generate(roundTwoPrompt
                                        + "\n\nRespondé al estudiante en una oración breve confirmando lo que hiciste. No repitas JSON.\n"));
                            } catch (Exception e) {
                                log.warn("Falló respuesta de confirmación para {}. Usando fallback.", toolCall.name());
                                full.append("OPEN_WHITEBOARD".equals(action.type())
                                        ? "Abrí la pizarra con el contenido. Podés verla a la derecha."
                                        : "Actualicé la pizarra con el contenido.");
                            }
                        } else if (shouldContinueWithTextResponse(toolCall.name())) {
                            String toolPayload = objectMapper.writeValueAsString(toolResult);
                            String followUpPrompt = prep.prompt()
                                    + "\n\n[RESULTADO DE TOOL: " + toolCall.name() + "]\n"
                                    + toolPayload
                                    + "\n\nRespondé ahora al estudiante en lenguaje natural usando este resultado. "
                                    + "No devuelvas JSON crudo. Si la pizarra contiene una ecuación matemática, razoná sobre esa ecuación y no sobre diagramas de flujo.\n";
                            try {
                                full.append(llmClient.generate(followUpPrompt));
                            } catch (Exception e) {
                                log.warn("Falló respuesta final luego de tool {}. Se devuelve fallback controlado: {}",
                                        toolCall.name(), e.getMessage());
                                full.append(buildToolFallbackResponse(toolCall.name(), toolResult));
                            }
                        } else {
                            String payload = objectMapper.writeValueAsString(toolResult);
                            log.info("[TOOLS] emitting direct tool event conversation_id={} tool={}",
                                    prep.conversationId(), toolCall.name());
                            chatService.finalizeStream(prep.conversationId(), payload, List.of());
                            sse(writer, payload);
                            sse(writer, "{\"type\":\"done\"}");
                            return;
                        }
                    }

                    if (toolAwareResponse.content() != null && !toolAwareResponse.content().isBlank()) {
                        full.append(toolAwareResponse.content());
                    }
                } catch (Exception e) {
                    log.warn("Falló llamada LLM con tools. Se devuelve fallback controlado conversation_id={}: {}",
                            prep.conversationId(), e.getMessage());
                    full.append(buildModelFallbackResponse(prep));
                }
            } else {
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
            }

            SuggestionsExtraction extracted = extractSuggestions(full.toString(), prep.userMessage());
            String cleanResponse = extracted.cleanResponse();
            List<String> suggestions = extracted.suggestions();

            chatService.finalizeStream(prep.conversationId(), cleanResponse, suggestions);

            // Patch client if marker leaked (split-token edge case)
            if (markerFound[0] && !sentContent.toString().stripTrailing().equals(cleanResponse)) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "replace", "text", cleanResponse)));
            } else if (useRegisteredTools && !cleanResponse.isBlank()) {
                sse(writer, objectMapper.writeValueAsString(Map.of("type", "chunk", "text", cleanResponse)));
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

    private boolean shouldContinueWithTextResponse(String toolName) {
        return "interpret_whiteboard".equals(toolName)
                || "summarize_whiteboard".equals(toolName)
                || "get_active_whiteboard".equals(toolName)
                || "get_exercise_whiteboard".equals(toolName);
    }

    private record SuggestionsExtraction(String cleanResponse, List<String> suggestions) {}

    private SuggestionsExtraction extractSuggestions(String rawResponse, String userMessage) {
        int markerIdx = rawResponse.indexOf("|||");
        if (markerIdx < 0) {
            return new SuggestionsExtraction(rawResponse, List.of());
        }

        String cleanResponse = rawResponse.substring(0, markerIdx).stripTrailing();
        String markerPayload = rawResponse.substring(markerIdx + 3).trim();
        List<String> suggestions = parseSuggestions(markerPayload);

        if (suggestions.isEmpty()) {
            suggestions = parseLooseSuggestions(rawResponse.substring(markerIdx));
        }
        if (suggestions.isEmpty()) {
            suggestions = fallbackSuggestions(userMessage);
        }
        suggestions = completeSuggestions(suggestions, userMessage);

        return new SuggestionsExtraction(cleanResponse, suggestions);
    }

    private List<String> parseSuggestions(String payload) {
        try {
            String candidate = payload;
            if (!candidate.startsWith("[")) {
                candidate = "[" + candidate + "]";
            }
            int end = candidate.lastIndexOf(']');
            if (end < 0) {
                return List.of();
            }
            String jsonArray = candidate.substring(0, end + 1);
            List<String> parsed = objectMapper.readValue(jsonArray,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return normalizeSuggestions(parsed);
        } catch (Exception e) {
            log.warn("No se pudo parsear bloque JSON de sugerencias: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseLooseSuggestions(String markerPayload) {
        String normalized = markerPayload
                .replace("|||", "\n")
                .replace("\r", "\n");
        List<String> candidates = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String item = stripSuggestionToken(line);
            if (item.contains(",")) {
                for (String part : item.split(",")) {
                    candidates.add(stripSuggestionToken(part));
                }
            } else {
                candidates.add(item);
            }
        }
        return normalizeSuggestions(candidates);
    }

    private List<String> normalizeSuggestions(List<String> rawSuggestions) {
        List<String> normalized = new ArrayList<>();
        for (String raw : rawSuggestions) {
            String item = stripSuggestionToken(raw);
            if (item.isBlank() || isInvalidSuggestion(item) || normalized.contains(item)) {
                continue;
            }
            normalized.add(item);
            if (normalized.size() == 3) {
                break;
            }
        }
        return normalized;
    }

    private List<String> completeSuggestions(List<String> suggestions, String userMessage) {
        List<String> completed = new ArrayList<>(normalizeSuggestions(suggestions));
        if (completed.size() == 3) {
            return completed;
        }
        for (String fallback : fallbackSuggestions(userMessage)) {
            String item = stripSuggestionToken(fallback);
            if (!item.isBlank() && !completed.contains(item)) {
                completed.add(item);
            }
            if (completed.size() == 3) {
                break;
            }
        }
        return completed;
    }

    private String stripSuggestionToken(String raw) {
        String item = raw == null ? "" : raw.trim();
        item = item.replace("`", "").replace("*", "").trim();
        item = item.replaceFirst("^\\[+", "").replaceFirst("\\]+$", "").trim();
        while ((item.startsWith("[") && item.endsWith("]"))
                || (item.startsWith("\"") && item.endsWith("\""))
                || (item.startsWith("'") && item.endsWith("'"))) {
            item = item.substring(1, item.length() - 1).trim();
        }
        item = item.replaceFirst("^[-*•\\d.)\\s]+", "").trim();
        item = item.replaceFirst("(?i)^(sugerencia|pregunta|accion|acción|opcion|opción)\\s*[:.-]\\s*", "").trim();
        return item;
    }

    private boolean isInvalidSuggestion(String suggestion) {
        String lower = suggestion.toLowerCase();
        return suggestion.contains("|")
                || suggestion.contains("`")
                || suggestion.contains("[")
                || suggestion.contains("]")
                || suggestion.equals("```")
                || suggestion.length() > 80
                || suggestion.split("\\s+").length < 2
                || suggestion.split("\\s+").length > 8
                || lower.contains("pregunta corta")
                || lower.contains("sugerencia real")
                || lower.contains("placeholder")
                || lower.contains("recursos adicionales")
                || lower.contains("pide más detalles")
                || lower.contains("no disponible")
                || lower.contains("sección gráfica")
                || lower.contains("seccion grafica")
                || lower.contains("diagrama necesario")
                || !isActionableSuggestion(lower);
    }

    private boolean isActionableSuggestion(String lowerSuggestion) {
        return lowerSuggestion.startsWith("ver ")
                || lowerSuggestion.startsWith("explicar ")
                || lowerSuggestion.startsWith("practicar ")
                || lowerSuggestion.startsWith("resolver ")
                || lowerSuggestion.startsWith("repasar ")
                || lowerSuggestion.startsWith("comparar ")
                || lowerSuggestion.startsWith("crear ")
                || lowerSuggestion.startsWith("hacer ")
                || lowerSuggestion.startsWith("analizar ")
                || lowerSuggestion.startsWith("revisar ")
                || lowerSuggestion.startsWith("mostrar ")
                || lowerSuggestion.startsWith("entender ")
                || lowerSuggestion.startsWith("aplicar ")
                || lowerSuggestion.startsWith("probar ")
                || lowerSuggestion.startsWith("preguntar ")
                || lowerSuggestion.startsWith("continuar ")
                || lowerSuggestion.startsWith("usar ")
                || lowerSuggestion.startsWith("definir ")
                || lowerSuggestion.startsWith("identificar ")
                || lowerSuggestion.startsWith("consultar ")
                || lowerSuggestion.startsWith("consultá ")
                || lowerSuggestion.startsWith("calcular ")
                || lowerSuggestion.startsWith("simplificar ")
                || lowerSuggestion.startsWith("completar ")
                || lowerSuggestion.startsWith("corregir ")
                || lowerSuggestion.startsWith("qué ")
                || lowerSuggestion.startsWith("que ")
                || lowerSuggestion.startsWith("cómo ")
                || lowerSuggestion.startsWith("como ")
                || lowerSuggestion.startsWith("cuál ")
                || lowerSuggestion.startsWith("cual ")
                || lowerSuggestion.startsWith("por qué ")
                || lowerSuggestion.startsWith("por que ");
    }

    private List<String> fallbackSuggestions(String userMessage) {
        String lower = userMessage == null ? "" : userMessage.toLowerCase();
        if (lower.contains("programación orientada a objetos") || lower.contains("objetos")) {
            return List.of("Ver un ejemplo en Java", "Explicar clases y objetos", "Practicar con un ejercicio");
        }
        if (lower.contains("derivada")) {
            return List.of("Ver un ejemplo resuelto", "Repasar la regla usada", "Practicar otro ejercicio");
        }
        return List.of("Ver un ejemplo", "Explicarlo paso a paso", "Hacer una pregunta práctica");
    }

    private String buildToolFallbackResponse(String toolName, Object toolResult) {
        if ("interpret_whiteboard".equals(toolName) && toolResult instanceof WhiteboardInterpretationResponse result) {
            StringBuilder sb = new StringBuilder();
            if ("math".equals(result.type()) && result.equation() != null && !result.equation().isBlank()) {
                sb.append("La pizarra contiene esta ecuación: ").append(result.equation()).append(".");
            } else {
                sb.append(result.semanticSummary() == null || result.semanticSummary().isBlank()
                        ? "No pude interpretar claramente la pizarra."
                        : result.semanticSummary());
            }
            if ("unknown".equals(result.type())) {
                sb.append(" Probá agregar un texto o una ecuación editable para que pueda analizarla mejor.");
            }
            return sb.toString();
        }
        return "Procesé la información de la pizarra, pero no pude generar una respuesta completa en este momento.";
    }

    private String buildModelFallbackResponse(ChatService.StreamPrep prep) {
        WhiteboardInterpretationResponse result = prep.whiteboardInterpretation();
        if (result != null) {
            // Detectar si el fallback es por 429 u otro error de OpenRouter
            String reason = result.reason() != null ? result.reason() : "";
            boolean isRateLimited = reason.contains("429") || reason.contains("RATE_LIMIT");

            StringBuilder sb = new StringBuilder();

            // Si hay contenido útil en la pizarra, priorizarlo
            if ("math".equals(result.type()) && result.equation() != null && !result.equation().isBlank()) {
                sb.append("La pizarra contiene esta ecuación: ").append(result.equation()).append(".\n");
            } else if (result.ocrText() != null && !result.ocrText().isBlank()) {
                sb.append("Texto detectado en la pizarra: ").append(result.ocrText()).append(".\n");
            } else if (result.semanticSummary() != null && !result.semanticSummary().isBlank()) {
                sb.append(result.semanticSummary()).append("\n");
            } else {
                sb.append("La pizarra tiene contenido visible pero no pude interpretarlo completamente.");
            }

            if (isRateLimited) {
                sb.append("\n\nEl modelo gratuito para interpretar imágenes está saturado en este momento. Podés intentar de nuevo en unos minutos o escribir la ecuación con la herramienta de texto.");
            } else {
                sb.append("\n\nEl modelo de interpretación visual tuvo un error temporal. Intentá de nuevo en unos segundos.");
            }
            return sb.toString();
        }
        return "El servicio de IA no está disponible en este momento. Intentá de nuevo en unos segundos.";
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
