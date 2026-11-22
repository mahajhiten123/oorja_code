package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import com.oorja.backend.entity.enums.RefundReasonCode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Cancelling and rebooking are the same decision from the customer's side,
 * so they're the same request here: {@code intent} says which branch they
 * chose after seeing the refund preview.
 *
 * @param reason      the customer's own words, kept for support context
 * @param reasonCode  drives the policy engine; defaults to CUSTOMER_REQUEST
 * @param intent      REFUND (cancel and take the money) or REBOOK (keep the
 *                    booking and its payment, just move the date)
 * @param newDate     required when intent is REBOOK
 * @param newTime     required when intent is REBOOK
 */
public record CancelBookingRequest(
        @Size(max = 1000) String reason,
        RefundReasonCode reasonCode,
        Intent intent,
        @FutureOrPresent LocalDate newDate,
        LocalTime newTime
) {
    public enum Intent { REFUND, REBOOK }

    /** Cross-field rule: choosing REBOOK without saying when is meaningless. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @AssertTrue(message = "newDate and newTime are required when intent is REBOOK")
    public boolean isRebookTargetPresent() {
        return intentOrDefault() != Intent.REBOOK || (newDate != null && newTime != null);
    }

    public Intent intentOrDefault() {
        return intent == null ? Intent.REFUND : intent;
    }

    public RefundReasonCode reasonCodeOrDefault() {
        // A customer can only ever pick a customer-selectable code; anything
        // else arriving from the app is treated as a plain change of mind
        // rather than trusted into a full-refund band.
        return (reasonCode != null && reasonCode.customerSelectable())
                ? reasonCode : RefundReasonCode.CUSTOMER_REQUEST;
    }
}
