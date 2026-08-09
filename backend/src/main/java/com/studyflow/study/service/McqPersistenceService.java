package com.studyflow.study.service;

import com.studyflow.rag.service.ChunkQueryService.ChunkView;
import com.studyflow.study.domain.Difficulty;
import com.studyflow.study.domain.Question;
import com.studyflow.study.domain.QuestionSet;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.study.repo.QuestionSetRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class McqPersistenceService {

    private final QuestionSetRepository questionSetRepository;
    private final QuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    public McqPersistenceService(QuestionSetRepository questionSetRepository, QuestionRepository questionRepository,
            ObjectMapper objectMapper) {
        this.questionSetRepository = questionSetRepository;
        this.questionRepository = questionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QuestionSet saveAll(UUID documentId, UUID ownerId, UUID jobId, short requestedCount,
            List<QuestionDraft> drafts, List<ChunkView> chunks, Map<Difficulty, Integer> difficultyMix, String model,
            int promptVersion) {
        Map<String, ChunkView> chunksById = new LinkedHashMap<>();
        for (ChunkView chunk : chunks) {
            chunksById.put(chunk.id().toString(), chunk);
        }

        String difficultyMixJson = objectMapper.writeValueAsString(difficultyMix);
        QuestionSet questionSet = questionSetRepository.save(new QuestionSet(documentId, ownerId, jobId,
                requestedCount, (short) drafts.size(), difficultyMixJson, model, promptVersion));

        List<Question> toSave = new ArrayList<>();
        short sortOrder = 0;
        for (QuestionDraft draft : drafts) {
            String optionsJson = objectMapper.writeValueAsString(draft.options());
            String citationsJson = buildCitationsJson(draft.citedChunkIds(), chunksById);
            toSave.add(new Question(questionSet.getId(), documentId, ownerId, draft.stem(), optionsJson,
                    (short) draft.correctIndex(), draft.explanation(), draft.difficulty(), draft.bloomLevel(),
                    citationsJson, sortOrder++));
        }
        questionRepository.saveAll(toSave);
        return questionSet;
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
