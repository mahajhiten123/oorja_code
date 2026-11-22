package com.oorja.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthTokenCleanupJob {

    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    /** Runs every 30 minutes to remove expired refresh tokens and stale access-token denylist entries. */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {
        refreshTokenService.purgeExpired();
        tokenBlacklistService.purgeExpired();
    }
}
