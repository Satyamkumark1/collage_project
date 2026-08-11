package com.studyflow.identity.oauth;

import com.studyflow.identity.config.CookieProperties;
import com.studyflow.identity.service.AuthService;
import com.studyflow.identity.service.RefreshTokenService;
import com.studyflow.identity.web.AuthController;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes an OAuth2Login handshake exactly like a password login: issues the same
 * access-token + HttpOnly refresh-cookie pair ({@link AuthService#oauthLogin}), then redirects
 * to the frontend — no dedicated callback page, {@code AuthContext}'s mount-time
 * {@code apiRefresh()} picks the fresh cookie up. See docs/DECISIONS.md.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final AuthService authService;
    private final CookieProperties cookieProperties;
    private final OAuth2UserInfoResolver userInfoResolver;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final String frontendRedirectOrigin;

    public OAuth2LoginSuccessHandler(AuthService authService, CookieProperties cookieProperties,
            OAuth2UserInfoResolver userInfoResolver, OAuth2AuthorizedClientService authorizedClientService,
            @Value("${studyflow.oauth2.frontend-redirect-origin}") String frontendRedirectOrigin) {
        this.authService = authService;
        this.cookieProperties = cookieProperties;
        this.userInfoResolver = userInfoResolver;
        this.authorizedClientService = authorizedClientService;
        this.frontendRedirectOrigin = frontendRedirectOrigin;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        // This runs inside the security filter chain, not a @RestController — an uncaught
        // exception here never reaches @ControllerAdvice; it propagates as a raw, unhandled
        // error instead of the clean "/login?error=oauth_failed" redirect the failure handler
        // gives password-login-style failures. Catch broadly and redirect the same way.
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            String registrationId = token.getAuthorizedClientRegistrationId();
            OAuth2User oAuth2User = token.getPrincipal();
            OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(registrationId,
                    token.getName());

            OAuthUserInfo userInfo = userInfoResolver.resolve(registrationId, oAuth2User, authorizedClient);

            String userAgentHash = hash(request.getHeader("User-Agent"));
            String ipHash = hash(clientIp(request));
            AuthService.LoginResult result = authService.oauthLogin(userInfo.email(), userInfo.name(), userAgentHash,
                    ipHash);

            ResponseCookie cookie = ResponseCookie.from(AuthController.REFRESH_COOKIE_NAME, result.rawRefreshToken())
                    .httpOnly(true)
                    .secure(cookieProperties.isSecure())
                    .sameSite(cookieProperties.getSameSite())
                    .path(AuthController.REFRESH_COOKIE_PATH)
                    .maxAge(Duration.ofDays(cookieProperties.getRefreshTtlDays()))
                    .build();
            response.addHeader("Set-Cookie", cookie.toString());
            response.sendRedirect(frontendRedirectOrigin + "/library");
        } catch (RuntimeException e) {
            log.warn("OAuth2 login completion failed: {}", e.getMessage());
            response.sendRedirect(frontendRedirectOrigin + "/login?error=oauth_failed");
        }
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
