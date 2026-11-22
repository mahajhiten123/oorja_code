package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.SubjectType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A long-lived, revocable refresh token used to mint new short-lived access
 * (JWT) tokens without re-authenticating. Only a SHA-256 hash of the raw
 * token is ever persisted - the raw value is returned to the client exactly
 * once, at issue time, and can never be recovered from the database.
 *
 * Refresh tokens are single-use: every {@code /refresh} call revokes the
 * presented token and issues a brand-new one (rotation). Presenting an
 * already-revoked token is treated as a signal of possible theft and kills
 * every other live refresh token for that subject.
 */
@Entity
@Table(name = "refresh_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private SubjectType subjectType;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String role;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Null while still valid; set the moment it's rotated, logged out, or force-revoked. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
