package com.academy.chatservice.model.tools;

import java.util.List;

public record LLMToolResponse(
        String content,
        List<ToolCall> toolCalls
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
