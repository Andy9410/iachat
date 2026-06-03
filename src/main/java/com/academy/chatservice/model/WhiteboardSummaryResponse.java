package com.academy.chatservice.model;

public record WhiteboardSummaryResponse(
        String type,
        String whiteboardId,
        String title,
        String summary
) {}
