package com.academy.chatservice.service.openrouter;

import com.academy.chatservice.config.OpenRouterProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

@Component
public class OpenRouterClient {

    private static final String COMPLETIONS_PATH = "/api/v1/chat/completions";

    private final OpenRouterProperties properties;
    private final HttpClient httpClient;

    public OpenRouterClient(OpenRouterProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .build();
    }

    public HttpResponse<String> sendString(String body, String apiKey) throws IOException, InterruptedException {
        return httpClient.send(buildHttpRequest(body, apiKey), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<Stream<String>> sendLines(String body, String apiKey) throws IOException, InterruptedException {
        return httpClient.send(buildHttpRequest(body, apiKey), HttpResponse.BodyHandlers.ofLines());
    }

    private HttpRequest buildHttpRequest(String body, String apiKey) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + COMPLETIONS_PATH))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(properties.requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
