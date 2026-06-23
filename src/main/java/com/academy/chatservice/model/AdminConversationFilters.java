package com.academy.chatservice.model;

import java.time.LocalDate;

public record AdminConversationFilters(
        String email,
        String name,
        String title,
        LocalDate from,
        LocalDate to
) {}
