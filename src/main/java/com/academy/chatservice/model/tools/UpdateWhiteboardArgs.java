package com.academy.chatservice.model.tools;

import java.util.List;

public record UpdateWhiteboardArgs(
        String whiteboardId,
        Long conversationId,
        List<StepArg> entries
) {
    public record StepArg(String type, String content, int orderIndex) {}
}
