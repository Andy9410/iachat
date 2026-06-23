package com.academy.chatservice.model;

public record WeeklyStudyPlanItemDto(
        String day,
        String title,
        String focus,
        String activity
) {
}
