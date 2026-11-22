package com.oorja.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OtpCleanupJob {

    private final OtpService otpService;

    /** Runs every 30 minutes to remove expired OTP records. */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {
        otpService.purgeExpired();
    }
}
