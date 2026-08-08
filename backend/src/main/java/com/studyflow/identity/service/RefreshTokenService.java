package com.studyflow.identity.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    // Self-injected (via a lazily-resolved proxy) so rotate()'s call to revokeFamily goes back
    // through the Spring AOP proxy instead of a plain `this.`/bare call — a same-instance call
    // bypasses proxy-based @Transactional entirely, which would silently turn revokeFamily's
    // REQUIRES_NEW into "just joins whatever transaction rotate() happened to have," defeating
    // the fresh-Hibernate-session isolation it depends on (same pattern as JobLifecycleService).
    private final RefreshTokenService self;

    public RefreshTokenService(RefreshTokenRepository repository, @Lazy RefreshTokenService self) {
        this.repository = repository;
        this.self = self;
    }

    public record IssuedToken(String rawValue, RefreshToken entity) {
    }

    @Transactional
    public IssuedToken issueNewFamily(UUID userId, String userAgentHash, String ipHash) {
        return issue(userId, UUID.randomUUID(), userAgentHash, ipHash);
    }

    // Plain @Transactional (no noRollbackFor): unlike an earlier version of this method, the
    // catch block's revocation now persists via revokeFamily's own REQUIRES_NEW transaction —
    // independent of whatever happens to this one — so this transaction rolling back on the
    // ApiException below is not only safe but necessary: it discards the just-issued `next`
    // child token (issue() below already inserted it before the failing flush), rather than
    // committing an orphaned, unrevoked, still-valid token that revokeFamily's separate,
    // concurrently-running transaction can't see yet (it wouldn't be committed at that point).
    @Transactional
    public IssuedToken rotate(RefreshToken current, String userAgentHash, String ipHash) {
        IssuedToken next = issue(current.getUserId(), current.getFamilyId(), userAgentHash, ipHash);
        current.revoke(next.entity().getId());
        try {
            repository.saveAndFlush(current);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another request already rotated this exact token concurrently (read the same
            // version, raced to write it) — without this check both would succeed and mint two
            // live children from one parent. revokeFamily runs in its own fresh transaction
            // (see its @Transactional below) rather than joining this method's: the saveAndFlush
            // above already forced Hibernate to flush a batch that failed mid-flight, which
            // leaves this persistence context unreliable for further writes — reusing it here
            // previously re-threw the same ObjectOptimisticLockingFailureException from inside
            // revokeFamily's own save, past this catch, as an unhandled 500 instead of the
            // intended 401 AUTH_REFRESH_REUSED (reproduced with two concurrent real
            // /auth/refresh calls sharing one cookie before this fix).
            self.revokeFamily(current.getFamilyId(), current.getUserId());
            throw new ApiException(ErrorCode.AUTH_REFRESH_REUSED, "Concurrent refresh detected; session revoked");
        }
        return next;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByRawValue(String raw) {
        return repository.findByTokenHash(hash(raw));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID familyId, UUID userId) {
        List<RefreshToken> active = repository.findByFamilyIdAndUserIdAndRevokedAtIsNull(familyId, userId);
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
