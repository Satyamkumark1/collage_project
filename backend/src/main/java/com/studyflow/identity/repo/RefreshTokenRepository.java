package com.studyflow.identity.repo;

import com.studyflow.identity.domain.RefreshToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Intentionally cannot be owner-scoped before lookup: this is the entry point that
    // authenticates the raw cookie value in the first place — the caller doesn't know which
    // user it belongs to until this returns. Every subsequent operation on the result is
    // scoped by the userId the row itself carries (see AuthService.refresh/logout).
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    List<RefreshToken> findByFamilyIdAndUserIdAndRevokedAtIsNull(UUID familyId, UUID userId);
}
