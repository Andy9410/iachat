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
    private static final String DEFAULT_LESSON_MODEL = "nex-agi/nex-n2-pro:free";

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
        return "Sos un profesor. Creá una lección visual para pizarra basada en este intercambio:\n\n"
                + "PREGUNTA: " + userMessage + "\n\n"
                + "EXPLICACIÓN: " + assistantMessage + "\n\n"
                + """
                Generá 3 a 5 pasos usando el contenido real de la PREGUNTA y EXPLICACIÓN de arriba.
                IMPORTANTE: El "text" de cada elemento debe contener contenido real del tema, NO ejemplos genéricos.

                Coordenadas: x entre 40-500, y entre 40-180. Cada paso debe tener al menos 1 elemento.

                Para algoritmos usá: rect (proceso), diamond (decisión), arrow (flujo), text (pseudocódigo).
                Para matemática usá: equation (fórmulas reales del tema), text (pasos).

                Formato JSON requerido:
                {
                  "title": "<título real del tema>",
                  "steps": [
                    {
                      "id": "step-1",
                      "title": "<título del paso>",
                      "explanation": "<explicación breve>",
                      "elements": [
                        { "id": "e1", "type": "rect", "x": 50, "y": 60, "width": 200, "height": 60, "text": "<texto real>" }
                      ]
                    }
                  ]
                }

                Respondé SOLO con JSON. Sin markdown.
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
                        int elIndex = 0;
                        for (JsonNode el : elementsNode) {
                            Map<String, Object> element = objectMapper.convertValue(el, Map.class);
                            element.put("id", UUID.randomUUID().toString());
                            // Ensure x/y are always valid numbers
                            if (!(element.get("x") instanceof Number)) element.put("x", 60 + elIndex * 20);
                            if (!(element.get("y") instanceof Number)) element.put("y", 60 + elIndex * 30);
                            // Ensure type is a valid whiteboard element type
                            String type = element.get("type") instanceof String t ? t : "text";
                            if (!java.util.Set.of("text","equation","rect","circle","diamond","arrow","path").contains(type)) {
                                element.put("type", "text");
                            }
                            elements.add(element);
                            elIndex++;
                        }
                    }

                    // Garantizar al menos un elemento por paso
                    if (elements.isEmpty()) {
                        Map<String, Object> fallback = new java.util.LinkedHashMap<>();
                        fallback.put("id", UUID.randomUUID().toString());
                        fallback.put("type", "text");
                        fallback.put("x", 60);
                        fallback.put("y", 80);
                        fallback.put("text", stepTitle);
                        fallback.put("stroke", "#0f172a");
                        elements.add(fallback);
                    }

                    applyAutoLayout(elements);
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

    /**
     * Redistributes element positions so nothing overlaps.
     * Nodes (rect/circle/diamond) stack vertically centered.
     * Arrows are placed between nodes pointing downward.
     * Text/equations are placed to the right of their preceding node.
     */
    private void applyAutoLayout(List<Map<String, Object>> elements) {
        if (elements.size() <= 1) {
            if (!elements.isEmpty()) {
                elements.get(0).put("x", 180);
                elements.get(0).put("y", 90);
            }
            return;
        }

        // Separate nodes from arrows
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> arrows = new ArrayList<>();

        for (Map<String, Object> el : elements) {
            String type = String.valueOf(el.getOrDefault("type", "text"));
            if ("arrow".equals(type)) {
                arrows.add(el);
            } else {
                nodes.add(el);
            }
        }

        // Layout nodes vertically: start at y=50, spacing 80px, centered at x=180
        int nodeY = 50;
        int nodeX = 180;
        int nodeSpacing = 80;

        for (Map<String, Object> node : nodes) {
            String type = String.valueOf(node.getOrDefault("type", "text"));
            int width = node.get("width") instanceof Number w ? w.intValue() : defaultWidth(type);
            node.put("x", nodeX - width / 2);
            node.put("y", nodeY);
            nodeY += nodeSpacing;
        }

        // Layout arrows between nodes (pointing downward)
        if (!arrows.isEmpty() && nodes.size() >= 2) {
            int arrowY = 50 + nodeSpacing - 30; // midpoint between first two nodes
            for (Map<String, Object> arrow : arrows) {
                arrow.put("x", nodeX - 4);
                arrow.put("y", arrowY);
                arrow.put("width", 0);
                arrow.put("height", 30);
                arrowY += nodeSpacing;
            }
        } else if (!arrows.isEmpty()) {
            // Only one node or no nodes: just hide arrows off-screen (they add no value)
            for (Map<String, Object> arrow : arrows) {
                arrow.put("x", -999);
                arrow.put("y", -999);
            }
        }
    }

    private int defaultWidth(String type) {
        return switch (type) {
            case "rect" -> 200;
            case "circle", "diamond" -> 140;
            case "equation" -> 160;
            default -> 0; // text has no explicit width
        };
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
