package com.academy.chatservice.model;

import java.util.List;

public record WhiteboardLessonResponse(
        String title,
        List<LessonStepDto> steps
) {}
