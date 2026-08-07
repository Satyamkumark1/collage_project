package com.studyflow.identity.service;

import com.studyflow.common.hash.Sha256;
import com.studyflow.identity.domain.RefreshToken;
import com.studyflow.identity.repo.RefreshTokenRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token rotation with reuse detection (see specs/02-data-model.md, specs/04-identity-
 * and-security.md): presenting an already-revoked token revokes the whole {@code family_id}.
 */
@Service
public class RefreshTokenService {

    private static final Duration TTL = Duration.ofDays(30);

    private final SecureRandom random = new SecureRandom();
    private final RefreshTokenRepository repository;

    public RefreshTokenService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    public record IssuedToken(String rawValue, RefreshToken entity) {
    }

    @Transactional
    public IssuedToken issueNewFamily(UUID userId, String userAgentHash, String ipHash) {
        return issue(userId, UUID.randomUUID(), userAgentHash, ipHash);
    }

    @Transactional
    public IssuedToken rotate(RefreshToken current, String userAgentHash, String ipHash) {
        IssuedToken next = issue(current.getUserId(), current.getFamilyId(), userAgentHash, ipHash);
        current.revoke(next.entity().getId());
        repository.save(current);
        return next;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByRawValue(String raw) {
        return repository.findByTokenHash(hash(raw));
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        List<RefreshToken> active = repository.findByFamilyIdAndRevokedAtIsNull(familyId);
        for (RefreshToken token : active) {
            token.revoke(null);
        }
        repository.saveAll(active);
    }

    private IssuedToken issue(UUID userId, UUID familyId, String userAgentHash, String ipHash) {
        String raw = randomToken();
        RefreshToken entity = new RefreshToken(userId, hash(raw), familyId, Instant.now().plus(TTL), userAgentHash,
                ipHash);
        repository.save(entity);
        return new IssuedToken(raw, entity);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String raw) {
        return Sha256.hex(raw);
    }
}
