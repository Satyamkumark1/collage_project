package com.studyflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.service.LoginLockStore;
import com.studyflow.support.DatabaseCleanerExtension;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The L1 login-attempt limiter (see LoginRateLimiter, docs/DECISIONS.md) exercised over real
 * HTTP against real Postgres. No real external AI-provider calls happen on the auth path, so
 * this is fully deterministic — no rate-limit-tier caveat applies here (unlike the AI-provider
 * integration tests elsewhere in this suite).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class LoginRateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoginLockStore loginLockStore;

    /**
     * Proves the L2 durability property against the real Upstash instance: a lock L1's in-memory
     * map never saw (simulating what a restart would wipe from L1) still blocks login, because
     * checkNotLocked falls through to L2 when L1 has no record — see docs/DECISIONS.md.
     */
    @Test
    void aLockKnownOnlyToRedisStillBlocksLogin() {
        String email = "redislock" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Redis Lock Student", (short) 2000),
                Object.class);

        loginLockStore.lock(email, Duration.ofSeconds(30));

        ResponseEntity<Map<String, Object>> attempt = attemptLogin(email, "correct horse battery");
        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(attempt.getBody()).containsEntry("code", "RATE_LIMITED");
    }

    @Test
    void fiveFailedAttemptsAgainstARealAccountLocksItOut() {
        String email = "ratelimit" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Rate Limit Student", (short) 2000),
                Object.class);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map<String, Object>> attempt = attemptLogin(email, "wrong password");
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map<String, Object>> sixthAttempt = attemptLogin(email, "wrong password");
        assertThat(sixthAttempt.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixthAttempt.getBody()).containsEntry("code", "RATE_LIMITED");
        assertThat(sixthAttempt.getBody()).containsKey("retryAfterSeconds");
        assertThat(sixthAttempt.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void fiveFailedAttemptsAgainstANonexistentEmailAlsoLocksItOut() {
        String email = "never-registered" + System.nanoTime() + "@example.com";

        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map<String, Object>> attempt = attemptLogin(email, "whatever");
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map<String, Object>> sixthAttempt = attemptLogin(email, "whatever");
        assertThat(sixthAttempt.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixthAttempt.getBody()).containsEntry("code", "RATE_LIMITED");
    }

    private ResponseEntity<Map<String, Object>> attemptLogin(String email, String password) {
        return restTemplate.exchange("/api/v1/auth/login", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new LoginRequest(email, password), jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
