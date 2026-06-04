package com.academy.chatservice.model.tools;

public record CreateReasoningNodeArgs(
        Long conversationId,
        Long whiteboardId,
        Long parentNodeId,
        String nodeType,
        String title,
        String description,
        String status,
        Integer orderIndex
) {}
