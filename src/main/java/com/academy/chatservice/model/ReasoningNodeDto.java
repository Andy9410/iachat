package com.academy.chatservice.model;

public record ReasoningNodeDto(
        Long nodeId,
        Long conversationId,
        Long whiteboardId,
        Long parentNodeId,
        String nodeType,
        String title,
        String description,
        String status,
        int level,
        int orderIndex
) {}
