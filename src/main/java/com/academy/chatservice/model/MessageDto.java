package com.academy.chatservice.model;

import java.time.LocalDateTime;

public record MessageDto(Long id, String role, String content, LocalDateTime createdAt) {}
