package com.academy.chatservice.model;

public record WhiteboardInterpretRequest(
        Long conversationId,
        String whiteboardId,
        String imageBase64,
        String exerciseLabel,
        String interpretMode  // "auto", "math", "graph", "geometry", "algorithm", "flowchart", "text"
) {
    public WhiteboardInterpretRequest {
        if (interpretMode == null || interpretMode.isBlank()) {
            interpretMode = "auto";
        }
    }
}
