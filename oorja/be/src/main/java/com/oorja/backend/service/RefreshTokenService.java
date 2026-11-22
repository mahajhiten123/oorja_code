package com.oorja.backend.service;

import com.oorja.backend.entity.RefreshToken;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, rotates and revokes refresh tokens so a stolen 401-vulnerable
 * bearer JWT is only ever valid for its short access-token lifetime instead
 * of a full day. Only a hash of the raw token is ever stored (see
 * {@link RefreshToken}); the raw value is handed to the client exactly once.
 *
 * Rotation is single-use: every successful {@link #rotate} revokes the
 * presented token and returns a new one. If a caller ever presents a token
 * that's already been rotated/revoked, that's a strong signal of token
 * theft/replay, so every other live refresh token for that subject is
 * revoked too, forcing a fresh login everywhere.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.jwt.refresh-expiration-ms:2592000000}") // 30 days
    private long refreshExpirationMs;

    /** Issues a brand-new refresh token for a freshly authenticated subject (login). */
    @Transactional
    public String issue(Long subjectId, SubjectType subjectType, String email, String role) {
        String raw = randomToken();

        RefreshToken entity = RefreshToken.builder()
                .tokenHash(hash(raw))
                .subjectId(subjectId)
                .subjectType(subjectType)
                .email(email)
                .role(role)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshExpirationMs / 1000))
                .build();

        refreshTokenRepository.save(entity);
        return raw;
    }

    /**
     * Validates + rotates a presented refresh token. Returns the subject info needed to mint a new access token.
     *
     * noRollbackFor is important: on reuse detection we revoke every session
     * for the subject and *then* throw. Without this, the rollback triggered
     * by the exception would silently undo that revocation, leaving the
     * stolen tokens live.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotated rotate(String rawToken, SubjectType expectedSubjectType) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Refresh token is required.");
        }

        // Row-locking read: without this, two simultaneous refreshes of the
        // same token can both pass the !isRevoked check below and both mint
        // a child token, defeating single-use rotation under true
        // concurrency (sequential reuse is still correctly detected either way).
        RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token."));

        if (existing.getSubjectType() != expectedSubjectType) {
            throw ApiException.unauthorized("Invalid refresh token.");
        }

        if (existing.isRevoked()) {
            // Reuse of an already-rotated token: treat as compromised and kill
            // every other live session for this subject.
            refreshTokenRepository.revokeAllForSubject(
                    existing.getSubjectId(), existing.getSubjectType(), LocalDateTime.now());
            throw ApiException.unauthorized("This session is no longer valid. Please log in again.");
        }

        if (existing.isExpired()) {
            throw ApiException.unauthorized("Session expired. Please log in again.");
        }

        existing.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(existing);

        String newRaw = issue(existing.getSubjectId(), existing.getSubjectType(), existing.getEmail(), existing.getRole());

        return new Rotated(existing.getSubjectId(), existing.getEmail(), existing.getRole(), newRaw);
    }

    /** Logout: revoke a single refresh token. Silently no-ops for an unknown/already-revoked token. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            t.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(t);
        });
    }

    /** Kills every live session for a subject (e.g. "log out everywhere", password change). */
    @Transactional
    public void revokeAll(Long subjectId, SubjectType subjectType) {
        refreshTokenRepository.revokeAllForSubject(subjectId, subjectType, LocalDateTime.now());
    }

    @Transactional
    public void purgeExpired() {
        // Keep revoked/expired rows around briefly for reuse-detection auditing, then drop them.
        refreshTokenRepository.deleteExpired(LocalDateTime.now().minusDays(7));
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Rotated(Long subjectId, String email, String role, String newRefreshToken) {}
}
