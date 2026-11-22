package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

/**
 * Sent by the customer app once Razorpay's payment for an accepted quote
 * has been verified. The order id lets the backend cross-check against the
 * CAPTURED Payment ledger row instead of trusting a bare payment reference.
 */
public record RecordQuotationPaymentRequest(@NotBlank @Size(max = 64) String razorpayOrderId) {}
