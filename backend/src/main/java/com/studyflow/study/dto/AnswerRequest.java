package com.studyflow.study.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** {@code selectedIndex} is nullable — sending {@code null} clears a previously saved answer. */
public record AnswerRequest(@Min(0) @Max(3) Integer selectedIndex) {
}
