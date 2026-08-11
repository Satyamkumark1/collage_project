package com.studyflow.identity.domain;

import com.studyflow.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.generate();

    // No @JdbcTypeCode override: binding this as SqlTypes.OTHER made Hibernate send the
    // parameter as bytea, which citext's operators reject ("citext = bytea"). Plain VARCHAR
    // binding works because citext accepts text/varchar comparisons natively. columnDefinition
    // still tells Hibernate's validator to expect "citext" here rather than "varchar(255)".
    @Column(name = "email", columnDefinition = "citext", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", columnDefinition = "varchar(20)", nullable = false)
    private UserRole role = UserRole.USER;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    // Nullable: OAuth sign-ins (Google/GitHub) create the account before birth year is known —
    // see docs/DECISIONS.md. Callers must check hasBirthYear() before isMinor().
    @Column(name = "birth_year")
    private Short birthYear;

    @Column(name = "guardian_consent_at")
    private Instant guardianConsentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "varchar(20)", nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "locale", nullable = false)
    private String locale = "en-IN";

    @Column(name = "timezone", nullable = false)
    private String timezone = "Asia/Kolkata";

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
        // JPA
    }

    public User(String email, String passwordHash, String name, Short birthYear) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.birthYear = birthYear;
    }

    public boolean hasBirthYear() {
        return birthYear != null;
    }

    /**
     * Only the birth year is collected, not a full date of birth, so the exact birthday within
     * that year is unknown. A user whose birth-year difference is exactly 18 may not have had
     * their birthday yet this calendar year — treat them as a minor through the end of that
     * anniversary year rather than assuming the birthday has already passed, so the DPDP consent
     * gate never under-triggers.
     *
     * <p>Callers must check {@link #hasBirthYear()} first — birth year is unset for OAuth
     * sign-ins until the post-login profile step completes.
     */
    public boolean isMinor() {
        int age = Year.now(ZoneId.of("Asia/Kolkata")).getValue() - birthYear;
        return age <= 18;
    }

    public boolean hasGuardianConsent() {
        return guardianConsentAt != null;
    }

    // No collection flow yet (deferred — see docs/DECISIONS.md); this exists so the DPDP gate
    // itself and its tests aren't blocked on that follow-up phase.
    public void recordGuardianConsent() {
        this.guardianConsentAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public Short getBirthYear() {
        return birthYear;
    }

    public void setBirthYear(Short birthYear) {
        this.birthYear = birthYear;
    }

    public Instant getGuardianConsentAt() {
        return guardianConsentAt;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
