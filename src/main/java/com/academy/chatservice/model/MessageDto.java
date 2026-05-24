package com.academy.chatservice.model;

import java.time.LocalDateTime;
import java.util.List;

public record MessageDto(Long id, String role, String content, LocalDateTime createdAt, List<String> suggestions) {}
