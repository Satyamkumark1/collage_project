package com.studyflow.identity.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import java.time.Year;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DpdpGuardTest {

    private final DpdpGuard guard = new DpdpGuard();
    private final int currentYear = Year.now(ZoneId.of("Asia/Kolkata")).getValue();

    @Test
    void blocksMinorWithoutGuardianConsent() {
        User minor = new User("minor@example.com", "hash", "Minor User", (short) (currentYear - 16));

        assertThatThrownBy(() -> guard.requireConsentIfMinor(minor))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AUTH_GUARDIAN_CONSENT_REQUIRED);
    }

    @Test
    void allowsAdultUser() {
        User adult = new User("adult@example.com", "hash", "Adult User", (short) (currentYear - 20));

        assertThatCode(() -> guard.requireConsentIfMinor(adult)).doesNotThrowAnyException();
    }

    @Test
    void allowsMinorWithRecordedGuardianConsent() {
        User minor = new User("consented@example.com", "hash", "Consented Minor", (short) (currentYear - 16));
        minor.recordGuardianConsent();

        assertThatCode(() -> guard.requireConsentIfMinor(minor)).doesNotThrowAnyException();
    }

    @Test
    void blocksOauthUserWithoutBirthYearYet() {
        User oauthUser = new User("oauth@example.com", "hash", "OAuth User", null);

        assertThatThrownBy(() -> guard.requireConsentIfMinor(oauthUser))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.AUTH_BIRTH_YEAR_REQUIRED);
    }
}
