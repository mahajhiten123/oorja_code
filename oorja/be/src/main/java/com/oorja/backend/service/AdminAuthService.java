package com.oorja.backend.service;

import com.oorja.backend.config.JwtService;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.entity.Admin;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final AdminRepository adminRepository;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;

    /** Shared admin-console password. Set only via ADMIN_PASSWORD env var - see SecurityStartupValidator. */
    @Value("${app.admin.password}")
    private String adminPassword;

    /** Which admin account the shared-password session is issued for/as. */
    @Value("${app.admin.email}")
    private String defaultAdminEmail;

    @Value("${app.jwt.expiration-ms}")
    private long accessTokenExpirationMs;

    /**
     * Simple shared-password login for the admin console (as opposed to the
     * per-admin email+OTP flow below). Used by the admin dashboard's single
     * "Admin password" login screen. Issues a full SUPER_ADMIN token pair
     * scoped to the configured default admin account, creating that account
     * on first use if it doesn't exist yet.
     */
    @Transactional
    public AuthResponse loginWithPassword(String password) {
        if (password == null || !password.equals(adminPassword)) {
            throw ApiException.unauthorized("Incorrect admin password.");
        }

        Admin admin = adminRepository.findByEmailIgnoreCase(defaultAdminEmail)
                .orElseGet(() -> adminRepository.save(Admin.builder()
                        .name("Admin")
                        .email(defaultAdminEmail)
                        .role("SUPER_ADMIN")
                        .build()));

        return buildAuthResponse(admin, "SUPER_ADMIN");
    }

    @Transactional
    public void requestOtp(String email) {
        Admin admin = adminRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> ApiException.notFound(
                        "No admin account found for this email."));

        otpService.generateAndSend(admin.getEmail(), OtpPurpose.ADMIN_LOGIN, "log in to the Oorja on Move admin dashboard");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyOtpAndLogin(String email, String otp) {
        Admin admin = adminRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> ApiException.notFound("No admin account found for this email."));

        otpService.verify(admin.getEmail(), otp, OtpPurpose.ADMIN_LOGIN);

        return buildAuthResponse(admin, "SUPER_ADMIN");
    }

    /** Exchanges a valid, unused refresh token for a brand-new access/refresh pair (rotation). */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(refreshToken, SubjectType.ADMIN);

        String accessToken = jwtService.generateToken(rotated.email(), rotated.subjectId(), rotated.role());

        return AuthResponse.builder()
                .token(accessToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000)
                .refreshToken(rotated.newRefreshToken())
                .role(rotated.role())
                .userId(rotated.subjectId())
                .email(rotated.email())
                .build();
    }

    /** Revokes the presented refresh token and blacklists the current access token for its remaining lifetime. */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        refreshTokenService.revoke(refreshToken);
        blacklistAccessTokenIfPresent(accessToken);
    }

    private void blacklistAccessTokenIfPresent(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            String jti = jwtService.extractJti(accessToken);
            var expiresAt = jwtService.extractExpirationDate(accessToken);
            tokenBlacklistService.revoke(jti, expiresAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignored) {
            // Already invalid/expired - nothing to blacklist.
        }
    }

    private AuthResponse buildAuthResponse(Admin admin, String role) {
        String accessToken = jwtService.generateToken(admin.getEmail(), admin.getId(), role);
        String refreshToken = refreshTokenService.issue(admin.getId(), SubjectType.ADMIN, admin.getEmail(), role);

        return AuthResponse.builder()
                .token(accessToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000)
                .refreshToken(refreshToken)
                .role(role.equals("SUPER_ADMIN") ? role : "ADMIN")
                .userId(admin.getId())
                .name(admin.getName())
                .email(admin.getEmail())
                .build();
    }
}
