package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Admin "Send to Customer" (either the first price, or a revision). Fills in
 * the current editable draft version with these values and sends it —
 * creating a new version if the current one has already been sent before.
 * If {@code revisionRequestId} is given, that open revision request is
 * marked RESOLVED as part of the same transaction.
 */
public record SendQuotationVersionRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal amountInr,
        @NotNull @FutureOrPresent LocalDate validUntil,
        @Size(max = 2000) String notes,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal subtotal,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal taxAmount,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount,
        Long revisionRequestId
) {}
