package com.academy.chatservice.service.openrouter;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class OpenRouterRetryPolicy {

    public boolean isModelCapacityError(int statusCode, String responseBody) {
        if (statusCode == 401 || statusCode == 403) {
            return false;
        }
        if (statusCode >= 500) {
            return false;
        }
        if (statusCode == 429) {
            return true;
        }

        String normalized = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        return normalized.contains("rate limit")
                || normalized.contains("too many requests")
                || normalized.contains("free model capacity exceeded")
                || normalized.contains("quota exceeded")
                || normalized.contains("free model unavailable");
    }
}
