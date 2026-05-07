package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "cloudflare")
public record CloudflareProperties(
        String accountId,
        String apiToken,
        @DefaultValue("@cf/baai/bge-base-en-v1.5") String embeddingModel,
        @DefaultValue("https://api.cloudflare.com/client/v4") String baseUrl,
        @DefaultValue("10") int connectTimeoutSeconds,
        @DefaultValue("30") int requestTimeoutSeconds
) {}
