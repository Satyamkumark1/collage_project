package com.studyflow.tutor.repo;

import com.studyflow.tutor.domain.Conversation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndOwnerId(UUID id, UUID ownerId);

    // Cursor pagination on UUIDv7 ids (time-sortable) — never offset-based, per
    // specs/03-api-and-errors.md.
    List<Conversation> findByDocumentIdAndOwnerIdOrderByIdDesc(UUID documentId, UUID ownerId, Limit limit);

    List<Conversation> findByDocumentIdAndOwnerIdAndIdLessThanOrderByIdDesc(UUID documentId, UUID ownerId,
            UUID cursor, Limit limit);
}
