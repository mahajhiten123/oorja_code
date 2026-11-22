package com.oorja.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What the customer sees <i>before</i> they cancel anything.
 *
 * This is the single most important object in the refund UX: it's computed
 * by the same eligibility and calculation code that will later produce the
 * real refund, so the number quoted here is the number that arrives. It
 * also carries the rebooking offer, because the best outcome for both sides
 * is usually a different date rather than a refund.
 */
@Data
@Builder
public class CancellationPreviewResponse {

    private Long bookingId;
    private String bookingToken;
    private String serviceLabel;
    private String scheduledDate;
    private String scheduledTime;

    /** Whether any money comes back at all. */
    private boolean refundable;
    private String policyTier;
    /** A full sentence explaining the decision, written for the customer. */
    private String message;

    private double paidInr;
    private int refundPercent;
    private double cancellationFeeInr;
    private double gatewayFeeInr;
    private double deductionsInr;
    private double refundAmountInr;

    /** True when this refund would go through without waiting for a human. */
    private boolean autoApproved;
    private int settlementDays;

    // ---- The rebooking alternative ----

    /** True when rescheduling is still possible for this booking. */
    private boolean rebookAvailable;
    /** True when rebooking is strictly better for the customer than refunding. */
    private boolean rebookRecommended;
    /** What they keep by rebooking instead — usually the full amount paid. */
    private double rebookValueInr;
    /** e.g. "Keep all ₹2,400 by moving to another day." */
    private String rebookMessage;
    /** Next few open slots, so the choice is one tap rather than a new journey. */
    private List<SlotSuggestion> suggestedSlots;

    @Data
    @Builder
    public static class SlotSuggestion {
        private String date;
        private String time;
        private String label;
        private int seatsLeft;
    }
}
