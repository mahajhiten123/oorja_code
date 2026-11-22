package com.oorja.backend.entity.enums;

public enum InvoiceStatus {
    ISSUED,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    CANCELLED;

    public String label() {
        return switch (this) {
            case ISSUED -> "Issued";
            case PAID -> "Paid";
            case PARTIALLY_REFUNDED -> "Partially refunded";
            case REFUNDED -> "Refunded";
            case CANCELLED -> "Cancelled";
        };
    }
}
