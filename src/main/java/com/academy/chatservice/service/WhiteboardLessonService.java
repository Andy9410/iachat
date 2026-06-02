package com.academy.chatservice.service;

import com.academy.chatservice.config.OpenRouterProperties;
import com.academy.chatservice.model.LessonStepDto;
import com.academy.chatservice.model.WhiteboardLessonRequest;
import com.academy.chatservice.model.WhiteboardLessonResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WhiteboardLessonService {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardLessonService.class);
    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";
    private static final String DEFAULT_LESSON_MODEL = "google/gemma-3-12b-it:free";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenRouterProperties props;

    public WhiteboardLessonService(ObjectMapper objectMapper, OpenRouterProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    public WhiteboardLessonResponse generate(WhiteboardLessonRequest request, String userEmail) {
        long start = System.currentTimeMillis();
        log.info("[WHITEBOARD_LESSON] user={} conversationId={} Generando lección", userEmail, request.conversationId());

        try {
            String body = objectMapper.writeValueAsString(buildRequestBody(request));
            HttpRequest httpRequest = buildHttpRequest(body);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            log.info("[WHITEBOARD_LESSON] user={} status={} elapsed={}ms", userEmail, response.statusCode(), elapsed);

            if (response.statusCode() == 429) {
                log.warn("[WHITEBOARD_LESSON] user={} reason=RATE_LIMIT", userEmail);
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Modelo no disponible temporalmente. Intentá de nuevo.");
            }

            if (response.statusCode() != 200) {
                log.error("[WHITEBOARD_LESSON] user={} reason=HTTP_{} body={}", userEmail, response.statusCode(),
                        truncate(response.body(), 300));
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo generar la lección.");
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.path("choices").get(0).path("message").path("content").asText();

            log.debug("[WHITEBOARD_LESSON] user={} rawContent={}", userEmail, truncate(content, 500));

            return parseLesson(content, userEmail);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("[WHITEBOARD_LESSON] user={} reason=TIMEOUT", userEmail);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "El modelo tardó demasiado. Intentá de nuevo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Generación interrumpida.");
        } catch (Exception e) {
            log.error("[WHITEBOARD_LESSON] user={} reason=ERROR {}", userEmail, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar la lección.");
        }
    }

    private Map<String, Object> buildRequestBody(WhiteboardLessonRequest request) {
        String userMsg = request.userMessage() != null ? request.userMessage() : "";
        String assistantMsg = request.assistantMessage() != null ? request.assistantMessage() : "";

        String prompt = buildPrompt(userMsg, assistantMsg);

        var messages = List.of(Map.of("role", "user", "content", prompt));
        var body = new LinkedHashMap<String, Object>();
        body.put("model", lessonModelName());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.4);
        return body;
    }

    private String buildPrompt(String userMessage, String assistantMessage) {
        return """
                Sos un profesor de matemática y programación.
                Generá una explicación visual paso a paso para una pizarra interactiva.

                PREGUNTA DEL USUARIO:
                """ + userMessage + """

                RESPUESTA DEL ASISTENTE:
                """ + assistantMessage + """

                INSTRUCCIONES:
                - Generá entre 3 y 6 pasos.
                - Cada paso representa UNA acción conceptual.
                - Cada paso es INDEPENDIENTE (muestra la imagen completa del concepto en ese paso).
                - El canvas mide aproximadamente 600 de ancho × 380 de alto (píxeles).
                - Distribuí los elementos para que sean legibles y no se superpongan.
                - Usá coordenadas realistas: x entre 40 y 560, y entre 40 y 340.
                - Para ecuaciones largas, usá y >= 100 para dejar espacio al título.

                TIPOS DE ELEMENTOS:
                - "text": { "type": "text", "x": ..., "y": ..., "text": "...", "stroke": "#0f172a" }
                - "equation": { "type": "equation", "x": ..., "y": ..., "text": "...", "stroke": "#0f172a" }
                - "rect": { "type": "rect", "x": ..., "y": ..., "width": 160, "height": 60, "text": "...", "stroke": "#0f172a", "fill": "#ffffff" }
                - "circle": { "type": "circle", "x": ..., "y": ..., "width": 120, "height": 60, "text": "...", "stroke": "#0f172a", "fill": "#ffffff" }
                - "diamond": { "type": "diamond", "x": ..., "y": ..., "width": 140, "height": 72, "text": "...", "stroke": "#0f172a", "fill": "#ffffff" }
                - "arrow": { "type": "arrow", "x": ..., "y": ..., "width": 120, "height": 0, "stroke": "#0f172a" }

                EJEMPLO DE FORMATO DE RESPUESTA (seguí exactamente este JSON):
                {
                  "title": "Resolver ecuación lineal",
                  "steps": [
                    {
                      "id": "step-1",
                      "title": "Identificar la ecuación",
                      "explanation": "Observamos la ecuación original.",
                      "elements": [
                        { "id": "el-1-1", "type": "equation", "x": 220, "y": 140, "text": "2x + 3 = 4", "stroke": "#0f172a" }
                      ]
                    },
                    {
                      "id": "step-2",
                      "title": "Restar 3 en ambos lados",
                      "explanation": "Restamos 3 para aislar el término con x.",
                      "elements": [
                        { "id": "el-2-1", "type": "equation", "x": 220, "y": 120, "text": "2x + 3 - 3 = 4 - 3", "stroke": "#0f172a" },
                        { "id": "el-2-2", "type": "equation", "x": 250, "y": 180, "text": "2x = 1", "stroke": "#0f172a" }
                      ]
                    }
                  ]
                }

                Respondé SOLO con JSON válido. Sin markdown, sin texto antes o después del JSON.
                """;
    }

    private WhiteboardLessonResponse parseLesson(String content, String userEmail) {
        try {
            String json = extractJsonObject(content);
            JsonNode root = objectMapper.readTree(json);

            String title = root.path("title").asText("Explicación paso a paso");
            List<LessonStepDto> steps = new ArrayList<>();

            JsonNode stepsNode = root.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    String id = stepNode.path("id").asText(UUID.randomUUID().toString());
                    String stepTitle = stepNode.path("title").asText("Paso");
                    String explanation = stepNode.path("explanation").asText("");

                    List<Map<String, Object>> elements = new ArrayList<>();
                    JsonNode elementsNode = stepNode.path("elements");
                    if (elementsNode.isArray()) {
                        for (JsonNode el : elementsNode) {
                            Map<String, Object> element = objectMapper.convertValue(el, Map.class);
                            element.put("id", UUID.randomUUID().toString());
                            elements.add(element);
                        }
                    }

                    steps.add(new LessonStepDto(id, stepTitle, explanation, elements));
                }
            }

            if (steps.isEmpty()) {
                log.warn("[WHITEBOARD_LESSON] user={} LLM devolvió steps vacío. Content={}", userEmail, truncate(content, 300));
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No se pudo generar la lección: el modelo no devolvió pasos.");
            }

            log.info("[WHITEBOARD_LESSON] user={} steps={} title='{}'", userEmail, steps.size(), title);
            return new WhiteboardLessonResponse(title, steps);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[WHITEBOARD_LESSON] user={} Error parseando lección: {} content={}", userEmail, e.getMessage(), truncate(content, 500));
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El modelo devolvió una respuesta inválida.");
        }
    }

    private String extractJsonObject(String content) {
        String raw = content == null ? "" : content.trim();
        // Strip markdown code fences if present
        if (raw.startsWith("```")) {
            int start = raw.indexOf('\n');
            int end = raw.lastIndexOf("```");
            if (start >= 0 && end > start) {
                raw = raw.substring(start + 1, end).trim();
            }
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private HttpRequest buildHttpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + COMPLETIONS_PATH))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolvedApiKey())
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String lessonModelName() {
        String model = props.lessonModel();
        if (model == null || model.isBlank()) return DEFAULT_LESSON_MODEL;
        return model.trim();
    }

    private String resolvedApiKey() {
        String key = props.whiteboardApiKey();
        if (key != null && !key.isBlank()) return key.trim();
        key = props.apiKey();
        return key != null ? key.trim() : "";
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
