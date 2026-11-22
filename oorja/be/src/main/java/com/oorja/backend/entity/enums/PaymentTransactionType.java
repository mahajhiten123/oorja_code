package com.oorja.backend.entity.enums;

public enum PaymentTransactionType {
    ORDER_CREATED,
    PAYMENT_CAPTURED,
    PAYMENT_FAILED,
    REFUND_INITIATED,
    REFUND_COMPLETED,
    /** Gateway rejected or could not settle the refund — kept in the ledger so the money story stays complete. */
    REFUND_FAILED
}
