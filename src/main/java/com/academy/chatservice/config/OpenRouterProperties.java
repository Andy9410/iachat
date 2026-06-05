package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm.openrouter")
public record OpenRouterProperties(
        @DefaultValue("https://openrouter.ai") String baseUrl,
        @DefaultValue("liquid/lfm-2.5-1.2b-instruct:free") String model,
        @DefaultValue("google/gemma-4-26b-a4b-it:free") String visionModel,
        @DefaultValue("liquid/lfm-2.5-1.2b-instruct:free") String lessonModel,
        @DefaultValue("meta-llama/llama-3.3-70b-instruct:free") String toolsModel,
        String apiKey,
        String whiteboardApiKey,
        @DefaultValue("false") boolean reasoningEnabled,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("120") int requestTimeoutSeconds
) {}
