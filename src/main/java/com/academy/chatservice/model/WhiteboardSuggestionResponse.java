package com.academy.chatservice.model;

import java.util.List;
import java.util.Map;

public record WhiteboardSuggestionResponse(
        String type,
        String whiteboardId,
        String title,
        List<Map<String, Object>> elements
) {}
