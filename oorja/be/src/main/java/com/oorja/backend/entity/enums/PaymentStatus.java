package com.oorja.backend.entity.enums;

/**
 * The payment lifecycle. Legal moves live in {@link #canTransitionTo}.
 *
 * <pre>
 * CREATED ──► PENDING ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *    │           │            │ └──────────────────────────────► REFUNDED
 *    ├───────────┴──► FAILED ──► (PENDING | CAPTURED)   a failed attempt can be retried, or captured late
 *    └───────────────► CANCELLED
 * </pre>
 *
 * Money statuses only move forward: CAPTURED is never downgraded to FAILED/CREATED (a late
 * {@code payment.failed} webhook arriving after a capture must be ignored), and REFUNDED /
 * CANCELLED are terminal.
 */
public enum PaymentStatus {
    /** Razorpay order created; customer hasn't completed Checkout yet. */
    CREATED,
    /** Reserved for async/webhook-driven flows (added in a later phase). */
    PENDING,
    /** Signature verified — this is real, captured money. */
    CAPTURED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case CREATED            -> next == PENDING || next == CAPTURED || next == FAILED || next == CANCELLED;
            case PENDING            -> next == CAPTURED || next == FAILED || next == CANCELLED;
            case FAILED             -> next == PENDING || next == CAPTURED || next == CANCELLED;
            case CAPTURED           -> next == PARTIALLY_REFUNDED || next == REFUNDED;
            case PARTIALLY_REFUNDED -> next == PARTIALLY_REFUNDED || next == REFUNDED;
            case REFUNDED, CANCELLED -> false;
        };
    }

    /** The customer can still pay this order (Checkout can be (re)opened for it). */
    public boolean isPayable() {
        return this == CREATED || this == PENDING || this == FAILED;
    }

    /** Money has been taken for this payment (whether or not some has since been returned). */
    public boolean isCaptured() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
