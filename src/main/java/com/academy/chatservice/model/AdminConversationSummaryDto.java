package com.academy.chatservice.model;

import java.time.LocalDateTime;

public record AdminConversationSummaryDto(
        Long conversationId,
        String title,
        String userEmail,
        String userName,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime lastActivity,
        String lastMessage,
        boolean active
) {}
