package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOrderForQuotationRequest(
        @NotNull Long quotationId,
        @NotBlank @Size(max = 40) String receipt
) {}
