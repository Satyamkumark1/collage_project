package com.studyflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.studyflow.ai.repo.AiCallRepository;
import com.studyflow.jobs.repo.AiJobRepository;
import com.studyflow.library.repo.DocumentRepository;
import com.studyflow.rag.repo.DocumentChunkRepository;
import com.studyflow.study.repo.SummaryRepository;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
