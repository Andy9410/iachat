package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm.openrouter")
public record OpenRouterProperties(
        @DefaultValue("https://openrouter.ai") String baseUrl,
        @DefaultValue("nex-agi/nex-n2-pro:free") String model,
        @DefaultValue("nex-agi/nex-n2-pro:free") String visionModel,
        @DefaultValue("nex-agi/nex-n2-pro:free") String lessonModel,
        @DefaultValue("nex-agi/nex-n2-pro:free") String toolsModel,
        String apiKey,
        String whiteboardApiKey,
        @DefaultValue("false") boolean reasoningEnabled,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("120") int requestTimeoutSeconds
) {}
