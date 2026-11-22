package com.oorja.backend.service;

import com.oorja.backend.repository.RevokedAccessTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Denylist for short-lived JWT access tokens that were explicitly revoked
 * (logout) before their natural expiry. {@link com.oorja.backend.security.JwtAuthFilter}
 * consults this on every request in addition to the token's own expiration
 * check, so a stolen-but-logged-out token stops working immediately instead
 * of remaining valid for the rest of its lifetime.
 */
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    @Transactional
    public void revoke(String jti, LocalDateTime expiresAt) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        revokedAccessTokenRepository.insertIfAbsent(jti, expiresAt);
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        return jti != null && revokedAccessTokenRepository.existsById(jti);
    }

    @Transactional
    public void purgeExpired() {
        revokedAccessTokenRepository.deleteExpired(LocalDateTime.now());
    }
}
