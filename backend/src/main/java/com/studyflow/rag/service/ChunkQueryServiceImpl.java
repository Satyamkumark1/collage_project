package com.studyflow.rag.service;

import com.studyflow.rag.repo.DocumentChunkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChunkQueryServiceImpl implements ChunkQueryService {

    private final DocumentChunkRepository chunkRepository;

    public ChunkQueryServiceImpl(DocumentChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChunkView> findOrderedChunks(UUID documentId, UUID ownerId) {
        return chunkRepository.findByDocumentIdAndOwnerIdOrderByChunkIndexAsc(documentId, ownerId).stream()
                .map(chunk -> new ChunkView(chunk.getId(), chunk.getContent(), chunk.getTokenCount(),
                        chunk.getChunkIndex(), chunk.getPageFrom(), chunk.getPageTo(), chunk.getSectionPath()))
                .toList();
    }
}
