package com.studyflow.identity.web;

import com.studyflow.common.error.ApiException;
import com.studyflow.common.error.ErrorCode;
import com.studyflow.identity.domain.User;
import com.studyflow.identity.dto.CompleteBirthYearRequest;
import com.studyflow.identity.dto.MeResponse;
import com.studyflow.identity.repo.UserRepository;
import jakarta.validation.Valid;
import java.time.Year;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;

    public MeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public MeResponse me(Authentication authentication) {
        User user = currentUser(authentication);
        return toResponse(user);
    }

    @PatchMapping("/birth-year")
    public MeResponse completeBirthYear(Authentication authentication,
            @Valid @RequestBody CompleteBirthYearRequest request) {
        User user = currentUser(authentication);
        int currentYear = Year.now(ZoneId.of("Asia/Kolkata")).getValue();
        if (request.birthYear() > currentYear) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "birthYear cannot be in the future");
        }
        user.setBirthYear(request.birthYear());
        userRepository.save(user);
        return toResponse(user);
    }

    private User currentUser(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "User no longer exists"));
    }

    private MeResponse toResponse(User user) {
        // Both false until birth year is known — birthYearRequired is the flag that drives the
        // frontend's /complete-profile redirect until then. See docs/DECISIONS.md.
        boolean isMinor = user.hasBirthYear() && user.isMinor();
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name(),
                user.getEmailVerifiedAt() != null, isMinor, isMinor && !user.hasGuardianConsent(),
                !user.hasBirthYear());
    }
}
