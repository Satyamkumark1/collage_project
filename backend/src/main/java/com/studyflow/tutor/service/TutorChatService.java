package com.studyflow.tutor.service;

import com.studyflow.ai.AiCompletionRequest;
import com.studyflow.ai.AiCompletionResult;
import com.studyflow.ai.AiProvider;
import com.studyflow.ai.domain.AiOutcome;
import com.studyflow.ai.prompt.PromptRegistry;
import com.studyflow.ai.service.AiCallLogger;
import com.studyflow.rag.service.RetrievalService;
import com.studyflow.rag.service.RetrievalService.RetrievedChunk;
import com.studyflow.tutor.domain.Conversation;
import com.studyflow.tutor.domain.Message;
import com.studyflow.tutor.domain.MessageRole;
import com.studyflow.tutor.repo.MessageRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives one tutor turn end to end: retrieval, the confidence-floor grounding decision, the
 * streamed Groq call, mechanical citation extraction, and persistence — see specs/09-rag.md
 * §Grounding contract for the tutor, docs/DECISIONS.md for every parameter's rationale. Runs
 * entirely off the request thread (see {@code TutorChatController}); never throws — every path
 * ends by completing (or erroring) the given {@link SseEmitter}.
 */
@Service
public class TutorChatService {

    private static final Logger log = LoggerFactory.getLogger(TutorChatService.class);
    private static final String PURPOSE = "tutor";
    private static final int PROMPT_VERSION = 1;
    private static final int MAX_OUTPUT_TOKENS = 900;
    private static final int HISTORY_TURNS = 6;
    private static final double CONFIDENCE_FLOOR = 0.35;
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d{1,10})]");
    private static final String REFUSAL_TEXT = "I couldn't find anything about this in your uploaded notes. Try "
            + "rephrasing, or turn on \"explain beyond my notes\" for a general-knowledge answer instead.";

    private final RetrievalService retrievalService;
    private final AiProvider aiProvider;
    private final PromptRegistry promptRegistry;
    private final AiCallLogger aiCallLogger;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final String tutorModel;

    public TutorChatService(RetrievalService retrievalService, AiProvider aiProvider, PromptRegistry promptRegistry,
            AiCallLogger aiCallLogger, MessageRepository messageRepository, ObjectMapper objectMapper,
            @Value("${studyflow.ai.groq.models.tutor}") String tutorModel) {
        this.retrievalService = retrievalService;
        this.aiProvider = aiProvider;
        this.promptRegistry = promptRegistry;
        this.aiCallLogger = aiCallLogger;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.tutorModel = tutorModel;
    }

    public void streamReply(Conversation conversation, UUID ownerId, String userContent, boolean explainBeyondNotes,
            SseEmitter emitter) {
        RetrievalService.RetrievalResult retrieval;
        try {
            List<Message> recentFirst = messageRepository.findByConversationIdAndOwnerIdOrderByIdDesc(
                    conversation.getId(), ownerId, Limit.of(HISTORY_TURNS));
            List<Message> history = new ArrayList<>(recentFirst);
            Collections.reverse(history);

            messageRepository.save(Message.userTurn(conversation.getId(), ownerId, userContent));

            retrieval = retrievalService.retrieve(conversation.getDocumentId(), ownerId, userContent);
            boolean floorPassed = retrieval.bestVectorSimilarity() >= CONFIDENCE_FLOOR;
            if (!explainBeyondNotes && !floorPassed) {
                sendRefusal(conversation, ownerId, emitter);
                return;
            }
            boolean grounded = !explainBeyondNotes;

            List<AiCompletionRequest.Message> messages = new ArrayList<>();
            messages.add(new AiCompletionRequest.Message("system",
                    promptRegistry.load(PURPOSE, PROMPT_VERSION).content()));
            for (Message turn : history) {
                String role = turn.getRole() == MessageRole.USER ? "user" : "assistant";
                messages.add(new AiCompletionRequest.Message(role, turn.getContent()));
            }
            messages.add(new AiCompletionRequest.Message("user",
                    buildContextAndQuestion(retrieval.chunks(), userContent, explainBeyondNotes)));

            AiCompletionRequest request = new AiCompletionRequest(PURPOSE, PROMPT_VERSION, messages, tutorModel,
                    MAX_OUTPUT_TOKENS, 0.4, false, ownerId, null);

            aiProvider.streamComplete(request, new AiProvider.StreamListener() {
                @Override
                public void onToken(String delta) {
                    trySend(emitter, SseEmitter.event().name("token").data(Map.of("delta", delta)));
                }

                @Override
                public void onComplete(AiCompletionResult result) {
                    try {
                        finalizeAssistantMessage(conversation, ownerId, result, retrieval.chunks(), grounded,
                                explainBeyondNotes, emitter);
                    } catch (RuntimeException e) {
                        log.warn("Failed to finalize tutor turn for conversation {}: {}", conversation.getId(),
                                e.getMessage());
                        aiCallLogger.log(ownerId, null, "groq", tutorModel, PURPOSE, PROMPT_VERSION,
                                result.tokensIn(), result.tokensOut(), (int) result.latencyMs(), result.finishReason(),
                                AiOutcome.ERROR, 1);
                        sendError(emitter, "AI_PROVIDER_UNAVAILABLE");
                    }
                }

                @Override
                public void onError(RuntimeException error) {
                    log.warn("Groq streaming call failed for conversation {}: {}", conversation.getId(),
                            error.getMessage());
                    aiCallLogger.log(ownerId, null, "groq", tutorModel, PURPOSE, PROMPT_VERSION, null, null, null,
                            null, AiOutcome.ERROR, 1);
                    sendError(emitter, "AI_PROVIDER_UNAVAILABLE");
                }
            });
        } catch (RuntimeException e) {
            log.warn("Tutor turn failed for conversation {}: {}", conversation.getId(), e.getMessage());
            sendError(emitter, "AI_PROVIDER_UNAVAILABLE");
        }
    }

    private void finalizeAssistantMessage(Conversation conversation, UUID ownerId, AiCompletionResult result,
            List<RetrievedChunk> candidateChunks, boolean grounded, boolean explainBeyondNotes, SseEmitter emitter) {
        List<TutorCitation> citations = extractCitations(result.content(), candidateChunks);
        String citationsJson = objectMapper.writeValueAsString(citations);

        Message assistantMessage = messageRepository.save(Message.assistantTurn(conversation.getId(), ownerId,
                result.content(), citationsJson, grounded, explainBeyondNotes, result.model(), PROMPT_VERSION));

        aiCallLogger.log(ownerId, null, "groq", tutorModel, PURPOSE, PROMPT_VERSION, result.tokensIn(),
                result.tokensOut(), (int) result.latencyMs(), result.finishReason(), AiOutcome.OK, 1);

        trySend(emitter, SseEmitter.event().name("done").data(Map.of(
                "messageId", assistantMessage.getId().toString(),
                "citations", citations,
                "grounded", grounded,
                "beyondNotes", explainBeyondNotes)));
        emitter.complete();
    }

    private void sendRefusal(Conversation conversation, UUID ownerId, SseEmitter emitter) {
        aiCallLogger.log(ownerId, null, "groq", tutorModel, PURPOSE, PROMPT_VERSION, null, null, null, null,
                AiOutcome.REFUSED, 1);
        Message assistantMessage = messageRepository.save(Message.assistantTurn(conversation.getId(), ownerId,
                REFUSAL_TEXT, "[]", false, false, null, PROMPT_VERSION));
        trySend(emitter, SseEmitter.event().name("token").data(Map.of("delta", REFUSAL_TEXT)));
        trySend(emitter, SseEmitter.event().name("done").data(Map.of(
                "messageId", assistantMessage.getId().toString(),
                "citations", List.of(),
                "grounded", false,
                "beyondNotes", false)));
        emitter.complete();
    }

    private void sendError(SseEmitter emitter, String code) {
        trySend(emitter, SseEmitter.event().name("error").data(Map.of("code", code)));
        emitter.complete();
    }

    private List<TutorCitation> extractCitations(String content, List<RetrievedChunk> candidateChunks) {
        Set<Integer> markers = new LinkedHashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(content);
        while (matcher.find()) {
            try {
                int marker = Integer.parseInt(matcher.group(1));
                if (marker >= 1 && marker <= candidateChunks.size()) {
                    markers.add(marker);
                }
            } catch (NumberFormatException e) {
                log.debug("Ignoring oversized tutor citation marker '{}'", matcher.group(1));
            }
        }
        return markers.stream()
                .map(marker -> {
                    RetrievedChunk chunk = candidateChunks.get(marker - 1);
                    return new TutorCitation(marker, chunk.chunkId(), chunk.pageFrom(), chunk.pageTo(),
                            chunk.sectionPath());
                })
                .toList();
    }

    private String buildContextAndQuestion(List<RetrievedChunk> chunks, String question, boolean explainBeyondNotes) {
        StringBuilder sb = new StringBuilder();
        if (!chunks.isEmpty()) {
            sb.append("<<<NOTES>>>\n");
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                sb.append('[').append(i + 1).append("] ");
                if (chunk.sectionPath() != null) {
                    sb.append(chunk.sectionPath()).append(' ');
                }
                if (chunk.pageFrom() != null) {
                    sb.append("(p. ").append(chunk.pageFrom());
                    if (chunk.pageTo() != null && !chunk.pageTo().equals(chunk.pageFrom())) {
                        sb.append('-').append(chunk.pageTo());
                    }
                    sb.append(')');
                }
                sb.append('\n').append(chunk.content()).append("\n\n");
            }
            sb.append("<<<END_NOTES>>>\n\n");
        }
        sb.append(explainBeyondNotes
                ? "The student has allowed answers beyond their notes for this question. Prefer the notes above "
                        + "when relevant (cite them), and clearly flag anything you add from general knowledge.\n\n"
                : "Answer using ONLY the notes above, citing them inline like [1], [2]. If the notes don't cover "
                        + "this, say so plainly instead of guessing.\n\n");
        sb.append("Question: ").append(question);
        return sb.toString();
    }

    private void trySend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not send SSE event (client likely disconnected): {}", e.getMessage());
        }
    }
}
