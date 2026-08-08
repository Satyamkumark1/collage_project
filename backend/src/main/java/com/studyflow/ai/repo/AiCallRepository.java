package com.studyflow.ai.repo;

import com.studyflow.ai.domain.AiCall;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallRepository extends JpaRepository<AiCall, UUID> {

    Optional<AiCall> findByIdAndOwnerId(UUID id, UUID ownerId);
}
