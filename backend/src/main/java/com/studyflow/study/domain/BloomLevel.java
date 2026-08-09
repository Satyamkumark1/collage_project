package com.studyflow.study.domain;

/** Dropping EVALUATE/CREATE — they don't map onto objective single-answer MCQs (see docs/DECISIONS.md). */
public enum BloomLevel {
    REMEMBER,
    UNDERSTAND,
    APPLY,
    ANALYZE
}
