package com.academy.chatservice.model;

import java.util.List;

public record AdminConversationPageDto(
        List<AdminConversationSummaryDto> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {}
