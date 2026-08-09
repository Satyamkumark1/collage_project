package com.studyflow.study.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.Flashcard;
import com.studyflow.study.repo.FlashcardRepository;
import com.studyflow.study.service.Sm2Calculator.Sm2Result;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class FlashcardPersistenceService {

    private final FlashcardRepository flashcardRepository;
    private final ObjectMapper objectMapper;

    public FlashcardPersistenceService(FlashcardRepository flashcardRepository, ObjectMapper objectMapper) {
        this.flashcardRepository = flashcardRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<Flashcard> saveAll(UUID documentId, UUID ownerId, UUID jobId, List<FlashcardDraft> drafts,
            List<ChunkView> chunks, String model, int promptVersion) {
        Map<String, ChunkView> chunksById = new LinkedHashMap<>();
        for (ChunkView chunk : chunks) {
            chunksById.put(chunk.id().toString(), chunk);
        }

        List<Flashcard> toSave = new ArrayList<>();
        for (FlashcardDraft draft : drafts) {
            String citationsJson = buildCitationsJson(draft.citedChunkIds(), chunksById);
            toSave.add(new Flashcard(documentId, ownerId, jobId, draft.frontMd(), draft.backMd(), citationsJson,
                    model, promptVersion));
        }
        return flashcardRepository.saveAll(toSave);
    }

    // Optimistic locking (Flashcard.version) means a concurrent double-review of the same card
    // throws ObjectOptimisticLockingFailureException here instead of silently corrupting SM-2
    // state — see the migration's comment and docs/DECISIONS.md.
    @Transactional
    public Flashcard review(UUID id, UUID ownerId, int quality, ZoneId userZone) {
        Flashcard flashcard = flashcardRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.FLASHCARD_NOT_FOUND, "No flashcard with that id"));
        Sm2Result result = Sm2Calculator.compute(flashcard.getEaseFactor(), flashcard.getIntervalDays(),
                flashcard.getRepetitions(), quality, userZone);
        flashcard.applyReview(result, (short) quality);
        return flashcardRepository.save(flashcard);
    }

    private String buildCitationsJson(List<String> citedChunkIds, Map<String, ChunkView> chunksById) {
        List<Map<String, Object>> citations = citedChunkIds.stream().map(id -> {
            ChunkView chunk = chunksById.get(id);
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("chunkId", id);
            citation.put("pageFrom", chunk.pageFrom());
            citation.put("pageTo", chunk.pageTo());
            citation.put("sectionPath", chunk.sectionPath());
            return citation;
        }).toList();
        return objectMapper.writeValueAsString(citations);
    }
}
