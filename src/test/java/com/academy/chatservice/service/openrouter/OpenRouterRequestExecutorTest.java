package com.academy.chatservice.service.openrouter;

import com.academy.chatservice.config.OpenRouterProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenRouterRequestExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRetryWithNextModelAndPreserveRequestPayload() throws Exception {
        FakeOpenRouterClient client = new FakeOpenRouterClient(properties());
        OpenRouterModelRouter router = new OpenRouterModelRouter(properties(), fixedClock());
        OpenRouterRequestExecutor executor = new OpenRouterRequestExecutor(
                objectMapper,
                client,
                router,
                new OpenRouterRetryPolicy(),
                new OpenRouterMetrics()
        );

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "old-model");
        request.put("messages", List.of(Map.of("role", "user", "content", "hola")));
        request.put("temperature", 0.2);
        request.put("max_tokens", 128);
        request.put("tools", List.of(Map.of("type", "function")));

        HttpResponse<String> result = executor.sendString(request, "api-key");

        assertEquals(200, result.statusCode());

        assertEquals(2, client.bodies.size());
        JsonNode firstAttempt = objectMapper.readTree(client.bodies.get(0));
        JsonNode secondAttempt = objectMapper.readTree(client.bodies.get(1));

        assertEquals("model-a:free", firstAttempt.path("model").asText());
        assertEquals("model-b:free", secondAttempt.path("model").asText());
        assertEquals("hola", secondAttempt.path("messages").get(0).path("content").asText());
        assertEquals(0.2, secondAttempt.path("temperature").asDouble());
        assertEquals(128, secondAttempt.path("max_tokens").asInt());
        assertEquals("function", secondAttempt.path("tools").get(0).path("type").asText());
    }

    private OpenRouterProperties properties() {
        return new OpenRouterProperties(
                "https://openrouter.ai",
                "model-a:free",
                List.of("model-a:free", "model-b:free"),
                "vision:free",
                "lesson:free",
                "tools:free",
                "api-key",
                "",
                false,
                10,
                10,
                120
        );
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-29T12:00:00Z"), ZoneOffset.UTC);
    }

    private static final class FakeOpenRouterClient extends OpenRouterClient {
        private final List<String> bodies = new ArrayList<>();

        private FakeOpenRouterClient(OpenRouterProperties properties) {
            super(properties);
        }

        @Override
        public HttpResponse<String> sendString(String body, String apiKey) {
            bodies.add(body);
            if (bodies.size() == 1) {
                return new FakeHttpResponse(429, "{\"error\":\"Rate limit exceeded\"}");
            }
            return new FakeHttpResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        }
    }

    private record FakeHttpResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://openrouter.ai/api/v1/chat/completions");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
