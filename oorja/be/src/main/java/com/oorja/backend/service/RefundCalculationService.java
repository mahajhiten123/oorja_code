package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.repository.RefundRepository;
import com.oorja.backend.service.RefundEligibilityService.Decision;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Stage 3: <b>exactly how much?</b>
 *
 * Takes the eligibility band and turns it into an itemised, auditable
 * breakdown. Two rules matter more than anything else here:
 *
 * <ol>
 *   <li><b>Integer paise only.</b> Percentages are applied with long
 *       arithmetic and rounded half-up once, at the end. No doubles ever
 *       touch money — {@code 0.1 + 0.2} problems in a payout are how you
 *       end up off-by-a-paisa against the gateway forever.</li>
 *   <li><b>Never exceed the refundable balance.</b> The net is clamped
 *       against what the payment actually captured minus everything already
 *       committed to other refunds, so partial refunds can't add up to more
 *       than the customer paid — even if the policy says they should.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RefundCalculationService {

    private final RefundPolicyProperties policy;
    private final RefundRepository refundRepository;

    @Builder
    public record Breakdown(
            long grossPaise,
            int percentBps,
            /** gross x percent, before flat fees. */
            long policyAmountPaise,
            long cancellationFeePaise,
            long gatewayFeePaise,
            /** Everything withheld: gross - net. */
            long deductionsPaise,
            /** The figure actually sent to the gateway. */
            long netPaise,
            /** What was still refundable on this payment before this refund. */
            long refundableBalancePaise,
            boolean clamped
    ) {}

    /**
     * @param grossPaise what the customer paid for this booking
     * @param payment the funding payment, if known — used to cap against
     *                refunds already committed elsewhere on the same payment
     */
    public Breakdown calculate(long grossPaise, Decision decision, Payment payment) {

        long capturedPaise = payment != null ? payment.getAmountPaise() : grossPaise;
        long alreadyCommitted = payment != null ? refundRepository.sumCommittedForPayment(payment.getId()) : 0L;
        long refundableBalance = Math.max(0L, capturedPaise - alreadyCommitted);

        if (!decision.eligible()) {
            return Breakdown.builder()
                    .grossPaise(grossPaise).percentBps(0).policyAmountPaise(0L)
                    .cancellationFeePaise(0L).gatewayFeePaise(0L)
                    .deductionsPaise(grossPaise).netPaise(0L)
                    .refundableBalancePaise(refundableBalance).clamped(false)
                    .build();
        }

        int bps = clampBps(decision.refundPercentBps());
        long policyAmount = applyBps(grossPaise, bps);

        long cancellationFee = Math.min(decision.flatFeePaise(), policyAmount);
        long gatewayFee = Math.min(policy.getGatewayFeePaise(), Math.max(0L, policyAmount - cancellationFee));

        long net = policyAmount - cancellationFee - gatewayFee;

        // Dust: paying out ₹0.63 costs more in reconciliation than it's worth,
        // and a customer seeing "refunded ₹0" is worse than seeing nothing.
        if (net > 0 && net < policy.getMinimumPayoutPaise()) {
            net = 0L;
        }

        boolean clamped = net > refundableBalance;
        if (clamped) {
            net = refundableBalance;
        }
        net = Math.max(0L, Math.min(net, grossPaise));

        return Breakdown.builder()
                .grossPaise(grossPaise)
                .percentBps(bps)
                .policyAmountPaise(policyAmount)
                .cancellationFeePaise(cancellationFee)
                .gatewayFeePaise(gatewayFee)
                .deductionsPaise(grossPaise - net)
                .netPaise(net)
                .refundableBalancePaise(refundableBalance)
                .clamped(clamped)
                .build();
    }

    /** Half-up percentage in pure integer arithmetic: (amount * bps + 5000) / 10000. */
    static long applyBps(long amountPaise, int bps) {
        if (amountPaise <= 0 || bps <= 0) return 0L;
        return Math.floorDiv(amountPaise * (long) bps + 5_000L, 10_000L);
    }

    private static int clampBps(int bps) {
        return Math.max(0, Math.min(10_000, bps));
    }
}
