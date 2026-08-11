package com.studyflow.identity.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import org.springframework.stereotype.Component;

/**
 * DPDP Act 2023 age gate (see specs/04-identity-and-security.md §DPDP age gate). Called from
 * every AI-feature endpoint (upload/ingestion, summary generation, and later MCQs/flashcards/
 * quizzes/tutor) — under-18 users without recorded guardian consent are blocked. Consent
 * *collection* UX is a documented deferral (docs/DECISIONS.md); this structural block is not.
 */
@Component
public class DpdpGuard {

    public void requireConsentIfMinor(User user) {
        if (!user.hasBirthYear()) {
            throw new ApiException(ErrorCode.AUTH_BIRTH_YEAR_REQUIRED,
                    "Confirm your birth year before using AI features");
        }
        if (user.isMinor() && !user.hasGuardianConsent()) {
            throw new ApiException(ErrorCode.AUTH_GUARDIAN_CONSENT_REQUIRED,
                    "Users under 18 require verifiable parental consent before using AI features");
        }
    }
}
