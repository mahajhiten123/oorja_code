package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.repository.RefundRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Stage 2 of the pipeline: <b>may we refund this at all, and on what terms?</b>
 *
 * Pure and deterministic — same booking, same clock, same answer, no writes,
 * no gateway calls. That's what lets the customer app show an accurate
 * "you'll get ₹X back" preview <i>before</i> they commit to cancelling, using
 * the identical code path that later produces the real refund.
 *
 * The engine only decides eligibility and the policy band. Turning a band
 * into rupees is {@link RefundCalculationService}'s job.
 */
@Service
@RequiredArgsConstructor
public class RefundEligibilityService {

    private final RefundPolicyProperties policy;
    private final RefundRepository refundRepository;

    /** Policy bands. The name is stored on the refund row for auditing. */
    public enum Tier {
        FULL_REFUND_WINDOW,
        PARTIAL_REFUND_WINDOW,
        LATE_CANCELLATION,
        TECHNICIAN_DISPATCHED,
        CUSTOMER_NO_SHOW,
        PROVIDER_FAULT,
        SERVICE_ALREADY_DELIVERED,
        NOT_PAID,
        ALREADY_REFUNDED,
        OUTSIDE_REFUND_WINDOW
    }

    @Builder
    public record Decision(
            boolean eligible,
            Tier tier,
            int refundPercentBps,
            long flatFeePaise,
            boolean autoApprovable,
            boolean rebookingPreferred,
            /** Shown verbatim to the customer, so it must always read as a sentence. */
            String message
    ) {
        public boolean isFullRefund() {
            return refundPercentBps >= 10_000 && flatFeePaise == 0L;
        }
    }

    /**
     * Evaluates a cancellation/refund request.
     *
     * @param booking the booking being cancelled
     * @param reasonCode why — provider-fault codes bypass the time tiers entirely
     * @param at the moment to evaluate against (injected rather than read from the
     *           clock so previews, tests and the real request all agree)
     */
    public Decision evaluate(Booking booking, RefundReasonCode reasonCode, LocalDateTime at) {

        // --- Hard gates: no money, nothing to refund. ---
        if (booking.getAmountPaise() == null || booking.getAmountPaise() <= 0
                || booking.getPaymentRef() == null || booking.getPaymentRef().isBlank()) {
            return deny(Tier.NOT_PAID,
                    "There's no payment on this booking, so there's nothing to refund. You can cancel it free of charge.");
        }

        if (refundRepository.findActiveByBookingId(booking.getId()).isPresent()) {
            return deny(Tier.ALREADY_REFUNDED,
                    "A refund for this booking is already in progress. Check its status under Refunds.");
        }

        // --- Our fault, or a payment error: always whole, always automatic. ---
        if (reasonCode.impliesFullRefund()) {
            return Decision.builder()
                    .eligible(true)
                    .tier(Tier.PROVIDER_FAULT)
                    .refundPercentBps(10_000)
                    .flatFeePaise(0L)
                    .autoApprovable(policy.isAutoApproveProviderFault())
                    .rebookingPreferred(false)
                    .message("You'll be refunded in full — this cancellation wasn't your doing.")
                    .build();
        }

        // --- Gateway can't reach back this far. ---
        long daysSincePayment = Duration.between(
                booking.getCreatedAt() != null ? booking.getCreatedAt() : at, at).toDays();
        if (daysSincePayment > policy.getRequestWindowDays()) {
            return deny(Tier.OUTSIDE_REFUND_WINDOW, String.format(
                    "This payment is more than %d days old, so it can no longer be refunded to your original payment method. "
                            + "Contact support and we'll sort out an alternative.",
                    policy.getRequestWindowDays()));
        }

        // --- Work already delivered. ---
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            return deny(Tier.SERVICE_ALREADY_DELIVERED,
                    "This service has already been completed. If something went wrong on site, raise a service issue "
                            + "instead and our team will review it.");
        }

        if (reasonCode == RefundReasonCode.CUSTOMER_NO_SHOW) {
            return Decision.builder()
                    .eligible(policy.getNoShowPercentBps() > 0)
                    .tier(Tier.CUSTOMER_NO_SHOW)
                    .refundPercentBps(policy.getNoShowPercentBps())
                    .flatFeePaise(policy.getDispatchedVisitFeePaise())
                    .autoApprovable(false)
                    .rebookingPreferred(true)
                    .message("The technician travelled to site for this visit, so the visit charge is retained. "
                            + "Rebooking keeps the full value of what you've paid.")
                    .build();
        }

        // --- Technician already on site: the visit has cost us regardless. ---
        if (booking.getStatus() == BookingStatus.ON_SITE) {
            return Decision.builder()
                    .eligible(true)
                    .tier(Tier.TECHNICIAN_DISPATCHED)
                    .refundPercentBps(policy.getLatePercentBps())
                    .flatFeePaise(policy.getDispatchedVisitFeePaise())
                    .autoApprovable(false)
                    .rebookingPreferred(true)
                    .message("Your technician is already on the way or on site, so a visit charge applies. "
                            + "Moving the booking to another day instead keeps every rupee you've paid.")
                    .build();
        }

        // --- Time-based tiers. ---
        LocalDateTime slot = LocalDateTime.of(booking.getScheduledDate(), booking.getScheduledTime());
        long hoursToSlot = Duration.between(at, slot).toHours();

        if (hoursToSlot >= policy.getFullRefundHours()) {
            return Decision.builder()
                    .eligible(true)
                    .tier(Tier.FULL_REFUND_WINDOW)
                    .refundPercentBps(10_000)
                    .flatFeePaise(0L)
                    .autoApprovable(true)
                    .rebookingPreferred(false)
                    .message(String.format(
                            "You're cancelling more than %d hours ahead, so you get a full refund.",
                            policy.getFullRefundHours()))
                    .build();
        }

        if (hoursToSlot >= policy.getPartialRefundHours()) {
            return Decision.builder()
                    .eligible(true)
                    .tier(Tier.PARTIAL_REFUND_WINDOW)
                    .refundPercentBps(policy.getPartialPercentBps())
                    .flatFeePaise(0L)
                    .autoApprovable(true)
                    .rebookingPreferred(true)
                    .message(String.format(
                            "Your slot is under %d hours away, so %d%% of what you paid comes back. "
                                    + "Rescheduling instead costs you nothing.",
                            policy.getFullRefundHours(), policy.getPartialPercentBps() / 100))
                    .build();
        }

        boolean inLateWindow = hoursToSlot < policy.getLateWindowHours();
        return Decision.builder()
                .eligible(true)
                .tier(Tier.LATE_CANCELLATION)
                .refundPercentBps(policy.getLatePercentBps())
                .flatFeePaise(policy.getLateCancellationFeePaise())
                .autoApprovable(!inLateWindow)
                .rebookingPreferred(true)
                .message(String.format(
                        "This is a late cancellation — the crew and route for your slot are already committed — "
                                + "so %d%% comes back. Moving to another day instead keeps the full amount.",
                        policy.getLatePercentBps() / 100))
                .build();
    }

    /** Convenience for re-reading a stored decision back off a refund row. */
    public Tier tierOf(Refund refund) {
        try {
            return Tier.valueOf(refund.getPolicyTier());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Tier.FULL_REFUND_WINDOW;
        }
    }

    private Decision deny(Tier tier, String message) {
        return Decision.builder()
                .eligible(false).tier(tier)
                .refundPercentBps(0).flatFeePaise(0L)
                .autoApprovable(false).rebookingPreferred(tier == Tier.SERVICE_ALREADY_DELIVERED)
                .message(message)
                .build();
    }
}
