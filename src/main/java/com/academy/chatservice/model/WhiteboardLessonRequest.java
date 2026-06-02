package com.academy.chatservice.model;

public record WhiteboardLessonRequest(
        Long conversationId,
        String userMessage,
        String assistantMessage
) {}
