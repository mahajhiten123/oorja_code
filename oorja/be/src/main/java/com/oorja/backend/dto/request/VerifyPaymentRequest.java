package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

public record VerifyPaymentRequest(
        @NotBlank @Size(max = 64) String razorpay_order_id,
        @NotBlank @Size(max = 64) String razorpay_payment_id,
        @NotBlank @Size(max = 128) String razorpay_signature
) {}
