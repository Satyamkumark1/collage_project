package com.studyflow.identity.config;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fails fast at boot if JWT_SECRET is absent or too short for HS512 (needs >= 64 bytes). */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, CookieProperties.class})
public class JwtKeyConfig {

    private static final int MIN_KEY_BYTES = 64;

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Refusing to start.");
        }
        byte[] keyBytes = decode(properties.getSecret());
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must decode to at least " + MIN_KEY_BYTES + " bytes for HS512; got "
                            + keyBytes.length);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
