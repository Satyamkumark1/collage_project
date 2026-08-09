package com.studyflow.study.dto;

import jakarta.validation.constraints.NotNull;

public record QuestionSetRequest(@NotNull Integer requestedCount) {
}
