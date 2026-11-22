package com.oorja.backend.controller;

import com.oorja.backend.dto.request.AdminLoginRequest;
import com.oorja.backend.dto.request.LogoutRequest;
import com.oorja.backend.dto.request.RefreshTokenRequest;
import com.oorja.backend.dto.request.SendOtpRequest;
import com.oorja.backend.dto.request.VerifyOtpRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    /** Shared-password login used by the admin dashboard's login screen. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(adminAuthService.loginWithPassword(request.password()));
    }

    @PostMapping("/send-otp")
    public ResponseEntity<ApiMessageResponse> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        adminAuthService.requestOtp(request.email());
        return ResponseEntity.ok(ApiMessageResponse.ok("OTP sent to " + request.email()));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(adminAuthService.verifyOtpAndLogin(request.email(), request.otp()));
    }

    /**
     * Exchanges a valid refresh token for a fresh access/refresh pair. The
     * presented refresh token is consumed (single-use rotation), so clients
     * must store the new one returned here.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(adminAuthService.refresh(request.refreshToken()));
    }

    /**
     * Revokes the refresh token and blacklists the current access token, so a
     * stolen copy of either stops working immediately rather than staying
     * valid until it expires on its own.
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiMessageResponse> logout(@RequestBody(required = false) LogoutRequest request,
                                                      HttpServletRequest httpRequest) {
        adminAuthService.logout(bearerToken(httpRequest), request == null ? null : request.refreshToken());
        return ResponseEntity.ok(ApiMessageResponse.ok("Logged out."));
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
    }
}
