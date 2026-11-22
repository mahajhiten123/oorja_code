package com.oorja.backend.service;

import com.oorja.backend.config.JwtService;
import com.oorja.backend.dto.request.CustomerRegisterRequest;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.enums.AccountStatus;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CustomerAuthService {

    private final CustomerRepository customerRepository;
    private final OtpService otpService;
    private final JwtService jwtService;
    private final CodeGeneratorService codeGeneratorService;
    private final GoogleAuthService googleAuthService;
    private final RefreshTokenService refreshTokenService;
    private final TokenBlacklistService tokenBlacklistService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.jwt.expiration-ms}")
    private long accessTokenExpirationMs;

    // ==================== Email OTP (existing flow) ====================

    /** Step 1a: an existing customer requests a login code. */
    @Transactional
    public void requestLoginOtp(String email) {
        Customer customer = customerRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> ApiException.notFound(
                        "No account found for this email. Please register first."));

        if (customer.getStatus() == AccountStatus.INACTIVE) {
            throw ApiException.unauthorized("This account has been deactivated. Please contact support.");
        }

        otpService.generateAndSend(customer.getEmail(), OtpPurpose.CUSTOMER_LOGIN, "log in to your Oorja on Move account");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyLoginOtp(String email, String otp) {
        Customer customer = customerRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> ApiException.notFound("No account found for this email."));

        otpService.verify(customer.getEmail(), otp, OtpPurpose.CUSTOMER_LOGIN);

        return buildAuthResponse(customer);
    }

    /** Step 1b: a brand-new customer starts registration by requesting a verification code. */
    @Transactional
    public void requestRegisterOtp(CustomerRegisterRequest request) {
        if (customerRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("An account already exists for this email. Please log in instead.");
        }
        otpService.generateAndSend(request.email(), OtpPurpose.CUSTOMER_REGISTER, "verify your email for Oorja on Move");
    }

    /** Step 2b: verify the code and create the account. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse verifyRegisterOtp(CustomerRegisterRequest request, String otp) {
        if (customerRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("An account already exists for this email. Please log in instead.");
        }

        otpService.verify(request.email(), otp, OtpPurpose.CUSTOMER_REGISTER);

        Customer customer = Customer.builder()
                .customerCode(codeGeneratorService.nextCustomerCode())
                .name(request.name())
                .email(request.email().toLowerCase(Locale.ROOT))
                .phone(request.phone())
                .address(request.address())
                .status(AccountStatus.ACTIVE)
                .build();

        customer = customerRepository.save(customer);

        return buildAuthResponse(customer);
    }

    // ==================== Google OAuth ====================

    /**
     * Verifies the Google ID token, then finds the matching customer by
     * Google id (or by email, to link an account that previously registered
     * the normal way) or creates a brand-new one. Newly-created accounts
     * won't have a phone/address yet - AuthResponse.needsProfile tells the
     * frontend to collect those before dropping the customer into the app.
     */
    public AuthResponse authenticateWithGoogle(String idToken) {
        // Network call to Google stays outside any DB transaction (no pooled connection held while waiting).
        GoogleAuthService.GoogleIdentity identity = googleAuthService.verify(idToken);

        if (!identity.emailVerified()) {
            throw ApiException.unauthorized("This Google account's email isn't verified.");
        }

        try {
            return transactionTemplate.execute(status -> findOrCreateGoogleCustomer(identity));
        } catch (DataIntegrityViolationException race) {
            // Two first-time sign-ins for the same person landed together; the loser hit the unique
            // index on google_id / lower(email). The winner has committed, so a second attempt simply
            // finds the existing row. A second failure is a real problem and propagates.
            return transactionTemplate.execute(status -> findOrCreateGoogleCustomer(identity));
        }
    }

    private AuthResponse findOrCreateGoogleCustomer(GoogleAuthService.GoogleIdentity identity) {
        // Always store/lookup lower-cased so 'Rahul@x.com' and 'rahul@x.com' can never be two accounts.
        String email = identity.email().toLowerCase(Locale.ROOT);

        Customer customer = customerRepository.findByGoogleId(identity.googleId())
                .or(() -> customerRepository.findByEmailIgnoreCase(email))
                .orElseGet(() -> Customer.builder()
                        .customerCode(codeGeneratorService.nextCustomerCode())
                        .name(identity.name())
                        .email(email)
                        .status(AccountStatus.ACTIVE)
                        .build());

        if (customer.getGoogleId() == null) {
            customer.setGoogleId(identity.googleId());
        }
        customer = customerRepository.save(customer);

        return buildAuthResponse(customer);
    }

    // ==================== Shared ====================

    /**
     * Exchanges a valid, unused refresh token for a brand-new access/refresh pair (rotation).
     * noRollbackFor keeps the "deactivated account" revocation below from being
     * undone by the exception thrown immediately after it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(refreshToken, SubjectType.CUSTOMER);

        Customer customer = customerRepository.findById(rotated.subjectId())
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists."));

        if (customer.getStatus() == AccountStatus.INACTIVE) {
            // Deactivated mid-session: kill every live session rather than handing out a fresh token.
            refreshTokenService.revokeAll(customer.getId(), SubjectType.CUSTOMER);
            throw ApiException.unauthorized("This account has been deactivated. Please contact support.");
        }

        String accessToken = jwtService.generateToken(customer.getEmail(), customer.getId(), "CUSTOMER");

        return AuthResponse.builder()
                .token(accessToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000)
                .refreshToken(rotated.newRefreshToken())
                .role("CUSTOMER")
                .userId(customer.getId())
                .name(customer.getName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .address(customer.getAddress())
                .needsProfile(isBlank(customer.getPhone()) || isBlank(customer.getAddress()))
                .build();
    }

    /** Revokes the presented refresh token and blacklists the current access token for its remaining lifetime. */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        refreshTokenService.revoke(refreshToken);
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            tokenBlacklistService.revoke(
                    jwtService.extractJti(accessToken),
                    jwtService.extractExpirationDate(accessToken).toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignored) {
            // Already invalid/expired - nothing to blacklist.
        }
    }

    private AuthResponse buildAuthResponse(Customer customer) {
        String token = jwtService.generateToken(customer.getEmail(), customer.getId(), "CUSTOMER");
        String refreshToken = refreshTokenService.issue(
                customer.getId(), SubjectType.CUSTOMER, customer.getEmail(), "CUSTOMER");
        boolean needsProfile = isBlank(customer.getPhone()) || isBlank(customer.getAddress());
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpirationMs / 1000)
                .refreshToken(refreshToken)
                .role("CUSTOMER")
                .userId(customer.getId())
                .name(customer.getName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .address(customer.getAddress())
                .needsProfile(needsProfile)
                .build();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
