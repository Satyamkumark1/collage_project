package com.studyflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.dto.RegisterResponse;
import com.studyflow.support.DatabaseCleanerExtension;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ExtendWith(DatabaseCleanerExtension.class)
class AuthFlowIntegrationTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void registerLoginMeRefreshReuseDetectionLogout() {
        String email = "student" + System.nanoTime() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(email, "correct horse battery", "Test Student",
                (short) 2000);

        ResponseEntity<RegisterResponse> registerResponse = restTemplate.postForEntity("/api/v1/auth/register",
                registerRequest, RegisterResponse.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().email()).isEqualToIgnoringCase(email);

        LoginRequest loginRequest = new LoginRequest(email, "correct horse battery");
        ResponseEntity<AccessTokenResponse> loginResponse = restTemplate.postForEntity("/api/v1/auth/login",
                loginRequest, AccessTokenResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().accessToken();
        assertThat(accessToken).isNotBlank();

        String setCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=").contains("HttpOnly");

        HttpHeaders meHeaders = new HttpHeaders();
        meHeaders.setBearerAuth(accessToken);
        ResponseEntity<MeResponse> meResponse = restTemplate.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(meHeaders), MeResponse.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meResponse.getBody().email()).isEqualToIgnoringCase(email);
        assertThat(meResponse.getBody().isMinor()).isFalse();

        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, cookiePair(setCookie));
        refreshHeaders.add("X-Request-Id", "test-" + System.nanoTime());
        ResponseEntity<AccessTokenResponse> refreshResponse = restTemplate.exchange("/api/v1/auth/refresh",
                HttpMethod.POST, new HttpEntity<>(refreshHeaders), AccessTokenResponse.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody().accessToken()).isNotBlank();
        String rotatedCookie = refreshResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(rotatedCookie).contains("refresh_token=");

        // Reuse detection: presenting the now-rotated-away original cookie again must fail
        // and revoke the whole family (spec: AUTH_REFRESH_REUSED).
        ResponseEntity<String> reuseResponse = restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(refreshHeaders), String.class);
        assertThat(reuseResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuseResponse.getBody()).contains("AUTH_REFRESH_REUSED");

        // The rotated (newest) cookie must also now be dead, since reuse revokes the family.
        HttpHeaders rotatedHeaders = new HttpHeaders();
        rotatedHeaders.add(HttpHeaders.COOKIE, cookiePair(rotatedCookie));
        rotatedHeaders.add("X-Request-Id", "test-" + System.nanoTime());
        ResponseEntity<String> deadResponse = restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(rotatedHeaders), String.class);
        assertThat(deadResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Regression test for a real race found via manual browser testing (two tabs, or a page
     * reload firing a silent-refresh while an earlier one is still in flight, both present the
     * same cookie): the loser must get a clean 401 AUTH_REFRESH_REUSED, never an unhandled 500 —
     * see RefreshTokenService.rotate/revokeFamily's comments for the underlying Hibernate-session
     * and Spring-AOP-self-invocation causes and fixes.
     */
    @Test
    void concurrentRefreshWithTheSameCookieNeverProducesA500() throws InterruptedException {
        String email = "race" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Race User", (short) 2000), RegisterResponse.class);
        ResponseEntity<AccessTokenResponse> loginResponse = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        String setCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, cookiePair(setCookie));
        refreshHeaders.add("X-Request-Id", "race-" + System.nanoTime());
        HttpEntity<Void> request = new HttpEntity<>(refreshHeaders);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = List.of(
                    executor.submit(() -> fireAfterBothReady(ready, go, request)),
                    executor.submit(() -> fireAfterBothReady(ready, go, request)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            ResponseEntity<String> firstResponse = futures.get(0).get(10, TimeUnit.SECONDS);
            ResponseEntity<String> secondResponse = futures.get(1).get(10, TimeUnit.SECONDS);
            List<HttpStatusCode> statuses = List.of(firstResponse.getStatusCode(), secondResponse.getStatusCode());
            assertThat(statuses).allMatch(status -> status.equals(HttpStatus.OK) || status.equals(HttpStatus.UNAUTHORIZED),
                    "neither concurrent refresh may ever return a 500");
            assertThat(statuses).contains(HttpStatus.OK);
            assertThat(statuses).contains(HttpStatus.UNAUTHORIZED);

            ResponseEntity<String> unauthorizedResponse = firstResponse.getStatusCode().equals(HttpStatus.UNAUTHORIZED)
                    ? firstResponse
                    : secondResponse;
            assertThat(unauthorizedResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            JsonNode problem = objectMapper.readTree(unauthorizedResponse.getBody());
            assertThat(problem.get("code").asText()).isEqualTo("AUTH_REFRESH_REUSED");
        } catch (Exception e) {
            throw new AssertionError("Concurrent refresh requests failed unexpectedly", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<String> fireAfterBothReady(CountDownLatch ready, CountDownLatch go, HttpEntity<Void> request)
            throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST, request, String.class);
    }

    @Test
    void logoutRevokesTheRefreshCookieSoItCanNoLongerBeUsed() {
        String email = "logout" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Logout User", (short) 2000),
                RegisterResponse.class);
        ResponseEntity<AccessTokenResponse> loginResponse = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "correct horse battery"), AccessTokenResponse.class);
        String setCookie = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        HttpHeaders cookieHeaders = new HttpHeaders();
        cookieHeaders.add(HttpHeaders.COOKIE, cookiePair(setCookie));
        cookieHeaders.add("X-Request-Id", "test-" + System.nanoTime());

        // logout must work on the cookie alone — no Bearer token required, same as refresh.
        ResponseEntity<Void> logoutResponse = restTemplate.exchange("/api/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(cookieHeaders), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> refreshAfterLogout = restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(cookieHeaders), String.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginFailsUniformlyForWrongPasswordAndUnknownEmail() {
        String email = "known" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "correct horse battery", "Known User", (short) 2000),
                RegisterResponse.class);

        ResponseEntity<String> wrongPassword = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "wrong password entirely"), String.class);
        ResponseEntity<String> unknownEmail = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest("nobody" + System.nanoTime() + "@example.com", "whatever password"), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).contains("AUTH_INVALID_CREDENTIALS");
        assertThat(unknownEmail.getBody()).contains("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void registeringTheSameEmailTwiceIsRejected() {
        String email = "dup" + System.nanoTime() + "@example.com";
        RegisterRequest request = new RegisterRequest(email, "correct horse battery", "Dup User", (short) 2000);
        restTemplate.postForEntity("/api/v1/auth/register", request, RegisterResponse.class);

        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/auth/register", request, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("AUTH_EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void shortPasswordFailsValidation() {
        RegisterRequest request = new RegisterRequest("shortpw" + System.nanoTime() + "@example.com", "short1",
                "Short Pw", (short) 2000);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/register", request, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_FAILED");
    }

    private String cookiePair(String setCookieHeader) {
        return setCookieHeader.split(";", 2)[0];
    }
}
