package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

public record PriceQuoteRequest(
        @NotBlank @Size(max = 64) String serviceId,
        @Size(max = 64) String tierId,
        @Size(max = 32) String couponCode,
        Boolean quoteRequest
) {}
