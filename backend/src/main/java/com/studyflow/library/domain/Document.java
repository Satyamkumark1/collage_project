package com.studyflow.library.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, updatable = false)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", columnDefinition = "varchar(10)", nullable = false, updatable = false)
    private DocumentFileType fileType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "content_sha256", nullable = false, updatable = false)
    private String contentSha256;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "storage_provider", nullable = false, updatable = false)
    private String storageProvider;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "language")
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_detail")
    private String failureDetail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Document() {
        // JPA
    }

    public Document(UUID ownerId, String title, String originalFilename, String mimeType, DocumentFileType fileType,
            long sizeBytes, String contentSha256, String storageKey, String storageProvider) {
        this.ownerId = ownerId;
        this.title = title;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.fileType = fileType;
        this.sizeBytes = sizeBytes;
        this.contentSha256 = contentSha256;
        this.storageKey = storageKey;
        this.storageProvider = storageProvider;
    }

    /**
     * Takes an explicit id so the caller can compute a storage key that embeds the document id
     * (e.g. {@code users/{userId}/{documentId}/...}) before the row is ever persisted.
     */
    public Document(UUID id, UUID ownerId, String title, String originalFilename, String mimeType,
            DocumentFileType fileType, long sizeBytes, String contentSha256, String storageKey,
            String storageProvider) {
        this(ownerId, title, originalFilename, mimeType, fileType, sizeBytes, contentSha256, storageKey,
                storageProvider);
        this.id = id;
    }

    public void markParsing() {
        this.status = DocumentStatus.PARSING;
    }

    public void markChunking() {
        this.status = DocumentStatus.CHUNKING;
    }

    public void markEmbedding() {
        this.status = DocumentStatus.EMBEDDING;
    }

    public void markReady(int pageCount, int charCount, String language) {
        this.status = DocumentStatus.READY;
        this.pageCount = pageCount;
        this.charCount = charCount;
        this.language = language;
    }

    public void markFailed(String failureCode, String failureDetail) {
        this.status = DocumentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureDetail = failureDetail;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public DocumentFileType getFileType() {
        return fileType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
