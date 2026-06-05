package com.academy.chatservice.model;

public record WhiteboardEntryDto(
        Long id,
        String whiteboardId,
        Long conversationId,
        String type,
        String content,
        int orderIndex,
        String metadata
) {}
