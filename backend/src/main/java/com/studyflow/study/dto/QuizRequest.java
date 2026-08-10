package com.studyflow.study.dto;

import com.studyflow.study.domain.QuizMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QuizRequest(@NotNull QuizMode mode, @NotNull @Positive Integer requestedCount) {
}
