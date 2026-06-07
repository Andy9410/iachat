package com.academy.chatservice.model;

import java.util.List;
import java.util.Map;

public record InjectWhiteboardRequest(List<BlockRequest> blocks) {

    public record BlockRequest(
            String type,
            String author,       // "user" | "assistant" — defaults to "assistant"
            String content,
            int orderIndex,
            Map<String, Object> metadata
    ) {}
}
