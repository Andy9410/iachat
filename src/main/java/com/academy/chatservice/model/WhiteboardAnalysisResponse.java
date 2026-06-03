package com.academy.chatservice.model;

import java.util.List;

public record WhiteboardAnalysisResponse(
        String type,
        String whiteboardId,
        String title,
        String summary,
        List<String> observations
) {}
