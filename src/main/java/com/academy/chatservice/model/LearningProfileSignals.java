package com.academy.chatservice.model;

import java.util.List;

public record LearningProfileSignals(
        List<String> recentDocuments,
        List<LearningExerciseSignal> recentExercises,
        List<LearningQuestionSignal> recentQuestions
) {
}
