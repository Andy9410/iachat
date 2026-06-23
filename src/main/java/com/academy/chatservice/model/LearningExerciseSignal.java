package com.academy.chatservice.model;

public record LearningExerciseSignal(
        String documentName,
        String exerciseRef,
        int chunkCount
) {
}
