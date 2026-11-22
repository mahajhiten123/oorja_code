package com.oorja.backend.entity.enums;

/** A bill, or the document that reverses part of one. */
public enum InvoiceType {
    /** Issued when a payment is captured. */
    TAX_INVOICE,
    /**
     * Issued when a refund completes. Never edits the original invoice —
     * amending a tax document after the fact is how books stop balancing.
     */
    CREDIT_NOTE
}
