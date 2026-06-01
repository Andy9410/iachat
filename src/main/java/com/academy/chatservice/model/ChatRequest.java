package com.academy.chatservice.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        String message,
        Long conversationId,
        Long preferredDocumentId,
        String activeWhiteboardId,
        WhiteboardInterpretationResponse whiteboardInterpretation,
        Integer explanationLevel,
        Boolean includeFullHistory,
        Integer visiblePage
) {
    public ChatRequest(
            String message,
            Long conversationId,
            Long preferredDocumentId,
            Integer explanationLevel,
            Boolean includeFullHistory,
            Integer visiblePage
    ) {
        this(message, conversationId, preferredDocumentId, null, null, explanationLevel, includeFullHistory, visiblePage);
    }
}
