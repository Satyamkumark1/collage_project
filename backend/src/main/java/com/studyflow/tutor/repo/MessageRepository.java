package com.studyflow.tutor.repo;

import com.studyflow.tutor.domain.Message;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Message> findByConversationIdAndOwnerIdOrderByIdAsc(UUID conversationId, UUID ownerId);

    /** Most recent turns first, for building bounded chat history context — see TutorChatService. */
    List<Message> findByConversationIdAndOwnerIdOrderByIdDesc(UUID conversationId, UUID ownerId, Limit limit);
}
