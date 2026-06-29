package com.academy.chatservice.service.openrouter;

import com.academy.chatservice.config.OpenRouterProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenRouterModelRouterTest {

    @Test
    void shouldKeepSuccessfulModelAsActiveModel() {
        OpenRouterModelRouter router = new OpenRouterModelRouter(properties(), fixedClock());

        assertEquals("model-a:free", router.selectModel(Set.of()).model());

        router.markSuccessful("model-b:free");

        assertEquals("model-b:free", router.selectModel(Set.of()).model());
    }

    @Test
    void shouldSkipModelsInCooldown() {
        OpenRouterModelRouter router = new OpenRouterModelRouter(properties(), fixedClock());

        router.markUnavailable("model-a:free");

        assertEquals("model-b:free", router.selectModel(Set.of()).model());
    }

    private OpenRouterProperties properties() {
        return new OpenRouterProperties(
                "https://openrouter.ai",
                "model-a:free",
                List.of("model-a:free", "model-b:free", "model-c:free"),
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
}
