package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AmcQuoteRequest(
        @NotNull Long plantId,
        /** e.g. "6 Months", "1 Year", "1.5 Years" */
        @NotBlank @Size(max = 60) String planLabel,
        @Size(max = 2000) String notes
) {}
