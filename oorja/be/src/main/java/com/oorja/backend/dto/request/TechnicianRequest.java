package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

public record TechnicianRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "^([0-9+()\\-\\s]{7,20})?$", message = "must be a valid phone number") String phone,
        @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 150) String specialization,
        /** Optional — the admin console doesn't collect this; defaults to "Unassigned". */
        @Size(max = 150) String area
) {}
