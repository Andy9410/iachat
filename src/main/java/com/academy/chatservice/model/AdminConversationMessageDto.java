package com.academy.chatservice.model;

import java.time.LocalDateTime;

public record AdminConversationMessageDto(
        String role,
        String content,
        LocalDateTime createdAt
) {}
