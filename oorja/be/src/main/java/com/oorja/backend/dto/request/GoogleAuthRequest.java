package com.oorja.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

/** idToken is the Google ID token (JWT) returned by Google Identity Services on the frontend. */
public record GoogleAuthRequest(
        @NotBlank String idToken
) {}
