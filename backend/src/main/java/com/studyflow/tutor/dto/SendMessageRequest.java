package com.studyflow.tutor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(@NotBlank @Size(max = 4000) String content, boolean explainBeyondNotes) {
}
