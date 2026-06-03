package com.academy.chatservice.model.tools;

import java.util.List;

public record ExerciseBreakdownResponse(
        String type,
        String exerciseTitle,
        List<ExerciseStepDTO> steps
) {
    public ExerciseBreakdownResponse(String exerciseTitle, List<ExerciseStepDTO> steps) {
        this("exercise_breakdown", exerciseTitle, steps);
    }
}
