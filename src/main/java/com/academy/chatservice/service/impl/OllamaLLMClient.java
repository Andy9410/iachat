package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.OllamaProperties;
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

@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
public class OllamaLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLLMClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties props;

    public OllamaLLMClient(ObjectMapper objectMapper, OllamaProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public String generate(String prompt) {
        try {
            String body = objectMapper.writeValueAsString(
                    new OllamaRequest(props.model(), prompt, false));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.baseUrl() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Ollama respondió con status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Ollama error: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            return json.path("response").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a Ollama interrumpido", e);
        } catch (Exception e) {
            log.error("Error llamando a Ollama: {}", e.getMessage(), e);
            throw new RuntimeException("Error al llamar a Ollama", e);
        }
    }

    private record OllamaRequest(String model, String prompt, boolean stream) {}
}