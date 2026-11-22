package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CustomerRegisterRequest;
import com.oorja.backend.dto.request.GoogleAuthRequest;
import com.oorja.backend.dto.request.LogoutRequest;
import com.oorja.backend.dto.request.RefreshTokenRequest;
import com.oorja.backend.dto.request.SendOtpRequest;
import com.oorja.backend.dto.request.VerifyOtpRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.service.CustomerAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/auth")
@RequiredArgsConstructor
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;

    // ---- Login (existing customer, email) ----

    @PostMapping("/login/send-otp")
    public ResponseEntity<ApiMessageResponse> sendLoginOtp(@Valid @RequestBody SendOtpRequest request) {
        customerAuthService.requestLoginOtp(request.email());
        return ResponseEntity.ok(ApiMessageResponse.ok("OTP sent to " + request.email()));
    }

    @PostMapping("/login/verify-otp")
    public ResponseEntity<AuthResponse> verifyLoginOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(customerAuthService.verifyLoginOtp(request.email(), request.otp()));
    }

    // ---- Registration (new customer, email) ----

    @PostMapping("/register/send-otp")
    public ResponseEntity<ApiMessageResponse> sendRegisterOtp(@Valid @RequestBody CustomerRegisterRequest request) {
        customerAuthService.requestRegisterOtp(request);
        return ResponseEntity.ok(ApiMessageResponse.ok("Verification code sent to " + request.email()));
    }

    @PostMapping("/register/verify-otp")
    public ResponseEntity<AuthResponse> verifyRegisterOtp(@Valid @RequestBody RegisterVerifyRequest request) {
        AuthResponse response = customerAuthService.verifyRegisterOtp(
                new CustomerRegisterRequest(request.name(), request.email(), request.phone(), request.address()),
                request.otp());
        return ResponseEntity.ok(response);
    }

    // ---- Google OAuth ----

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleAuth(@Valid @RequestBody GoogleAuthRequest request) {
        return ResponseEntity.ok(customerAuthService.authenticateWithGoogle(request.idToken()));
    }

    // ---- Session lifecycle ----

    /**
     * Exchanges a valid refresh token for a fresh access/refresh pair. The
     * presented refresh token is consumed (single-use rotation), so clients
     * must store the new one returned here.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(customerAuthService.refresh(request.refreshToken()));
    }

    /**
     * Revokes the refresh token and blacklists the current access token, so a
     * stolen copy of either stops working immediately rather than staying
     * valid until it expires on its own.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiMessageResponse> logout(@RequestBody(required = false) LogoutRequest request,
                                                      HttpServletRequest httpRequest) {
        customerAuthService.logout(bearerToken(httpRequest), request == null ? null : request.refreshToken());
        return ResponseEntity.ok(ApiMessageResponse.ok("Logged out."));
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
    }

    public record RegisterVerifyRequest(
            @NotBlank String name,
            @NotBlank String email,
            @NotBlank String phone,
            String address,
            @NotBlank @Pattern(regexp = "\\d{6}") String otp
    ) {}
}
