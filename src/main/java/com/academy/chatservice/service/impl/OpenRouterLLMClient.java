package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.OpenRouterProperties;
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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openrouter")
public class OpenRouterLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLLMClient.class);
    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenRouterProperties props;

    public OpenRouterLLMClient(ObjectMapper objectMapper, OpenRouterProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public String modelName() { return props.model(); }

    @Override
    public String generate(String prompt) {
        try {
            var body = objectMapper.writeValueAsString(buildRequestBody(prompt, false));

            var request = buildHttpRequest(body);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenRouter respondió con status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("OpenRouter error: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("choices").get(0).path("message").path("content").asText();

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
            var body = objectMapper.writeValueAsString(buildRequestBody(prompt, true));
            var request = buildHttpRequest(body);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                String errorBody = response.body().collect(java.util.stream.Collectors.joining("\n"));
                log.error("OpenRouter respondió con status {} en stream: {}", response.statusCode(), errorBody);
                throw new RuntimeException("OpenRouter error: HTTP " + response.statusCode());
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

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stream a OpenRouter interrumpido", e);
        } catch (Exception e) {
            log.error("Error en stream a OpenRouter: {}", e.getMessage(), e);
            throw new RuntimeException("Error en stream a OpenRouter", e);
        }
    }

    private Map<String, Object> buildRequestBody(String prompt, boolean stream) {
        var messages = List.of(Map.of("role", "user", "content", prompt));
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", props.model());
        body.put("messages", messages);
        body.put("stream", stream);
        if (props.reasoningEnabled()) {
            body.put("reasoning", Map.of("enabled", true));
        }
        return body;
    }

    private HttpRequest buildHttpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(props.baseUrl() + COMPLETIONS_PATH))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
