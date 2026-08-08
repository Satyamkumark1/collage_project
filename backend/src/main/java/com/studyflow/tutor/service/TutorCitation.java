package com.studyflow.tutor.service;

import java.util.UUID;

/**
 * A citation actually referenced by the model's inline {@code [n]} markers, mapped back to the
 * fixed retrieval candidate list — never self-reported (see specs/09-rag.md §Grounding contract).
 */
public record TutorCitation(int marker, UUID chunkId, Integer pageFrom, Integer pageTo, String sectionPath) {
}
