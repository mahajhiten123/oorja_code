package com.oorja.backend.entity.enums;

/**
 * The booking lifecycle. Legal moves live in exactly one place —
 * {@link #canTransitionTo} — and every service that changes a booking's status goes through it.
 *
 * <pre>
 * PENDING ──► ASSIGNED ──► ON_SITE ──► COMPLETED   (terminal)
 *    │            │            │
 *    └────────────┴────────────┴──► CANCELLED
 *
 * CANCELLED ──► PENDING   only via "rebook from refund" (the customer revives it at a new slot)
 * </pre>
 *
 * A booking may also be completed straight from PENDING/ASSIGNED (admin shortcut). COMPLETED is final:
 * a finished visit is part of the service/billing record and is never reopened or cancelled.
 * CANCELLED can only be reached through the cancel endpoints (not a generic status edit), because
 * cancelling has side effects — releasing the slot seat and raising the refund.
 */
public enum BookingStatus {
    PENDING,
    ASSIGNED,
    ON_SITE,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case PENDING   -> next == ASSIGNED || next == ON_SITE || next == COMPLETED || next == CANCELLED;
            case ASSIGNED  -> next == ON_SITE || next == COMPLETED || next == CANCELLED;
            case ON_SITE   -> next == COMPLETED || next == CANCELLED;
            case COMPLETED -> false;
            case CANCELLED -> next == PENDING;
        };
    }

    /** Still a live visit: it holds a slot seat and can be rescheduled or cancelled. */
    public boolean isOpen() {
        return this == PENDING || this == ASSIGNED || this == ON_SITE;
    }
}
