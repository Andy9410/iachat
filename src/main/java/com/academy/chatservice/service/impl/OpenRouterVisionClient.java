package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.OpenRouterProperties;
import com.academy.chatservice.model.WhiteboardInterpretationResponse;
import com.academy.chatservice.service.VisionModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OpenRouterVisionClient implements VisionModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterVisionClient.class);
    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";
    private static final String DEFAULT_FREE_VISION_MODEL = "openai/gpt-4o-mini";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenRouterProperties props;

    public OpenRouterVisionClient(ObjectMapper objectMapper, OpenRouterProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public WhiteboardInterpretationResponse interpretWhiteboardImage(String imageBase64, String whiteboardId) {
        long startTime = System.currentTimeMillis();
        int imageSize = imageBase64 != null ? imageBase64.length() : 0;
        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Enviando a OpenRouter vision. imageSize={}bytes", whiteboardId, imageSize);

        try {
            var body = objectMapper.writeValueAsString(buildVisionRequestBody(imageBase64));
            log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} Request body size={}bytes", whiteboardId, body.length());

            var response = httpClient.send(buildHttpRequest(body), HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("[WHITEBOARD_INTERPRET] whiteboardId={} OpenRouter response: status={} elapsed={}ms",
                    whiteboardId, response.statusCode(), elapsed);

            if (response.statusCode() == 429) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "";
                log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OPENROUTER_429 OpenRouter rate limit. Error: {} elapsedMs={}",
                        whiteboardId, errorBody, elapsed);
                return new WhiteboardInterpretationResponse(
                        "unknown",
                        whiteboardId,
                        null,
                        null,
                        null,
                        null,
                        "",
                        "",
                        "No se pudo interpretar la pizarra porque el modelo gratuito no está disponible temporalmente.",
                        0.0,
                        "OPENROUTER_429"
                );
            }

            if (response.statusCode() == 400) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(500, response.body().length())) : "";
                log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OPENROUTER_400 OpenRouter bad request: {} elapsedMs={}",
                        whiteboardId, errorBody, elapsed);
                return reasonWhiteboard(whiteboardId, "OPENROUTER_400");
            }

            if (response.statusCode() != 200) {
                String errorBody = response.body() != null ? response.body().substring(0, Math.min(500, response.body().length())) : "";
                log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OPENROUTER_{} OpenRouter error: {}",
                        whiteboardId, response.statusCode(), errorBody);
                return reasonWhiteboard(whiteboardId, "OPENROUTER_" + response.statusCode());
            }

            String rawBody = response.body();
            log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} OpenRouter respuesta cruda: {}", whiteboardId,
                    rawBody != null ? rawBody.substring(0, Math.min(300, rawBody.length())) : "null");

            JsonNode json = objectMapper.readTree(rawBody);
            String content = json.path("choices").get(0).path("message").path("content").asText();

            log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} OpenRouter content crudo: {}", whiteboardId,
                    content != null ? content.substring(0, Math.min(300, content.length())) : "null");

            return parseVisionInterpretation(content, whiteboardId);

        } catch (java.net.http.HttpTimeoutException e) {
            log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OPENROUTER_TIMEOUT Timeout en OpenRouter: {}", whiteboardId, e.getMessage());
            return reasonWhiteboard(whiteboardId, "OPENROUTER_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=OPENROUTER_INTERRUPTED Interpretación visual interrumpida", whiteboardId);
            return reasonWhiteboard(whiteboardId, "OPENROUTER_INTERRUPTED");
        } catch (Exception e) {
            log.error("[WHITEBOARD_INTERPRET] whiteboardId={} reason=VISION_CLIENT_ERROR Error interpretando pizarra con OpenRouter: {}", whiteboardId, e.getMessage(), e);
            return reasonWhiteboard(whiteboardId, "VISION_CLIENT_ERROR");
        }
    }

    private Map<String, Object> buildVisionRequestBody(String imageBase64) {
        var prompt = """
                Sos un intérprete de pizarras educativas.

                Identificá PRIMERO qué tipo de contenido predomina en la pizarra.

                Taxonomía de tipos (usá el más específico que corresponda):
                - "equation": Contiene ecuaciones o expresiones matemáticas explícitas (con =, +, -, *, /, ^, dy/dx, etc.)
                - "graph": Contiene ejes cartesianos (líneas horizontal y vertical), curvas, funciones graficadas
                - "geometry": Contiene figuras geométricas (triángulos, círculos, polígonos, ángulos)
                - "algorithm": Contiene pseudocódigo, estructuras de control (if, while, for, inicio, fin, función)
                - "flowchart": Diagramas con cajas, rombos, flechas y nodos conectados
                - "text": Texto libre, apuntes, definiciones, frases
                - "unknown": Solo cuando la imagen está completamente vacía o en blanco

                Para determinar si es GRAPH, buscá:
                - Una línea horizontal larga (eje X)
                - Una línea vertical larga (eje Y)
                - Ambas se cruzan formando un sistema de ejes
                - Curvas o rectas sobre esos ejes

                Si hay ejes cartesianos o curvas, usá "graph".
                NO clasifiques como "math" si el contenido principal es una gráfica con ejes.

                Respondé únicamente JSON válido:

                {
                  "type": "equation | graph | geometry | algorithm | flowchart | text | unknown",
                  "ocrText": "",
                  "equation": "",
                  "semanticSummary": "",
                  "confidence": 0.0,
                  "classificationReason": ""
                }

                Reglas:
                - PRIORIDAD 1: Si hay ejes cartesianos o curvas sobre ejes, type = "graph".
                - PRIORIDAD 2: Si hay ecuaciones explícitas con =, +, -, dy/dx, type = "equation".
                - PRIORIDAD 3: Si hay figuras geométricas (triángulos, círculos, polígonos), type = "geometry".
                - PRIORIDAD 4: Si hay inicio, fin, leer, mostrar, mientras o si, type = "algorithm".
                - PRIORIDAD 5: Si hay cajas, flechas o nodos conectados, type = "flowchart".
                - PRIORIDAD 6: Si hay texto libre legible, type = "text".
                - NUNCA devuelvas "equation" si el contenido principal es una gráfica con ejes.
                - NUNCA devuelvas unknown si ves contenido visible en la pizarra.
                - En classificationReason indicá por qué elegiste el tipo, ej: "GRAPH_AXES_DETECTED", "MATH_SYMBOLS_DETECTED", "TEXT_CONTENT".
                - No inventes contenido que no veas.
                """;
        var content = List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", imageBase64))
        );
        var messages = List.of(Map.of("role", "user", "content", content));
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", visionModelName());
        body.put("messages", messages);
        body.put("stream", false);
        return body;
    }

    private WhiteboardInterpretationResponse parseVisionInterpretation(String content, String whiteboardId) {
        try {
            String json = extractJsonObject(content);
            JsonNode parsed = objectMapper.readTree(json);
            String type = normalizeVisionType(parsed.path("type").asText("unknown"));
            String ocrText = parsed.path("ocrText").asText("");
            String equation = parsed.path("equation").asText(null);
            if (equation != null && equation.isBlank()) equation = null;
            String summary = parsed.path("semanticSummary").asText("");
            double confidence = Math.max(0.0, Math.min(1.0, parsed.path("confidence").asDouble(0.0)));
            if (summary.isBlank()) {
                summary = "No se pudo interpretar claramente la pizarra.";
            }
            return new WhiteboardInterpretationResponse(
                    type,
                    whiteboardId,
                    null,
                    null,
                    null,
                    equation,
                    ocrText,
                    "",
                    summary,
                    confidence
            );
        } catch (Exception e) {
            log.warn("[WHITEBOARD_INTERPRET] whiteboardId={} No se pudo parsear JSON de visión: {}", whiteboardId, content, e);
            return unknownWhiteboard(whiteboardId);
        }
    }

    private String extractJsonObject(String content) {
        String raw = content == null ? "" : content.trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private String normalizeVisionType(String type) {
        return switch (type) {
            case "equation" -> "math";
            case "math", "graph", "geometry", "algorithm", "flowchart", "text" -> type;
            default -> "unknown";
        };
    }

    private WhiteboardInterpretationResponse unknownWhiteboard(String whiteboardId) {
        return reasonWhiteboard(whiteboardId, "VISION_FAILED");
    }

    private WhiteboardInterpretationResponse reasonWhiteboard(String whiteboardId, String reason) {
        return new WhiteboardInterpretationResponse(
                "unknown",
                whiteboardId,
                null,
                null,
                null,
                null,
                "",
                "",
                "No se pudo interpretar claramente la pizarra.",
                0.0,
                reason
        );
    }

    private HttpRequest buildHttpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + COMPLETIONS_PATH))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + whiteboardApiKey())
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String visionModelName() {
        return freeModel(props.visionModel(), DEFAULT_FREE_VISION_MODEL);
    }

    private String whiteboardApiKey() {
        String whiteboardKey = normalizedKey(props.whiteboardApiKey());
        if (!whiteboardKey.isBlank()) {
            return whiteboardKey;
        }
        return normalizedKey(props.apiKey());
    }

    private String normalizedKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    private String freeModel(String model, String fallback) {
        if (model == null || model.isBlank()) return fallback;
        return model.trim();
    }
}
