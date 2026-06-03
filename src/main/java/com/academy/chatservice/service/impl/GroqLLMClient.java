package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.GroqProperties;
import com.academy.chatservice.model.tools.LLMToolResponse;
import com.academy.chatservice.model.tools.ToolCall;
import com.academy.chatservice.model.tools.ToolDefinition;
import com.academy.chatservice.service.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "groq")
public class GroqLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(GroqLLMClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GroqProperties props;

    public GroqLLMClient(ObjectMapper objectMapper, GroqProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public String modelName() { return props.model(); }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public String generate(String prompt) {
        try {
            var body = objectMapper.writeValueAsString(new GroqRequest(
                    props.model(),
                    List.of(new Message("user", prompt)),
                    false
            ));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/openai/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Groq respondió con status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Groq error: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("choices").get(0).path("message").path("content").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a Groq interrumpido", e);
        } catch (Exception e) {
            log.error("Error llamando a Groq: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a Groq", e);
        }
    }

    @Override
    public LLMToolResponse generateWithTools(String prompt, List<ToolDefinition> tools) {
        try {
            var body = objectMapper.writeValueAsString(buildRequestBody(prompt, false, tools));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/openai/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Groq respondió con status {} usando tools: {}", response.statusCode(), response.body());
                throw new RuntimeException("Groq error: HTTP " + response.statusCode());
            }

            JsonNode message = objectMapper.readTree(response.body()).path("choices").get(0).path("message");
            return parseToolResponse(message);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a Groq interrumpido", e);
        } catch (Exception e) {
            log.error("Error llamando a Groq con tools: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a Groq con tools", e);
        }
    }

    @Override
    public void generateStream(String prompt, java.util.function.Consumer<String> onChunk) {
        try {
            var body = objectMapper.writeValueAsString(new GroqRequest(
                    props.model(),
                    List.of(new Message("user", prompt)),
                    true
            ));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/openai/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiKey())
                    .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                log.error("Groq respondió con status {} en stream", response.statusCode());
                throw new RuntimeException("Groq error: HTTP " + response.statusCode());
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
                    log.warn("No se pudo parsear chunk SSE de Groq: {}", data, e);
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stream a Groq interrumpido", e);
        } catch (Exception e) {
            log.error("Error en stream a Groq: {}", e.getMessage(), e);
            throw new RuntimeException("Error en stream a Groq", e);
        }
    }

    private record Message(String role, String content) {}
    private record GroqRequest(String model, List<Message> messages, boolean stream) {}

    private Map<String, Object> buildRequestBody(String prompt, boolean stream, List<ToolDefinition> tools) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", props.model());
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", stream);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toOpenAiTool).toList());
            body.put("tool_choice", "auto");
        }
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
}
