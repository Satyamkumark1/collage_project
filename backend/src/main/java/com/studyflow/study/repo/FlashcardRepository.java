package com.studyflow.study.repo;

import com.studyflow.study.domain.Flashcard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardRepository extends JpaRepository<Flashcard, UUID> {

    Optional<Flashcard> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Flashcard> findByDocumentIdAndOwnerIdOrderByCreatedAtDesc(UUID documentId, UUID ownerId);

    // Not cursor-paginated (unlike GET /jobs) — a due-now queue is inherently dynamic (each
    // review reschedules the card that was just shown), so "the next N due" is a fresher, more
    // correct model than paging through a fixed snapshot. See docs/DECISIONS.md.
    List<Flashcard> findByOwnerIdAndDueAtLessThanEqualOrderByDueAtAsc(UUID ownerId, Instant now, Limit limit);
}
