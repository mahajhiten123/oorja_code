package com.oorja.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every number the refund policy depends on, in one place and overridable
 * per environment. Nothing in the engine hard-codes a threshold: if finance
 * changes the cancellation window, it's a config change and a restart, not
 * a code change — and the values that applied to any past refund are
 * already frozen on the refund row itself.
 */
@Component
@ConfigurationProperties(prefix = "refund.policy")
@Data
public class RefundPolicyProperties {

    /** Cancel at least this many hours before the slot: full refund. */
    private int fullRefundHours = 48;

    /** Between partialRefundHours and fullRefundHours before the slot: partial refund. */
    private int partialRefundHours = 24;

    /** Inside this many hours of the slot it counts as a late cancellation. */
    private int lateWindowHours = 2;

    /** Partial-tier percentage, in basis points (7500 = 75%). */
    private int partialPercentBps = 7500;

    /** Late-tier percentage, in basis points. */
    private int latePercentBps = 5000;

    /** What a customer gets back after a no-show, in basis points. */
    private int noShowPercentBps = 0;

    /** Flat fee kept when a technician was already dispatched (ON_SITE). */
    private long dispatchedVisitFeePaise = 19900L;

    /** Flat fee kept on a late cancellation, on top of the percentage tier. */
    private long lateCancellationFeePaise = 0L;

    /** Non-refundable gateway charge passed on to the customer. Off by default. */
    private long gatewayFeePaise = 0L;

    /** Refunds at or below this net amount, on a full-refund tier, skip human approval. */
    private long autoApproveMaxPaise = 500_000L;

    /** Auto-approve anything the policy says is entirely our fault, regardless of size. */
    private boolean autoApproveProviderFault = true;

    /** A payment older than this can no longer be refunded to source. */
    private int requestWindowDays = 180;

    /** What we promise the customer: settled within N working days of initiation. */
    private int settlementDays = 7;

    /** Never refund less than this; below it, round up to zero-fee rather than dust. */
    private long minimumPayoutPaise = 100L;

    /** Gateway attempts before a refund stops auto-retrying and waits for an admin. */
    private int maxGatewayAttempts = 5;

    /** Base backoff between gateway attempts; doubles each time. */
    private int retryBackoffMinutes = 15;

    /** A PROCESSING refund with no webhook after this long gets actively polled. */
    private int reconcileStaleAfterMinutes = 30;

    /**
     * When a customer reschedules instead of cancelling, they keep 100% of
     * what they paid regardless of how close the slot is. This is the whole
     * point of offering rebooking first.
     */
    private boolean rebookingKeepsFullValue = true;
}
