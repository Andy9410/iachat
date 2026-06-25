package com.academy.chatservice.model;

import java.time.LocalDate;

public record WeeklyStudyPlanItemDto(
        String dayLabel,
        LocalDate date,
        String title,
        String focus,
        String activity
) {
}
