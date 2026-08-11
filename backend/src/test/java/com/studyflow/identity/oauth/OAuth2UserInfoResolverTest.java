package com.studyflow.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.studyflow.common.error.ApiException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Fake-server test for the one piece of real logic in the OAuth login slice: GitHub's private-
 * email fallback (real Google/GitHub calls can't be driven from an automated test — need a real
 * browser consent screen, see the plan's Verification section).
 */
class OAuth2UserInfoResolverTest {

    private MockWebServer server;
    private OAuth2UserInfoResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        resolver = new OAuth2UserInfoResolver(server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void googleResolvesDirectlyFromProfileAttributes() {
        OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "123", "email", "student@example.com", "email_verified", true, "name", "Student Name"), "sub");

        OAuthUserInfo info = resolver.resolve("google", user, null);

        assertThat(info.email()).isEqualTo("student@example.com");
        assertThat(info.name()).isEqualTo("Student Name");
    }

    @Test
    void githubFallsBackToUserEmailsEndpointWhenProfileEmailIsPrivate() {
        server.enqueue(new MockResponse().setBody(
                "[{\"email\":\"unverified@example.com\",\"primary\":false,\"verified\":false},"
                        + "{\"email\":\"verified@example.com\",\"primary\":true,\"verified\":true}]")
                .addHeader("Content-Type", "application/json"));

        OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", 42, "login", "octocat"), "id");

        OAuthUserInfo info = resolver.resolve("github", user, authorizedClient());

        assertThat(info.email()).isEqualTo("verified@example.com");
        assertThat(info.name()).isEqualTo("octocat");
    }

    @Test
    void throwsWhenNoVerifiedEmailCanBeResolved() {
        server.enqueue(new MockResponse().setBody("[]").addHeader("Content-Type", "application/json"));
        OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", 42, "login", "octocat"), "id");

        assertThatThrownBy(() -> resolver.resolve("github", user, authorizedClient()))
                .isInstanceOf(ApiException.class);
    }

    private OAuth2AuthorizedClient authorizedClient() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
                .clientId("test-client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "gh-token", Instant.now(),
                Instant.now().plusSeconds(3600));
        return new OAuth2AuthorizedClient(registration, "octocat", token);
    }
}
