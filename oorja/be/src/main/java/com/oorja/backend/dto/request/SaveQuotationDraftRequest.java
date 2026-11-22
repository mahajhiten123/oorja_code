package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Admin "Save Draft" — updates the quotation's current editable draft
 * version without sending anything to the customer. All fields optional so
 * the admin can save partial progress.
 */
public record SaveQuotationDraftRequest(
        @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal amountInr,
        @FutureOrPresent LocalDate validUntil,
        @Size(max = 2000) String notes,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal subtotal,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal taxAmount,
        @DecimalMin(value = "0.0") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount
) {}
