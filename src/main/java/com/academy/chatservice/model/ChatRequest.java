package com.academy.chatservice.model;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        String message,
        Long conversationId
) {}
