package com.studyflow.identity.service;

import com.studyflow.common.hash.Sha256;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Upstash's REST API (plain HTTP, `POST /{command}/{arg1}/{arg2}...` — see
 * https://upstash.com/docs/redis/features/restapi), not a TCP Redis client: one command a login
 * attempt needs (`SET ... EX`/`TTL`) doesn't justify adding Jedis/Lettuce + spring-data-redis
 * when the app already has an HTTP client (`RestClient`, same as the Groq/Voyage adapters).
 *
 * <p>Keys are the SHA-256 of the normalized email, never the raw address — same posture as
 * {@code refresh_tokens.user_agent_hash}/{@code ip_hash}, and sidesteps ever needing to
 * URL-encode an arbitrary email into a REST path segment.
 */
@Component
public class RedisLoginLockStore implements LoginLockStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginLockStore.class);
    private static final String KEY_PREFIX = "login:lock:";

    private final RestClient restClient;

    public RedisLoginLockStore(@Value("${studyflow.redis.upstash.rest-url}") String restUrl,
            @Value("${studyflow.redis.upstash.rest-token}") String token) {
        this.restClient = RestClient.builder().baseUrl(restUrl).defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    @Override
    public void lock(String rawEmail, Duration ttl) {
        execute("SET", key(rawEmail), "1", "EX", String.valueOf(ttl.toSeconds()));
    }

    @Override
    public Optional<Duration> remainingLock(String rawEmail) {
        JsonNode response = execute("TTL", key(rawEmail));
        if (response == null) {
            return Optional.empty();
        }
        long ttlSeconds = response.get("result").asLong();
        return ttlSeconds > 0 ? Optional.of(Duration.ofSeconds(ttlSeconds)) : Optional.empty();
    }

    private String key(String rawEmail) {
        return KEY_PREFIX + Sha256.hex(rawEmail.strip().toLowerCase(Locale.ROOT));
    }

    /** Never throws — a Redis hiccup fails the L2 check open, L1 still protects on its own. */
    private JsonNode execute(String... commandParts) {
        try {
            return restClient.post().uri("/" + String.join("/", commandParts)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("Upstash Redis call failed, failing open: {}", e.getMessage());
            return null;
        }
    }
}
