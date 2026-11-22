package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What the customer wants to pay for — not how much. The amount is looked
 * up server-side (see PricingService) so a tampered client can never open a
 * Razorpay order for less than the real price.
 * <p>
 * The optional draft* fields describe the booking this payment is for
 * (already known to Checkout before Razorpay even opens). They're stored on
 * the Payment row so the webhook handler can create the booking itself if
 * the customer's browser dies right after paying and never calls
 * POST /api/customer/bookings — see PaymentWebhookService.
 */
public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String serviceId,
        @Size(max = 64) String tierId,
        @Size(max = 32) String couponCode,
        @NotBlank @Size(max = 40) String receipt,
        Long draftPlantId,
        LocalDate draftScheduledDate,
        LocalTime draftScheduledTime,
        @Size(max = 120) String draftServiceType,
        @Size(max = 2000) String draftNotes,
        @Size(max = 255) String draftLocation
) {}
