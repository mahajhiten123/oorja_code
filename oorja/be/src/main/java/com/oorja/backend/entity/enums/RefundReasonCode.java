package com.oorja.backend.entity.enums;

/**
 * Why a refund is being asked for — the single biggest input to the
 * eligibility engine. A customer changing their mind two hours before the
 * technician arrives is a completely different case from us cancelling on
 * them, and a code, not free text, is what decides that.
 */
public enum RefundReasonCode {

    /** Customer changed their mind / no longer needs the service. Time-based policy applies. */
    CUSTOMER_REQUEST(false),

    /** No slot worked for them after trying to rebook. Time-based, but never worse than the partial tier. */
    RESCHEDULE_UNAVAILABLE(false),

    /** Customer wasn't at the site. Heaviest deduction; the visit still cost us. */
    CUSTOMER_NO_SHOW(false),

    /** Duplicate booking / double charge. Always fully refundable. */
    DUPLICATE_PAYMENT(true),

    /** We cancelled: no technician, weather, site access. Always full, auto-approved. */
    PROVIDER_CANCELLED(true),

    /** Technician attended but the job wasn't done properly. Full by default; admin can adjust. */
    SERVICE_FAILURE(true),

    /** Charged with no service ever scheduled (orphaned payment found by reconciliation). */
    PAYMENT_ERROR(true),

    /** Support/goodwill override. Bypasses the time tiers; admin-only. */
    GOODWILL(true);

    private final boolean fullRefund;

    RefundReasonCode(boolean fullRefund) {
        this.fullRefund = fullRefund;
    }

    /** True when our side (or a system error) caused it, so the customer keeps every rupee. */
    public boolean impliesFullRefund() {
        return fullRefund;
    }

    /** Codes a customer may pick themselves; the rest are admin-only. */
    public boolean customerSelectable() {
        return this == CUSTOMER_REQUEST || this == RESCHEDULE_UNAVAILABLE || this == DUPLICATE_PAYMENT;
    }
}
