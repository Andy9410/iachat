package com.academy.chatservice.model;

public record LearningRecommendationDto(
        String title,
        String description,
        String confidence
) {
}
