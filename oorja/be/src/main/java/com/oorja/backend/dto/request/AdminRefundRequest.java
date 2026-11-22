package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.RefundReasonCode;
import jakarta.validation.constraints.NotNull;

/**
 * Admin-raised refund against a booking the customer hasn't cancelled —
 * the "we got this wrong, make it right" path (duplicate charge, service
 * failure, goodwill).
 */
public record AdminRefundRequest(
        @NotNull Long bookingId,
        @NotNull RefundReasonCode reasonCode,
        @Size(max = 1000) String reason,
        @Positive @Max(10000000) Double amountInr
) {}
