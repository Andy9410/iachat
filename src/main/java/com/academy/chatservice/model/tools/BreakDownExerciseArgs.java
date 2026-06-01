package com.academy.chatservice.model.tools;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record BreakDownExerciseArgs(
        @NotBlank String exerciseText,
        @NotBlank String exerciseTitle,
        @NotBlank @Pattern(regexp = "basico|intermedio|avanzado") String userLevel,
        @NotNull Boolean showFullSolution
) {}
