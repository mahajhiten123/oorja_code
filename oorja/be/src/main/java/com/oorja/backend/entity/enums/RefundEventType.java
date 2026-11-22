package com.oorja.backend.entity.enums;

/** The kinds of rows that can appear in a refund's append-only audit trail. */
public enum RefundEventType {
    REQUESTED,
    ELIGIBILITY_EVALUATED,
    AMOUNT_CALCULATED,
    REVIEW_STARTED,
    APPROVED,
    AUTO_APPROVED,
    REJECTED,
    GATEWAY_SUBMITTED,
    GATEWAY_FAILED,
    WEBHOOK_RECEIVED,
    COMPLETED,
    RECONCILED,
    LEDGER_MISMATCH,
    WITHDRAWN_FOR_REBOOKING,
    NOTE
}
