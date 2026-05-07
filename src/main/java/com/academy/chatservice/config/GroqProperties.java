package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "llm.groq")
public record GroqProperties(
        @DefaultValue("https://api.groq.com") String  baseUrl,
        @DefaultValue("llama-3.3-70b-versatile") String model,
        String apiKey,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("60") int requestTimeoutSeconds
) {}
