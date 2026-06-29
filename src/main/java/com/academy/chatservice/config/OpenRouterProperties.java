package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "llm.openrouter")
public record OpenRouterProperties(
        @DefaultValue("https://openrouter.ai") String baseUrl,
        @DefaultValue("nvidia/nemotron-3-ultra-550b-a55b:free") String model,
        List<String> models,
        @DefaultValue("nvidia/nemotron-3-ultra-550b-a55b:free") String visionModel,
        @DefaultValue("nvidia/nemotron-3-ultra-550b-a55b:free") String lessonModel,
        @DefaultValue("nvidia/nemotron-3-ultra-550b-a55b:free") String toolsModel,
        String apiKey,
        String whiteboardApiKey,
        @DefaultValue("false") boolean reasoningEnabled,
        @DefaultValue("10") int modelCooldownMinutes,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("120") int requestTimeoutSeconds
) {}
