package com.studyflow.identity.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.config.CookieProperties;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.dto.AccessTokenResponse;
import com.studyflow.identity.dto.LoginRequest;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.dto.RegisterResponse;
import com.studyflow.identity.service.AuthService;
import com.studyflow.identity.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE_NAME = "refresh_token";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final CookieProperties cookieProperties;

    public AuthController(AuthService authService, CookieProperties cookieProperties) {
        this.authService = authService;
        this.cookieProperties = cookieProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        RegisterResponse body = new RegisterResponse(user.getId(), user.getEmail(), user.getName(),
                user.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        AuthService.LoginResult result = authService.login(request.email(), request.password(),
                hash(httpRequest.getHeader("User-Agent")), hash(clientIp(httpRequest)));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildCookie(result.rawRefreshToken()).toString())
                .body(new AccessTokenResponse(result.accessToken()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletRequest httpRequest) {
        if (refreshCookie == null) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "No refresh cookie present");
        }
        AuthService.LoginResult result = authService.refresh(refreshCookie, hash(httpRequest.getHeader("User-Agent")),
                hash(clientIp(httpRequest)));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildCookie(result.rawRefreshToken()).toString())
                .body(new AccessTokenResponse(result.accessToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie) {
        if (refreshCookie != null) {
            authService.logout(refreshCookie);
        }
        ResponseCookie expired = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    private ResponseCookie buildCookie(String rawValue) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawValue)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite(cookieProperties.getSameSite())
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofDays(cookieProperties.getRefreshTtlDays()))
                .build();
    }

    private String hash(String value) {
        return value == null ? null : RefreshTokenService.hash(value);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
