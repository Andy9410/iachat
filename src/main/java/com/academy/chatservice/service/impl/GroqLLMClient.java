package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.GroqProperties;
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
    public String generate(String prompt) {
        try {
            var body = objectMapper.writeValueAsString(new GroqRequest(
                    props.model(),
                    List.of(new Message("user", prompt))
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

    private record Message(String role, String content) {}
    private record GroqRequest(String model, List<Message> messages) {}
}
