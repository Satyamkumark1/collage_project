package com.studyflow.identity.service;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.RefreshToken;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.dto.RegisterRequest;
import com.studyflow.identity.repo.UserRepository;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /**
     * Precomputed BCrypt hash checked when the email doesn't exist, so a nonexistent-account
     * login takes the same code path (and roughly the same time) as a wrong-password login —
     * see specs/04-identity-and-security.md "no user enumeration".
     */
    private final String dummyHash;

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, RefreshTokenService refreshTokenService, JwtService jwtService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public User register(RegisterRequest request) {
        int currentYear = Year.now(ZoneId.of("Asia/Kolkata")).getValue();
        if (request.birthYear() > currentYear) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "birthYear cannot be in the future");
        }
        userRepository.findByEmailAndDeletedAtIsNull(request.email()).ifPresent(existing -> {
            throw new ApiException(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED, "Email is already registered");
        });
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.name(),
                request.birthYear());
        // Deviation: no SMTP provider this phase, auto-verify at registration. See docs/DECISIONS.md.
        user.setEmailVerifiedAt(Instant.now());
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // The findByEmailAndDeletedAtIsNull check above isn't atomic with this insert —
            // two concurrent registrations for the same email can both pass it and race to
            // users_email_unique_idx. Whichever loses gets a clean conflict, not a raw
            // constraint-violation 500.
            throw new ApiException(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED, "Email is already registered");
        }
    }

    public record LoginResult(User user, String accessToken, String rawRefreshToken) {
    }

    @Transactional
    public LoginResult login(String email, String rawPassword, String userAgentHash, String ipHash) {
        var maybeUser = userRepository.findByEmailAndDeletedAtIsNull(email);
        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);
        if (maybeUser.isEmpty() || !matches) {
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid email or password");
        }
        User user = maybeUser.get();
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        RefreshTokenService.IssuedToken issued = refreshTokenService.issueNewFamily(user.getId(), userAgentHash,
                ipHash);
        String accessToken = jwtService.issueAccessToken(user, issued.entity().getFamilyId());
        return new LoginResult(user, accessToken, issued.rawValue());
    }

    // Plain @Transactional: RefreshTokenService.revokeFamily persists via its own independent
    // REQUIRES_NEW transaction, so this one rolling back on the reuse-detection ApiException
    // below (or on rotate()'s) doesn't undo the revocation — see RefreshTokenService.rotate's
    // comment for why a rollback here is actually required, not just harmless.
    @Transactional
    public LoginResult refresh(String rawRefreshToken, String userAgentHash, String ipHash) {
        RefreshToken current = refreshTokenService.findByRawValue(rawRefreshToken)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "Refresh token not recognised"));

        if (current.isRevoked()) {
            refreshTokenService.revokeFamily(current.getFamilyId(), current.getUserId());
            throw new ApiException(ErrorCode.AUTH_REFRESH_REUSED, "Refresh token reuse detected; session revoked");
        }
        if (current.isExpired()) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "Refresh token expired");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(current.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "User no longer exists"));

        RefreshTokenService.IssuedToken next = refreshTokenService.rotate(current, userAgentHash, ipHash);
        String accessToken = jwtService.issueAccessToken(user, next.entity().getFamilyId());
        return new LoginResult(user, accessToken, next.rawValue());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.findByRawValue(rawRefreshToken)
                .ifPresent(token -> refreshTokenService.revokeFamily(token.getFamilyId(), token.getUserId()));
    }
}
