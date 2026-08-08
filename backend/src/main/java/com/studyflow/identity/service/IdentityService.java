package com.studyflow.identity.service;

import com.studyflow.identity.domain.User;
import java.util.UUID;

/**
 * Published interface for other features to resolve the authenticated user without injecting
 * {@code UserRepository} directly — see specs/01-architecture.md cross-feature access rule.
 */
public interface IdentityService {

    /** @throws com.studyflow.common.error.ApiException AUTH_TOKEN_EXPIRED if no active user has this id. */
    User requireActiveUser(UUID userId);
}
