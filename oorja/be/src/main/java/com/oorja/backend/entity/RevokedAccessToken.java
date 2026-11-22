package com.oorja.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Denylist entry for a short-lived JWT access token that was explicitly
 * revoked (e.g. on logout) before it naturally expired. Keyed by the
 * token's {@code jti} claim - the raw token itself is never stored.
 *
 * Rows are only needed until {@link #expiresAt}; after that the token
 * would be rejected on expiry alone, so cleanup jobs purge them freely.
 */
@Entity
@Table(name = "revoked_access_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevokedAccessToken {

    @Id
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false)
    @Builder.Default
    private LocalDateTime revokedAt = LocalDateTime.now();
}
