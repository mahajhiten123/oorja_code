package com.oorja.backend.service;

import com.oorja.backend.entity.OtpToken;
import com.oorja.backend.entity.enums.OtpChannel;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.OtpTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpTokenRepository otpTokenRepository;
    private final EmailService emailService;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.otp.expiry-minutes:5}")
    private int expiryMinutes;

    @Value("${app.otp.max-per-window:5}")
    private int maxRequestsPerWindow;

    @Value("${app.otp.window-minutes:15}")
    private int windowMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.otp.bypass-code:}")
    private String bypassCode = "";

    /** Email-channel OTP (existing behaviour, e.g. admin/customer email login). */
    @Transactional
    public void generateAndSend(String email, OtpPurpose purpose, String purposeLabel) {
        generateAndSend(email, purpose, purposeLabel, OtpChannel.EMAIL);
    }

    /**
     * Generates a 6-digit OTP, hashes and stores it, then emails it to the
     * given address. Email is the only supported OTP channel.
     */
    @Transactional
    public void generateAndSend(String identifier, OtpPurpose purpose, String purposeLabel, OtpChannel channel) {
        long recentRequests = otpTokenRepository.countRecentRequests(
                normalize(identifier, channel), purpose, LocalDateTime.now().minusMinutes(windowMinutes));

        if (recentRequests >= maxRequestsPerWindow) {
            throw ApiException.tooManyRequests(
                    "Too many OTP requests. Please wait a few minutes and try again.");
        }

        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = BCrypt.hashpw(otp, BCrypt.gensalt(10));

        OtpToken token = OtpToken.builder()
                .identifier(normalize(identifier, channel))
                .otpHash(hash)
                .purpose(purpose)
                .channel(channel)
                .attempts(0)
                .used(false)
                .expiresAt(LocalDateTime.now().plusMinutes(expiryMinutes))
                .build();

        otpTokenRepository.save(token);

        emailService.sendOtpEmail(identifier, otp, purposeLabel);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String identifier, String otp, OtpPurpose purpose) {
        verify(identifier, otp, purpose, OtpChannel.EMAIL);
    }

    /**
     * noRollbackFor is essential: a wrong code throws ApiException, and a default rollback would undo the
     * attempts++ written just before it, so the lockout counter would never advance.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(String identifier, String otp, OtpPurpose purpose, OtpChannel channel) {
        String normalized = normalize(identifier, channel);

        // Row-locking read: without this, two concurrent verifies of the
        // same still-unused, correct OTP can both read used=false and both
        // succeed before either write lands, and the attempts++ below can
        // lose updates under concurrent wrong attempts.
        OtpToken token = otpTokenRepository
                .findTopByIdentifierIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDescForUpdate(normalized, purpose)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("No OTP request found. Please request a new code."));

        if (token.isExpired()) {
            throw ApiException.badRequest("This OTP has expired. Please request a new code.");
        }

        if (token.getAttempts() >= maxAttempts) {
            throw ApiException.tooManyRequests("Too many incorrect attempts. Please request a new code.");
        }

        boolean matches = (bypassCode != null && !bypassCode.isBlank() && bypassCode.equals(otp))
                || BCrypt.checkpw(otp, token.getOtpHash());

        if (!matches) {
            // Atomic UPDATE rather than read-modify-write on the loaded
            // entity — see OtpTokenRepository#incrementAttempts. No need to
            // save() the stale in-memory `token`; the clearAutomatically
            // flag on that query detaches it so a later flush can't
            // overwrite this with the old count.
            otpTokenRepository.incrementAttempts(token.getId());
            throw ApiException.badRequest("Incorrect OTP. Please try again.");
        }

        token.setUsed(true);
        otpTokenRepository.save(token);
    }

    @Transactional
    public void purgeExpired() {
        otpTokenRepository.deleteExpired(LocalDateTime.now());
    }

    /** Emails are matched case-insensitively; phone numbers are kept as-is (digits only, no case). */
    private String normalize(String identifier, OtpChannel channel) {
        return identifier.toLowerCase();
    }
}
