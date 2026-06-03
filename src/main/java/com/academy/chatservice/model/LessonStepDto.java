package com.academy.chatservice.model;

import java.util.List;
import java.util.Map;

public record LessonStepDto(
        String id,
        String title,
        String explanation,
        List<Map<String, Object>> elements
) {}
