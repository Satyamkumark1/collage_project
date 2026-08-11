package com.studyflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.studyflow.ai.repo.AiCallRepository;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.rag.repo.DocumentChunkRepository;
import com.studyflow.study.repo.FlashcardRepository;
import com.studyflow.study.repo.KeyPointRepository;
import com.studyflow.study.repo.QuestionRepository;
import com.studyflow.planner.repo.StudyPlanRepository;
import com.studyflow.planner.repo.StudySessionRepository;
import com.studyflow.study.repo.QuestionSetRepository;
import com.studyflow.study.repo.QuizAnswerRepository;
import com.studyflow.study.repo.QuizAttemptRepository;
import com.studyflow.study.repo.QuizRepository;
import com.studyflow.study.repo.SummaryRepository;
import com.studyflow.tutor.repo.ConversationRepository;
import com.studyflow.tutor.repo.MessageRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.UUID;

/**
 * The #1 IDOR defence for this app (see specs/02-data-model.md §Tenancy enforcement,
 * specs/14-security-privacy-compliance.md §Threat model): owner-scoped repositories must be
 * accessed via {@code findByIdAndOwnerId(id, ownerId)} — a bare {@code findById} called from
 * outside the repository's own package is a fail-the-build violation, not a review comment.
 *
 * <p>To verify this test actually catches a violation: temporarily add a call like
 * {@code documentRepository.findById(someId)} anywhere outside {@code library.repo} on a scratch
 * branch, confirm this test fails, then revert.
 */
@AnalyzeClasses(packages = "com.studyflow", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule documentRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.library.repo")
            .should().callMethod(DocumentRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule documentChunkRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.rag.repo")
            .should().callMethod(DocumentChunkRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule aiJobRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.jobs.repo")
            .should().callMethod(AiJobRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId, or findByIdForInternalProcessing for "
                    + "the job engine's own worker-thread processing of a system-claimed id — "
                    + "see specs/02-data-model.md");

    @ArchTest
    static final ArchRule aiCallRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.ai.repo")
            .should().callMethod(AiCallRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule summaryRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(SummaryRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule keyPointRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(KeyPointRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule questionSetRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(QuestionSetRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule questionRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(QuestionRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule flashcardRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(FlashcardRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(QuizRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizAttemptRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(QuizAttemptRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizAnswerRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.study.repo")
            .should().callMethod(QuizAnswerRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule studyPlanRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.planner.repo")
            .should().callMethod(StudyPlanRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule studySessionRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.planner.repo")
            .should().callMethod(StudySessionRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule conversationRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.tutor.repo")
            .should().callMethod(ConversationRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    @ArchTest
    static final ArchRule messageRepositoryIsOwnerScoped = noClasses()
            .that().resideOutsideOfPackage("com.studyflow.tutor.repo")
            .should().callMethod(MessageRepository.class, "findById", Object.class)
            .because("callers must use findByIdAndOwnerId instead — see specs/02-data-model.md");

    // The rules above only ban the unscoped escape hatch; they don't verify the scoped
    // replacement actually exists. Without this, a repository could lose findByIdAndOwnerId
    // entirely (e.g. during a refactor) and every rule above would still pass.
    @ArchTest
    static final ArchRule documentRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(DocumentRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule documentChunkRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(DocumentChunkRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule aiJobRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(AiJobRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule aiCallRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(AiCallRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule summaryRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(SummaryRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule keyPointRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(KeyPointRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule questionSetRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(QuestionSetRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule questionRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(QuestionRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule flashcardRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(FlashcardRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(QuizRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizAttemptRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(QuizAttemptRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule quizAnswerRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(QuizAnswerRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule studyPlanRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(StudyPlanRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule studySessionRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(StudySessionRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule conversationRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(ConversationRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    @ArchTest
    static final ArchRule messageRepositoryDeclaresOwnerScopedFinder = classes()
            .that().areAssignableTo(MessageRepository.class)
            .should(declareOwnerScopedFindById())
            .because("owner-scoped repositories must expose findByIdAndOwnerId(UUID, UUID) — see "
                    + "specs/02-data-model.md");

    private static ArchCondition<JavaClass> declareOwnerScopedFindById() {
        return new ArchCondition<>("declare findByIdAndOwnerId(UUID, UUID)") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean declared = javaClass.tryGetMethod("findByIdAndOwnerId", UUID.class, UUID.class).isPresent();
                String message = javaClass.getFullName()
                        + (declared ? " declares findByIdAndOwnerId(UUID, UUID)"
                                : " does not declare findByIdAndOwnerId(UUID, UUID)");
                events.add(new SimpleConditionEvent(javaClass, declared, message));
            }
        };
    }
}
