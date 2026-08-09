package com.studyflow.study.service;

import com.studyflow.study.domain.BloomLevel;
import com.studyflow.study.domain.Difficulty;
import java.util.List;

public record QuestionDraft(String stem, List<String> options, int correctIndex, String explanation,
        Difficulty difficulty, BloomLevel bloomLevel, List<String> citedChunkIds) {
}
