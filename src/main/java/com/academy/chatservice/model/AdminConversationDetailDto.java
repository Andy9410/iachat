package com.academy.chatservice.model;

import java.time.LocalDateTime;
import java.util.List;

public record AdminConversationDetailDto(
        Long id,
        String title,
        AdminConversationUserDto user,
        LocalDateTime createdAt,
        LocalDateTime lastActivity,
        int messageCount,
        List<AdminConversationMessageDto> messages
) {}
