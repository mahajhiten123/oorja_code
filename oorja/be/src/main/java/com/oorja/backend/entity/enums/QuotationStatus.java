package com.oorja.backend.entity.enums;

public enum QuotationStatus {
    /** Auto-created when a customer requests a quote; admin hasn't priced it yet. */
    DRAFT,
    /** Admin has priced it and sent it to the customer. */
    SENT,
    /** Customer has opened/viewed the sent quote at least once. */
    VIEWED,
    /** Customer accepted the quoted price. */
    ACCEPTED,
    /** Customer (or admin, on the customer's behalf) rejected the quote. */
    REJECTED,
    /** Passed its validUntil date without being accepted. */
    EXPIRED
}
