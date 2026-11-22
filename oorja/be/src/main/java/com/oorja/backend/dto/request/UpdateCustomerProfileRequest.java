package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

public record UpdateCustomerProfileRequest(
        @NotBlank @Size(max = 150) String name,
        @Pattern(regexp = "^([0-9+()\\-\\s]{7,20})?$", message = "must be a valid phone number") String phone,
        @Size(max = 1000) String address
) {}
