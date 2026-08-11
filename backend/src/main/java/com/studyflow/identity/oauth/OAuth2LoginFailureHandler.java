package com.studyflow.identity.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final String frontendRedirectOrigin;

    public OAuth2LoginFailureHandler(
            @Value("${studyflow.oauth2.frontend-redirect-origin}") String frontendRedirectOrigin) {
        this.frontendRedirectOrigin = frontendRedirectOrigin;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        response.sendRedirect(frontendRedirectOrigin + "/login?error=oauth_failed");
    }
}
