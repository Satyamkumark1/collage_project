package com.studyflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.dto.RegisterResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AuthFlowIntegrationTest {

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
        ResponseEntity<String> deadResponse = restTemplate.exchange("/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(rotatedHeaders), String.class);
        assertThat(deadResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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
