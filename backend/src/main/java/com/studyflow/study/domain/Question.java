package com.studyflow.study.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "questions")
public class Question {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    @Column(name = "question_set_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID questionSetId;

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "owner_id", columnDefinition = "uuid", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "stem", nullable = false, updatable = false)
    private String stem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String optionsJson;

    @Column(name = "correct_index", nullable = false, updatable = false)
    private short correctIndex;

    @Column(name = "explanation", nullable = false, updatable = false)
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", columnDefinition = "varchar(10)", nullable = false, updatable = false)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "bloom_level", columnDefinition = "varchar(20)", nullable = false, updatable = false)
    private BloomLevel bloomLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String citationsJson;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private short sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Question() {
        // JPA
    }

    public Question(UUID questionSetId, UUID documentId, UUID ownerId, String stem, String optionsJson,
            short correctIndex, String explanation, Difficulty difficulty, BloomLevel bloomLevel,
            String citationsJson, short sortOrder) {
        this.questionSetId = questionSetId;
        this.documentId = documentId;
        this.ownerId = ownerId;
        this.stem = stem;
        this.optionsJson = optionsJson;
        this.correctIndex = correctIndex;
        this.explanation = explanation;
        this.difficulty = difficulty;
        this.bloomLevel = bloomLevel;
        this.citationsJson = citationsJson;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuestionSetId() {
        return questionSetId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getStem() {
        return stem;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public short getCorrectIndex() {
        return correctIndex;
    }

    public String getExplanation() {
        return explanation;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public BloomLevel getBloomLevel() {
        return bloomLevel;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
