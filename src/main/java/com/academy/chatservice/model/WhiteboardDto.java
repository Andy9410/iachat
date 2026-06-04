package com.academy.chatservice.model;

import java.time.LocalDateTime;
import java.util.Map;

public record WhiteboardDto(
        String id,
        Long conversationId,
        Long documentId,
        String exerciseLabel,
        String title,
        Map<String, Object> data,
        String mode,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
