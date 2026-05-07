package com.academy.chatservice.service.impl;

import com.academy.chatservice.config.CloudflareProperties;
import com.academy.chatservice.service.EmbeddingClient;
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
import java.util.ArrayList;
import java.util.List;

@Component
public class CloudflareEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(CloudflareEmbeddingClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CloudflareProperties props;

    public CloudflareEmbeddingClient(ObjectMapper objectMapper, CloudflareProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public List<Float> embed(String text) {
        try {
            String body = objectMapper.writeValueAsString(new EmbedRequest(List.of(text)));

            String url = props.baseUrl() + "/accounts/" + props.accountId() + "/ai/run/" + props.embeddingModel();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.apiToken())
                    .timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Cloudflare AI respondió con status {}: {}", response.statusCode(), response.body());
                throw new RuntimeException("Cloudflare AI error: HTTP " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            JsonNode vector = json.path("result").path("data").get(0);

            List<Float> embedding = new ArrayList<>();
            for (JsonNode val : vector) {
                embedding.add(val.floatValue());
            }
            return embedding;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request a Cloudflare AI interrumpido", e);
        } catch (Exception e) {
            log.error("Error llamando a Cloudflare AI: {}", e.getMessage(), e);
            throw new RuntimeException("Error al generar embedding", e);
        }
    }

    private record EmbedRequest(List<String> text) {}
}
