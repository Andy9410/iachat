package com.academy.chatservice.model;

import java.util.List;

public record LearningProfileDto(
        ProfileMaturity maturity,
        int documentsAnalyzed,
        int exercisesDetected,
        int interactions,
        int progressPercentage,
        boolean canGenerateRecommendations,
        boolean canGenerateStudyPlan,
        java.util.List<LearningRecommendationDto> recommendations,
        java.util.List<WeeklyStudyPlanItemDto> weeklyStudyPlan,
        List<String> strengths,
        List<String> weaknesses
) {
}
