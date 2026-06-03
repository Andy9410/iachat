package com.academy.chatservice.model.tools;

public record InterpretWhiteboardArgs(
        String whiteboardId,
        Long conversationId,
        Long documentId,
        String exerciseLabel
) {}
