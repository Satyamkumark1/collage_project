package com.studyflow.tutor.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.common.quota.QuotaService;
import com.studyflow.common.quota.UsageMetric;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.service.DpdpGuard;
import com.studyflow.identity.service.IdentityService;
import com.studyflow.library.domain.Document;
import com.studyflow.library.domain.DocumentStatus;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.tutor.domain.Conversation;
import com.studyflow.tutor.dto.ConversationResponse;
import com.studyflow.tutor.dto.MessageResponse;
import com.studyflow.tutor.dto.SendMessageRequest;
import com.studyflow.tutor.repo.ConversationRepository;
import com.studyflow.tutor.repo.MessageRepository;
import com.studyflow.tutor.service.TutorChatService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Tutor chat is synchronous streaming, not an async job — see specs/01-architecture.md's
 * sync/async boundary table ("Tutor chat | Streaming SSE | First token < 2.5s"). The actual
 * Groq round-trip and DB writes run on {@link #streamExecutor}, off the servlet thread that
 * returns the {@link SseEmitter} — the same "never block a browser-waited thread on an LLM call"
 * rule CLAUDE.md states for the job engine applies here too, just via async-servlet + a
 * dedicated pool instead of the {@code ai_jobs} table.
 */
@RestController
@RequestMapping("/api/v1")
public class TutorChatController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();

    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final IdentityService identityService;
    private final DpdpGuard dpdpGuard;
    private final QuotaService quotaService;
    private final TutorChatService tutorChatService;
    private final ObjectMapper objectMapper;
    private final long tutorMessagesPerDay;
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    private final Set<SseEmitter> activeEmitters = ConcurrentHashMap.newKeySet();

    public TutorChatController(DocumentRepository documentRepository, ConversationRepository conversationRepository,
            MessageRepository messageRepository, IdentityService identityService, DpdpGuard dpdpGuard,
            QuotaService quotaService, TutorChatService tutorChatService, ObjectMapper objectMapper,
            @Value("${studyflow.quota.tutor-messages-per-day}") long tutorMessagesPerDay) {
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.identityService = identityService;
        this.dpdpGuard = dpdpGuard;
        this.quotaService = quotaService;
        this.tutorChatService = tutorChatService;
        this.objectMapper = objectMapper;
        this.tutorMessagesPerDay = tutorMessagesPerDay;
    }

    public record ConversationListResponse(List<ConversationResponse> items, UUID nextCursor) {
    }

    @PostMapping("/documents/{documentId}/conversations")
    public ResponseEntity<ConversationResponse> create(@PathVariable UUID documentId, Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));
        Document document = requireReadyDocument(documentId, ownerId);

        Conversation conversation = conversationRepository.save(new Conversation(document.getId(), ownerId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(conversation));
    }

    @GetMapping("/documents/{documentId}/conversations")
    public ConversationListResponse list(@PathVariable UUID documentId, Authentication authentication,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) UUID cursor) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
        }
        UUID ownerId = ownerId(authentication);
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        Limit limitObj = Limit.of(effectiveLimit + 1);
        List<Conversation> page = cursor == null
                ? conversationRepository.findByDocumentIdAndOwnerIdOrderByIdDesc(documentId, ownerId, limitObj)
                : conversationRepository.findByDocumentIdAndOwnerIdAndIdLessThanOrderByIdDesc(documentId, ownerId,
                        cursor, limitObj);

        boolean hasMore = page.size() > effectiveLimit;
        List<Conversation> pageItems = hasMore ? page.subList(0, effectiveLimit) : page;
        UUID nextCursor = hasMore ? pageItems.get(pageItems.size() - 1).getId() : null;

        return new ConversationListResponse(pageItems.stream().map(ConversationResponse::from).toList(), nextCursor);
    }

    @GetMapping("/conversations/{id}")
    public ConversationResponse get(@PathVariable UUID id, Authentication authentication) {
        return ConversationResponse.from(requireConversation(id, ownerId(authentication)));
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageResponse> messages(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        requireConversation(id, ownerId);
        return messageRepository.findByConversationIdAndOwnerIdOrderByIdAsc(id, ownerId).stream()
                .map(m -> MessageResponse.from(m, objectMapper))
                .toList();
    }

    /**
     * No {@code Idempotency-Key} — that convention is for POSTs that create a job or a payment
     * (specs/03-api-and-errors.md); this creates neither. A retried send is just a second
     * question, same as re-sending a chat message anywhere else.
     */
    @PostMapping(path = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest body,
            Authentication authentication) {
        UUID ownerId = ownerId(authentication);
        dpdpGuard.requireConsentIfMinor(currentUser(ownerId));
        Conversation conversation = requireConversation(id, ownerId);
        requireReadyDocument(conversation.getDocumentId(), ownerId);
        quotaService.incrementAndEnforceDaily(ownerId, UsageMetric.TUTOR_MESSAGES, tutorMessagesPerDay,
                ErrorCode.QUOTA_AI_EXCEEDED);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        registerEmitter(emitter);
        try {
            streamExecutor.execute(() -> {
                try {
                    tutorChatService.streamReply(conversation, ownerId, body.content(), body.explainBeyondNotes(),
                            emitter);
                } finally {
                    activeEmitters.remove(emitter);
                }
            });
        } catch (RejectedExecutionException e) {
            activeEmitters.remove(emitter);
            throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "Tutor stream capacity is full");
        }
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                failOpenEmitters();
                streamExecutor.shutdownNow();
                streamExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOpenEmitters();
            streamExecutor.shutdownNow();
        }
    }

    private void registerEmitter(SseEmitter emitter) {
        activeEmitters.add(emitter);
        emitter.onCompletion(() -> activeEmitters.remove(emitter));
        emitter.onTimeout(() -> {
            if (activeEmitters.remove(emitter)) {
                emitter.completeWithError(new IllegalStateException("Tutor response timed out"));
            }
        });
        emitter.onError(throwable -> activeEmitters.remove(emitter));
    }

    private void failOpenEmitters() {
        for (SseEmitter emitter : List.copyOf(activeEmitters)) {
            try {
                emitter.completeWithError(new IllegalStateException("Tutor chat is shutting down"));
            } catch (RuntimeException ignored) {
                // If the client is already gone or the emitter is already terminal, there's
                // nothing left to clean up.
            }
        }
        activeEmitters.clear();
    }

    private Conversation requireConversation(UUID id, UUID ownerId) {
        return conversationRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "No conversation with that id"));
    }

    private Document requireReadyDocument(UUID documentId, UUID ownerId) {
        Document document = documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .filter(doc -> doc.getDeletedAt() == null)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "No document with that id"));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_READY, "Document ingestion has not finished yet");
        }
        return document;
    }

    private UUID ownerId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    private User currentUser(UUID userId) {
        return identityService.requireActiveUser(userId);
    }
}
