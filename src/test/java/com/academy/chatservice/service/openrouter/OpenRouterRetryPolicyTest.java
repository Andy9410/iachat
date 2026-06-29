package com.academy.chatservice.service.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRouterRetryPolicyTest {

    private final OpenRouterRetryPolicy retryPolicy = new OpenRouterRetryPolicy();

    @Test
    void shouldRetryOnlyCapacityErrors() {
        assertTrue(retryPolicy.isModelCapacityError(429, "{\"error\":\"Rate limit exceeded\"}"));
        assertTrue(retryPolicy.isModelCapacityError(400, "{\"error\":\"Free model unavailable\"}"));
        assertTrue(retryPolicy.isModelCapacityError(400, "{\"error\":\"Quota exceeded\"}"));

        assertFalse(retryPolicy.isModelCapacityError(401, "{\"error\":\"Rate limit exceeded\"}"));
        assertFalse(retryPolicy.isModelCapacityError(403, "{\"error\":\"Quota exceeded\"}"));
        assertFalse(retryPolicy.isModelCapacityError(500, "{\"error\":\"Internal server error\"}"));
        assertFalse(retryPolicy.isModelCapacityError(503, "{\"error\":\"Free model unavailable\"}"));
        assertFalse(retryPolicy.isModelCapacityError(400, "{\"error\":\"Invalid JSON\"}"));
    }
}
