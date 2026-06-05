package com.academy.chatservice.model.tools;

import java.util.List;
import java.util.Map;

public record InjectWhiteboardArgs(
        Long conversationId,
        String whiteboardId,
        List<Block> blocks
) {
    public record Block(
            String type,
            String content,
            int orderIndex,
            Map<String, Object> metadata
    ) {}
}
