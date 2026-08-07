package com.studyflow.rag.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "token_count", nullable = false, updatable = false)
    private int tokenCount;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(name = "section_path")
    private String sectionPath;

    @Column(name = "content_sha256", nullable = false, updatable = false)
    private String contentSha256;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentChunk() {
        // JPA
    }

    public DocumentChunk(UUID documentId, UUID ownerId, int chunkIndex, String content, int tokenCount,
            Integer pageFrom, Integer pageTo, String sectionPath, String contentSha256) {
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.tokenCount = tokenCount;
        this.pageFrom = pageFrom;
        this.pageTo = pageTo;
        this.sectionPath = sectionPath;
        this.contentSha256 = contentSha256;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public Integer getPageFrom() {
        return pageFrom;
    }

    public Integer getPageTo() {
        return pageTo;
    }

    public String getSectionPath() {
        return sectionPath;
    }
}
