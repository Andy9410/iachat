package com.academy.chatservice.model;

import java.time.LocalDateTime;

public record ConversationSummaryDto(Long id, String title, LocalDateTime createdAt, int messageCount) {}
