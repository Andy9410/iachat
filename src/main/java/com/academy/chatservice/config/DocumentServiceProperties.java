package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "document-service")
public record DocumentServiceProperties(
        @DefaultValue("http://localhost:8083") String baseUrl,
        @DefaultValue("5")    int topK,
        @DefaultValue("0.72") double similarityThreshold,
        @DefaultValue("true") boolean enabled
) {}
