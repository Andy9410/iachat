package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.OpenRouterProperties;
import com.academy.chatservice.model.WhiteboardInterpretationResponse;
import com.academy.chatservice.model.tools.LLMToolResponse;
import com.academy.chatservice.model.tools.ToolCall;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.LLMClient;
import com.academy.chatservice.service.openrouter.OpenRouterApiException;
import com.academy.chatservice.service.openrouter.OpenRouterModelRouter;
import com.academy.chatservice.service.openrouter.OpenRouterRequestExecutor;
import com.academy.chatservice.service.openrouter.OpenRouterUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openrouter")
public class OpenRouterLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLLMClient.class);
    private static final String DEFAULT_FREE_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free";
    private static final String DEFAULT_FREE_VISION_MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free";

    private final ObjectMapper objectMapper;
    private final OpenRouterProperties props;
    private final OpenRouterRequestExecutor requestExecutor;
    private final OpenRouterModelRouter modelRouter;

    public OpenRouterLLMClient(
            ObjectMapper objectMapper,
            OpenRouterProperties props,
            OpenRouterRequestExecutor requestExecutor,
            OpenRouterModelRouter modelRouter
    ) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.requestExecutor = requestExecutor;
        this.modelRouter = modelRouter;
    }

    @Override
    public String modelName() { return modelRouter.activeModelName(); }

    public String visionModelName() { return freeModel(props.visionModel(), DEFAULT_FREE_VISION_MODEL); }

    public String toolsModelName() {
        String m = props.toolsModel();
        return (m != null && !m.isBlank()) ? m.trim() : "nvidia/nemotron-3-ultra-550b-a55b:free";
    }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public String generate(String prompt) {
        try {
            var response = requestExecutor.sendString(buildRequestBody(prompt, false), apiKeyForPrompt(prompt));

            if (response.statusCode() != 200) {
                log.error("OpenRouter respondió con status {}: {}", response.statusCode(), response.body());
                throw new OpenRouterApiException(response.statusCode(), response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("choices").get(0).path("message").path("content").asText();

        } catch (OpenRouterUnavailableException | OpenRouterApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a OpenRouter interrumpido", e);
        } catch (Exception e) {
            log.error("Error llamando a OpenRouter: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a OpenRouter", e);
        }
    }

    @Override
    public void generateStream(String prompt, Consumer<String> onChunk) {
        try {
            var response = requestExecutor.sendLines(buildRequestBody(prompt, true), apiKeyForPrompt(prompt));

            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                log.error("OpenRouter respondió con status {} en stream: {}", response.statusCode(), errorBody);
                throw new OpenRouterApiException(response.statusCode(), errorBody);
            }

            response.body().forEach(line -> {
                if (!line.startsWith("data: ")) return;
                String data = line.substring("data: ".length()).trim();
                if ("[DONE]".equals(data)) return;
                try {
                    JsonNode json = objectMapper.readTree(data);
                    JsonNode content = json.path("choices").get(0).path("delta").path("content");
                    if (!content.isMissingNode() && !content.isNull()) {
                        String text = content.asText();
                        if (!text.isEmpty()) {
                            onChunk.accept(text);
                        }
                    }
                } catch (Exception e) {
                    log.warn("No se pudo parsear chunk SSE de OpenRouter: {}", data, e);
                }
            });

        } catch (OpenRouterUnavailableException | OpenRouterApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stream a OpenRouter interrumpido", e);
        } catch (Exception e) {
            log.error("Error en stream a OpenRouter: {}", e.getMessage(), e);
            throw new RuntimeException("Error en stream a OpenRouter", e);
        }
    }

    @Override
    public LLMToolResponse generateWithTools(String prompt, List<ToolDefinition> tools) {
        try {
            var response = requestExecutor.sendString(buildRequestBody(prompt, false, tools), apiKeyForPrompt(prompt));

            if (response.statusCode() != 200) {
                log.error("[TOOLS] OpenRouter respondió con status {} usando tools: {}", response.statusCode(),
                        response.body().substring(0, Math.min(200, response.body().length())));
                throw new OpenRouterApiException(response.statusCode(), response.body());
            }

            JsonNode message = objectMapper.readTree(response.body()).path("choices").get(0).path("message");
            return parseToolResponse(message);
        } catch (OpenRouterUnavailableException | OpenRouterApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a OpenRouter interrumpido", e);
        } catch (Exception e) {
            log.error("[TOOLS] Error llamando a OpenRouter con tools: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a OpenRouter con tools", e);
        }
    }

    public WhiteboardInterpretationResponse interpretWhiteboardImage(String imageBase64, String whiteboardId) {
        long startTime = System.currentTimeMillis();
        int imageSize = imageBase64 != null ? imageBase64.length() : 0;
        log.info("[WHITEBOARD_INTERPRET] whiteboardId={} Enviando a OpenRouter LLM vision. imageSize={}bytes", whiteboardId, imageSize);

        try {
            var body = buildVisionRequestBody(imageBase64);
            log.debug("[WHITEBOARD_INTERPRET] whiteboardId={} Request body size={}bytes", whiteboardId,
                    objectMapper.writeValueAsString(body).length());

            var response = requestExecutor.sendString(body, whiteboardApiKey());
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

    private Map<String, Object> buildRequestBody(String prompt, boolean stream) {
        return buildRequestBody(prompt, stream, List.of());
    }

    private Map<String, Object> buildRequestBody(String prompt, boolean stream, List<ToolDefinition> tools) {
        var messages = List.of(Map.of("role", "user", "content", prompt));
        var body = new java.util.LinkedHashMap<String, Object>();
        // Use tools-capable model when tools are present, fast model otherwise
        body.put("model", (tools != null && !tools.isEmpty()) ? toolsModelName() : modelName());
        body.put("messages", messages);
        body.put("stream", stream);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toOpenAiTool).toList());
            body.put("tool_choice", "auto");
        }
        if (props.reasoningEnabled()) {
            body.put("reasoning", Map.of("enabled", true));
        }
        return body;
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
                NO clasifiques como "equation" si el contenido principal es una gráfica con ejes.

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

    private Map<String, Object> toOpenAiTool(ToolDefinition definition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", definition.name(),
                        "description", definition.description(),
                        "parameters", definition.parameters()
                )
        );
    }

    private LLMToolResponse parseToolResponse(JsonNode message) {
        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                toolCalls.add(new ToolCall(
                        call.path("id").asText(""),
                        function.path("name").asText(),
                        function.path("arguments").asText("{}")
                ));
            }
        }
        return new LLMToolResponse(message.path("content").asText(""), toolCalls);
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
            log.warn("No se pudo parsear JSON de visión OpenRouter: {}", content, e);
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

    private String whiteboardApiKey() {
        String whiteboardKey = normalizedKey(props.whiteboardApiKey());
        if (!whiteboardKey.isBlank()) {
            return whiteboardKey;
        }
        return normalizedKey(props.apiKey());
    }

    private String openRouterApiKey() {
        String apiKey = normalizedKey(props.apiKey());
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        return whiteboardApiKey();
    }

    private String apiKeyForPrompt(String prompt) {
        if (usesWhiteboardContext(prompt)) {
            return whiteboardApiKey();
        }
        return openRouterApiKey();
    }

    private String normalizedKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    private boolean usesWhiteboardContext(String prompt) {
        return prompt != null && (
                prompt.contains("[PIZARRA ACTIVA]")
                        || prompt.contains("[PIZARRA INTERPRETADA]")
        );
    }

    /**
     * Devuelve el modelo configurado tal cual (o el fallback). Antes forzaba la variante ":free",
     * lo que arrastraba todo al tier gratuito de OpenRouter y provocaba rate-limit (429).
     */
    private String freeModel(String model, String fallback) {
        if (model == null || model.isBlank()) return fallback;
        return model.trim();
    }
}
