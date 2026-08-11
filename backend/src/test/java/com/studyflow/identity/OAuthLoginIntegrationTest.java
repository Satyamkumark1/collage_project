package com.studyflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.domain.User;
import com.studyflow.identity.dto.CompleteBirthYearRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.dto.RegisterResponse;
import com.studyflow.identity.repo.UserRepository;
import com.studyflow.identity.service.AuthService;
import com.studyflow.support.DatabaseCleanerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * OAuth2Login itself needs a real browser-driven consent screen (see the plan's Verification
 * section) — what's testable here is what actually matters: AuthService.oauthLogin's
 * find-vs-create behaviour and the birthYearRequired gate it leaves behind, against real
 * Postgres. {@link com.studyflow.identity.oauth.OAuth2UserInfoResolverTest} covers the GitHub
 * verified-email fallback separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class OAuthLoginIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void firstOauthSignInCreatesAnAccountWithoutBirthYearAndFlagsItOnMe() {
        String email = "oauth" + System.nanoTime() + "@example.com";

        AuthService.LoginResult result = authService.oauthLogin(email, "OAuth User", "ua-hash", "ip-hash");
        assertThat(result.user().hasBirthYear()).isFalse();
        assertThat(result.user().getEmail()).isEqualToIgnoringCase(email);

        HttpHeaders meHeaders = new HttpHeaders();
        meHeaders.setBearerAuth(result.accessToken());
        ResponseEntity<MeResponse> meResponse = restTemplate.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(meHeaders), MeResponse.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody().birthYearRequired()).isTrue();
        assertThat(meResponse.getBody().isMinor()).isFalse();
        assertThat(meResponse.getBody().guardianConsentRequired()).isFalse();

        ResponseEntity<MeResponse> patchResponse = restTemplate.exchange("/api/v1/me/birth-year", HttpMethod.PATCH,
                new HttpEntity<>(new CompleteBirthYearRequest((short) 2000), meHeaders), MeResponse.class);
        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResponse.getBody().birthYearRequired()).isFalse();

        User persisted = userRepository.findByIdAndDeletedAtIsNull(result.user().getId()).orElseThrow();
        assertThat(persisted.getBirthYear()).isEqualTo((short) 2000);
    }

    @Test
    void oauthSignInWithAnEmailThatAlreadyHasAPasswordAccountSignsIntoTheSameAccount() {
        String email = "existing" + System.nanoTime() + "@example.com";
        ResponseEntity<RegisterResponse> registerResponse = restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Existing User", (short) 1995),
                RegisterResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        AuthService.LoginResult result = authService.oauthLogin(email, "Existing User", "ua-hash", "ip-hash");
        assertThat(result.user().getId()).isEqualTo(registerResponse.getBody().id());
        assertThat(result.user().hasBirthYear()).isTrue();
    }
}
