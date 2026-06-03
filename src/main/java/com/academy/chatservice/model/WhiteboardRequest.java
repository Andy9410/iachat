package com.academy.chatservice.model;

import java.util.Map;

public record WhiteboardRequest(
        Long documentId,
        String exerciseLabel,
        String title,
        Map<String, Object> data
) {}
