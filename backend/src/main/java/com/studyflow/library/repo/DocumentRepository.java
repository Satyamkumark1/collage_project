package com.studyflow.library.repo;

import com.studyflow.library.domain.Document;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId);

    Optional<Document> findByOwnerIdAndContentSha256AndDeletedAtIsNull(UUID ownerId, String contentSha256);

    List<Document> findByOwnerIdAndDeletedAtIsNullOrderByIdDesc(UUID ownerId, Limit limit);

    List<Document> findByOwnerIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(UUID ownerId, UUID cursor,
            Limit limit);
}
