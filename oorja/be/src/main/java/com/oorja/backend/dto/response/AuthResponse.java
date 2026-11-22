package com.oorja.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    private String tokenType;
    /** Access token lifetime in seconds, so clients know when to proactively refresh. */
    private Long expiresIn;
    /** Opaque, single-use refresh token - present the current one to /refresh to rotate it for a new pair. */
    private String refreshToken;
    private String role;
    private Long userId;
    private String name;
    private String email;
    private String phone;
    private String address;
    /** True when phone/address are still missing (e.g. fresh Google sign-up) and the
     *  frontend should show a "complete your profile" step before continuing. */
    private boolean needsProfile;
}
