package com.academy.chatservice.model.tools;

public record ExerciseStepDTO(
        int stepNumber,
        String title,
        String content,
        String hint
) {}
