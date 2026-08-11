package com.studyflow.planner.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record StudyPlanRequest(@NotNull @FutureOrPresent LocalDate examDate) {
}
