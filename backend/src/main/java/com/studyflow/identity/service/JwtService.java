package com.studyflow.identity.service;

import com.studyflow.identity.config.JwtProperties;
import com.studyflow.identity.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and validates short-lived HS512 access tokens (see specs/04-identity-and-security.md). */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(SecretKey jwtSigningKey, JwtProperties properties) {
        this.signingKey = jwtSigningKey;
        this.properties = properties;
    }

    public String issueAccessToken(User user, UUID sessionId) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.getAccessTokenTtlMinutes() * 60L);
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("sid", sessionId.toString())
                .claim("role", user.getRole().name())
                .claim("emailVerified", user.getEmailVerifiedAt() != null)
                .issuer(properties.getIssuer())
                .claim("aud", properties.getAudience())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    /** @throws JwtException if the token is malformed, expired, or fails issuer/audience checks. */
    public Claims parseAndValidate(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!properties.getIssuer().equals(claims.getIssuer())) {
            throw new JwtException("Unexpected issuer");
        }
        // "aud" is a registered claim name: jjwt normalizes it to Set<String> on read
        // regardless of how it was written, so compare via getAudience(), not a raw get("aud").
        // A token with no "aud" claim at all yields a null Set here, not an empty one — treat
        // that the same as a non-matching audience rather than letting it NPE past validation.
        if (claims.getAudience() == null || !claims.getAudience().contains(properties.getAudience())) {
            throw new JwtException("Unexpected audience");
        }
        return claims;
    }
}
