package com.studyflow.study.web;

import com.studyflow.study.domain.QuizAttempt;
import com.studyflow.study.dto.AnswerRequest;
import com.studyflow.study.dto.AnswerResponse;
import com.studyflow.study.dto.QuizAttemptResponse;
import com.studyflow.study.dto.QuizResultResponse;
import com.studyflow.study.service.QuizAttemptService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Quiz-taking lifecycle: pure synchronous CRUD (no LLM calls, no Idempotency-Key — same cost
 * class as a flashcard review), server-authoritative timing enforced entirely inside
 * {@link QuizAttemptService}. See docs/DECISIONS.md.
 */
@RestController
@RequestMapping("/api/v1")
public class QuizAttemptController {

    private final QuizAttemptService quizAttemptService;
    private final ObjectMapper objectMapper;

    public QuizAttemptController(QuizAttemptService quizAttemptService, ObjectMapper objectMapper) {
        this.quizAttemptService = quizAttemptService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/quizzes/{quizId}/attempts")
    public ResponseEntity<QuizAttemptResponse> start(@PathVariable UUID quizId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        QuizAttempt attempt = quizAttemptService.start(quizId, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(QuizAttemptResponse.from(attempt));
    }

    @GetMapping("/quizzes/{quizId}/attempts")
    public List<QuizAttemptResponse> listForQuiz(@PathVariable UUID quizId, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return quizAttemptService.listForQuiz(quizId, ownerId).stream().map(QuizAttemptResponse::from).toList();
    }

    @GetMapping("/quiz-attempts/{id}")
    public QuizAttemptResponse get(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return QuizAttemptResponse.from(quizAttemptService.get(id, ownerId));
    }

    @GetMapping("/quiz-attempts/{id}/answers")
    public List<AnswerResponse> listAnswers(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return quizAttemptService.listAnswers(id, ownerId).stream().map(AnswerResponse::from).toList();
    }

    @PutMapping("/quiz-attempts/{id}/answers/{questionId}")
    public AnswerResponse saveAnswer(@PathVariable UUID id, @PathVariable UUID questionId,
            @Valid @RequestBody AnswerRequest request, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        Short selectedIndex = request.selectedIndex() == null ? null : request.selectedIndex().shortValue();
        return AnswerResponse.from(quizAttemptService.saveAnswer(id, questionId, ownerId, selectedIndex));
    }

    @PostMapping("/quiz-attempts/{id}/submit")
    public QuizResultResponse submit(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return QuizResultResponse.from(quizAttemptService.submit(id, ownerId), objectMapper);
    }

    @GetMapping("/quiz-attempts/{id}/result")
    public QuizResultResponse result(@PathVariable UUID id, Authentication authentication) {
        UUID ownerId = UUID.fromString(authentication.getName());
        return QuizResultResponse.from(quizAttemptService.result(id, ownerId), objectMapper);
    }
}
