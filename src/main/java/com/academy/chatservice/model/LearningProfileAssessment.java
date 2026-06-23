package com.academy.chatservice.model;

public record LearningProfileAssessment(
        ProfileMaturity maturity,
        int progressPercentage,
        boolean canGenerateRecommendations,
        boolean canGenerateStudyPlan,
        boolean canGenerateStrengths,
        boolean canGenerateWeaknesses
) {
}
