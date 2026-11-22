package com.oorja.backend.entity.enums;

/**
 * OPEN → IN_PROGRESS → (WAITING_CUSTOMER ⇄ IN_PROGRESS) → RESOLVED → CLOSED
 *
 * RESOLVED is not the end: the customer can reply and reopen, which is the
 * difference between a ticket system and a suggestion box.
 */
public enum SupportTicketStatus {
    OPEN,
    IN_PROGRESS,
    WAITING_CUSTOMER,
    RESOLVED,
    CLOSED;

    public boolean isOpen() {
        return this != RESOLVED && this != CLOSED;
    }

    public String label() {
        return switch (this) {
            case OPEN -> "Open";
            case IN_PROGRESS -> "Being looked at";
            case WAITING_CUSTOMER -> "Waiting on you";
            case RESOLVED -> "Resolved";
            case CLOSED -> "Closed";
        };
    }
}
