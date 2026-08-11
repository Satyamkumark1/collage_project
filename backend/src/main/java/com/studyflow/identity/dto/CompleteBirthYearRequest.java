package com.studyflow.identity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CompleteBirthYearRequest(@NotNull @Min(1900) Short birthYear) {
}
