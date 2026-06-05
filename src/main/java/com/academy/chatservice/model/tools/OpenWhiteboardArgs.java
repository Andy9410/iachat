package com.academy.chatservice.model.tools;

public record OpenWhiteboardArgs(
        Long conversationId,
        String title,
        String mode
) {}
