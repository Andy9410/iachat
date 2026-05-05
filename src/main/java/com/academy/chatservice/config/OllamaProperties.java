package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm.ollama")
public record OllamaProperties(
        @DefaultValue("http://localhost:11434") String baseUrl,
        @DefaultValue("qwen2.5:14b")           String model,
        @DefaultValue("10")                     int connectTimeoutSeconds,
        @DefaultValue("120")                    int requestTimeoutSeconds
) {}