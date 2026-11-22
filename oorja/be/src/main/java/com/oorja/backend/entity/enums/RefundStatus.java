package com.oorja.backend.entity.enums;

/**
 * The refund state machine. Legal transitions live in exactly one place —
 * {@link #canTransitionTo} — so no service can invent its own path.
 *
 * REQUESTED -> UNDER_REVIEW -> APPROVED -> PROCESSING -> COMPLETED
 *                   |                          |
 *                   +-> REJECTED               +-> FAILED -> APPROVED (retry)
 * REQUESTED -> CANCELLED  (customer withdrew it, e.g. chose to rebook instead)
 */
public enum RefundStatus {

    /** Raised by the customer, an admin, or the cancellation flow. Not yet actioned. */
    REQUESTED,

    /** An admin has picked it up. Informational, but it stops two admins double-approving. */
    UNDER_REVIEW,

    /** Approved for payout. Money has NOT left yet — the dispatcher picks these up. */
    APPROVED,

    /** Accepted by the gateway; waiting for the provider webhook to confirm settlement. */
    PROCESSING,

    /** Provider confirmed the money is on its way back to the original payment method. */
    COMPLETED,

    /** Declined under policy, with a reason the customer can read. Terminal. */
    REJECTED,

    /** The gateway call failed. Retryable — the reconciliation job re-arms it. */
    FAILED,

    /** Withdrawn before payout (customer rebooked instead). Terminal; frees the booking for a new request. */
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED;
    }

    /** True while this refund still owes the customer money or a decision. */
    public boolean isOpen() {
        return this == REQUESTED || this == UNDER_REVIEW || this == APPROVED
                || this == PROCESSING || this == FAILED;
    }

    public boolean canTransitionTo(RefundStatus next) {
        return switch (this) {
            case REQUESTED    -> next == UNDER_REVIEW || next == APPROVED || next == REJECTED || next == CANCELLED;
            case UNDER_REVIEW -> next == APPROVED || next == REJECTED || next == REQUESTED;
            case APPROVED     -> next == PROCESSING || next == FAILED || next == REJECTED;
            case PROCESSING   -> next == COMPLETED || next == FAILED;
            case FAILED       -> next == APPROVED || next == PROCESSING || next == REJECTED;
            default           -> false; // COMPLETED / REJECTED / CANCELLED are terminal
        };
    }

    public String label() {
        return switch (this) {
            case REQUESTED    -> "Refund requested";
            case UNDER_REVIEW -> "Under review";
            case APPROVED     -> "Approved";
            case PROCESSING   -> "Processing with your bank";
            case COMPLETED    -> "Refund completed";
            case REJECTED     -> "Not approved";
            case FAILED       -> "Retrying";
            case CANCELLED    -> "Withdrawn";
        };
    }
}
