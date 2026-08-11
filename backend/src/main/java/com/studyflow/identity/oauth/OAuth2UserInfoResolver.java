package com.studyflow.identity.oauth;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves the (email, name) pair from a provider's OAuth2 profile. The one piece of real logic
 * in the OAuth login slice: GitHub's {@code email} attribute is null for users with a private
 * email, so a verified address has to be fetched separately via {@code /user/emails} using the
 * access token Spring already obtained — see docs/DECISIONS.md.
 */
@Component
public class OAuth2UserInfoResolver {

    private final RestClient githubRestClient;

    public OAuth2UserInfoResolver(
            @Value("${studyflow.oauth2.github-api-base-url:https://api.github.com}") String githubApiBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.githubRestClient = RestClient.builder().baseUrl(githubApiBaseUrl).requestFactory(requestFactory).build();
    }

    public OAuthUserInfo resolve(String registrationId, OAuth2User oAuth2User, OAuth2AuthorizedClient authorizedClient) {
        String name = oAuth2User.getAttribute("name");
        String email = oAuth2User.getAttribute("email");
        if ("github".equals(registrationId)) {
            if (name == null || name.isBlank()) {
                name = oAuth2User.getAttribute("login");
            }
            if (email == null || email.isBlank()) {
                email = fetchGithubVerifiedEmail(authorizedClient);
            }
        }
        if (email == null || email.isBlank()) {
            // Never guess an email — fail the login rather than create a broken account (see
            // the plan's design decisions).
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Could not resolve a verified email address from " + registrationId);
        }
        return new OAuthUserInfo(email, name);
    }

    record GithubEmail(String email, boolean primary, boolean verified) {
    }

    private String fetchGithubVerifiedEmail(OAuth2AuthorizedClient authorizedClient) {
        List<GithubEmail> emails = githubRestClient.get()
                .uri("/user/emails")
                .headers(h -> h.setBearerAuth(authorizedClient.getAccessToken().getTokenValue()))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<GithubEmail>>() {
                });
        if (emails == null) {
            return null;
        }
        return emails.stream()
                .filter(e -> e.verified() && e.primary())
                .map(GithubEmail::email)
                .findFirst()
                .orElseGet(() -> emails.stream().filter(GithubEmail::verified).map(GithubEmail::email).findFirst()
                        .orElse(null));
    }
}
