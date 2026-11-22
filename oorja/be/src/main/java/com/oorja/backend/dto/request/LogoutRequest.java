package com.oorja.backend.dto.request;

/** refreshToken is optional so logout still succeeds (clearing the access token) even if it was already lost client-side. */
public record LogoutRequest(
        String refreshToken
) {}
