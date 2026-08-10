package com.studyflow.study.service;

import com.studyflow.study.domain.QuestionSet;
import com.studyflow.study.domain.Quiz;
import com.studyflow.study.domain.QuizMode;
import com.studyflow.study.repo.QuizRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link McqGenerationService} — a quiz is a fresh MCQ batch (same
 * difficulty/Bloom/chunk-coverage steering and partial-success contract) plus timing/scoring
 * config derived from {@link QuizMode}. No new generation or validation pipeline. See
 * docs/DECISIONS.md for the mode-to-config mapping.
 */
@Service
public class QuizGenerationService {

    // 90s/question (roughly JEE/NEET MCQ pacing) and JEE/NEET-style -0.25 negative marking for
    // EXAM only — fresh design calls, the master spec's quiz detail was never transcribed into
    // this repo (specs/15-PENDING.md). See docs/DECISIONS.md.
    static final int SECONDS_PER_QUESTION = 90;
    static final BigDecimal EXAM_NEGATIVE_MARKING_FRACTION = new BigDecimal("0.25");
    static final BigDecimal NO_NEGATIVE_MARKING = BigDecimal.ZERO;

    private final McqGenerationService mcqGenerationService;
    private final QuizRepository quizRepository;

    public QuizGenerationService(McqGenerationService mcqGenerationService, QuizRepository quizRepository) {
        this.mcqGenerationService = mcqGenerationService;
        this.quizRepository = quizRepository;
    }

    // Deliberately not @Transactional — mcqGenerationService.generate() itself isn't (the Groq
    // call(s) can take 20-180s and shouldn't hold a DB transaction open); the final quiz-row save
    // is its own short write.
    public Quiz build(UUID documentId, UUID ownerId, UUID jobId, QuizMode mode, int requestedCount) {
        QuestionSet questionSet = mcqGenerationService.generate(documentId, ownerId, jobId, requestedCount);
        short generatedCount = questionSet.getGeneratedCount();

        Integer timeLimitSeconds = mode == QuizMode.REVISION ? null : generatedCount * SECONDS_PER_QUESTION;
        BigDecimal negativeMarkingFraction = mode == QuizMode.EXAM ? EXAM_NEGATIVE_MARKING_FRACTION
                : NO_NEGATIVE_MARKING;

        Quiz quiz = new Quiz(documentId, ownerId, questionSet.getId(), jobId, mode, generatedCount, timeLimitSeconds,
                negativeMarkingFraction);
        return quizRepository.save(quiz);
    }
}
