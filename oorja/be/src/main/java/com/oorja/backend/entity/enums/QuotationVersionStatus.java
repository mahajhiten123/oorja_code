package com.oorja.backend.entity.enums;

/**
 * Lifecycle of a single {@code quotation_versions} row.
 *
 * A version's financial fields (subtotal/discount/tax/total/notes/validUntil)
 * are only ever written while it is DRAFT. The moment it leaves DRAFT
 * (transitions to SENT), those fields are frozen forever — later status
 * transitions (VIEWED/ACCEPTED/REJECTED/EXPIRED/SUPERSEDED) never touch them.
 * A revision is always a brand-new version, never an edit of an old one.
 */
public enum QuotationVersionStatus {
    /** Being drafted by admin. Invisible to the customer; the only status whose financial fields may still change. */
    DRAFT,
    /** Sent to the customer and is (or was) the quotation's current version. */
    SENT,
    /** Customer has opened this version at least once. */
    VIEWED,
    /** Customer accepted this exact version. Locks it — see Quotation.acceptedVersionId. */
    ACCEPTED,
    /** Customer rejected this version. */
    REJECTED,
    /** Passed its validUntil without a response. */
    EXPIRED,
    /** A newer version was sent (or the customer re-negotiated) before this one was resolved. */
    SUPERSEDED
}
