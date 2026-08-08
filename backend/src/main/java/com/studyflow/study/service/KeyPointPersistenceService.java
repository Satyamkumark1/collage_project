package com.studyflow.study.service;

import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.KeyPoint;
import com.studyflow.study.repo.KeyPointRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class KeyPointPersistenceService {

    private final KeyPointRepository keyPointRepository;
    private final ObjectMapper objectMapper;

    public KeyPointPersistenceService(KeyPointRepository keyPointRepository, ObjectMapper objectMapper) {
        this.keyPointRepository = keyPointRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<KeyPoint> saveAll(UUID documentId, UUID ownerId, UUID jobId, List<KeyPointDraft> drafts,
            List<ChunkView> chunks, String model, int promptVersion) {
        Map<String, ChunkView> chunksById = new LinkedHashMap<>();
        for (ChunkView chunk : chunks) {
            chunksById.put(chunk.id().toString(), chunk);
        }

        List<KeyPoint> toSave = new ArrayList<>();
        short sortOrder = 0;
        for (KeyPointDraft draft : drafts) {
            String citationsJson = buildCitationsJson(draft.citedChunkIds(), chunksById);
            toSave.add(new KeyPoint(documentId, ownerId, jobId, draft.category(), draft.label(),
                    draft.contentMd(), citationsJson, sortOrder++, model, promptVersion));
        }
        return keyPointRepository.saveAll(toSave);
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
