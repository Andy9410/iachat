package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm.openrouter")
public record OpenRouterProperties(
        @DefaultValue("https://openrouter.ai") String baseUrl,
        @DefaultValue("openai/gpt-4o-mini") String model,
        @DefaultValue("openai/gpt-4o-mini") String visionModel,
        @DefaultValue("openai/gpt-4o-mini") String lessonModel,
        @DefaultValue("openai/gpt-4o-mini") String toolsModel,
        String apiKey,
        String whiteboardApiKey,
        @DefaultValue("false") boolean reasoningEnabled,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("120") int requestTimeoutSeconds
) {}
