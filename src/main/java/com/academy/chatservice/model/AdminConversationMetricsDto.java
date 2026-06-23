package com.academy.chatservice.model;

public record AdminConversationMetricsDto(
        long totalConversations,
        long activeToday,
        long uniqueUsersToday,
        long messagesToday
) {}
