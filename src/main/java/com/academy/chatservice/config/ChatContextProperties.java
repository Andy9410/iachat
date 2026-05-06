package com.academy.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "chat.context")
public record ChatContextProperties(
        @DefaultValue("20") int windowSize,
        @DefaultValue("50") int compactionThreshold
) {}
