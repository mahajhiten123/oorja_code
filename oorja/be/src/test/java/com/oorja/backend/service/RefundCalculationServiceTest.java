package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.repository.RefundRepository;
import com.oorja.backend.service.RefundCalculationService.Breakdown;
import com.oorja.backend.service.RefundEligibilityService.Decision;
import com.oorja.backend.service.RefundEligibilityService.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** Money maths: integer paise only, half-up once at the end, never more than the payment still holds. */
@ExtendWith(MockitoExtension.class)
class RefundCalculationServiceTest {

    @Mock RefundRepository refundRepository;

    RefundPolicyProperties policy;
    RefundCalculationService calc;

    final Payment payment = Payment.builder().id(7L).amountPaise(295_000L).build();

    @BeforeEach
    void setUp() {
        policy = new RefundPolicyProperties();
        calc = new RefundCalculationService(policy, refundRepository);
    }

    private static Decision decision(boolean eligible, int bps, long flatFee) {
        return Decision.builder().eligible(eligible).tier(Tier.FULL_REFUND_WINDOW)
                .refundPercentBps(bps).flatFeePaise(flatFee).message("m.").build();
    }

    @Test
    void aFullRefundReturnsEveryPaiseWithNoDeductions() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(0L);

        Breakdown b = calc.calculate(295_000, decision(true, 10_000, 0), payment);

        assertEquals(295_000, b.netPaise());
        assertEquals(10_000, b.percentBps());
        assertEquals(0, b.deductionsPaise());
        assertEquals(295_000, b.refundableBalancePaise());
        assertFalse(b.clamped());
    }

    @Test
    void seventyFivePercentRoundsHalfUpOnce() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(0L);

        Breakdown b = calc.calculate(295_000, decision(true, 7_500, 0), payment);

        assertEquals(221_250, b.policyAmountPaise()); // 221,250.5 -> floor of (x*bps+5000)/10000
        assertEquals(221_250, b.netPaise());
        assertEquals(73_750, b.deductionsPaise());
    }

    @Test
    void aFlatFeeIsTakenOffThePercentageAmount() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(0L);

        Breakdown b = calc.calculate(295_000, decision(true, 5_000, 19_900), payment);

        assertEquals(147_500, b.policyAmountPaise());
        assertEquals(19_900, b.cancellationFeePaise());
        assertEquals(127_600, b.netPaise());
    }

    @Test
    void aFlatFeeNeverPushesTheRefundBelowZero() {
        Breakdown b = calc.calculate(10_000, decision(true, 5_000, 19_900), null);

        assertEquals(5_000, b.cancellationFeePaise(), "the fee is capped at what the policy would have refunded");
        assertEquals(0, b.netPaise());
    }

    @Test
    void theGatewayFeeIsPassedOnWhenConfigured() {
        policy.setGatewayFeePaise(500);
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(0L);

        Breakdown b = calc.calculate(295_000, decision(true, 10_000, 0), payment);

        assertEquals(500, b.gatewayFeePaise());
        assertEquals(294_500, b.netPaise());
    }

    @Test
    void dustBelowTheMinimumPayoutIsNotPaid() {
        assertEquals(99, calc.calculate(99, decision(true, 10_000, 0), null).policyAmountPaise());
        assertEquals(0, calc.calculate(99, decision(true, 10_000, 0), null).netPaise(), "below the 100-paise minimum");
        assertEquals(100, calc.calculate(100, decision(true, 10_000, 0), null).netPaise());
    }

    @Test
    void neverRefundsMoreThanIsStillHeldOnThePayment() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(100_000L);

        Breakdown b = calc.calculate(295_000, decision(true, 10_000, 0), payment);

        assertEquals(195_000, b.refundableBalancePaise());
        assertEquals(195_000, b.netPaise());
        assertTrue(b.clamped(), "the clamp is flagged so it can be audited");
    }

    @Test
    void aFullyRefundedPaymentHasNothingLeftToRefund() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(295_000L);

        Breakdown b = calc.calculate(295_000, decision(true, 10_000, 0), payment);

        assertEquals(0, b.refundableBalancePaise());
        assertEquals(0, b.netPaise());
    }

    @Test
    void anIneligibleDecisionPaysNothingAndWithholdsEverything() {
        when(refundRepository.sumCommittedForPayment(7L)).thenReturn(0L);

        Breakdown b = calc.calculate(295_000, decision(false, 0, 0), payment);

        assertEquals(0, b.netPaise());
        assertEquals(295_000, b.deductionsPaise());
        assertEquals(0, b.percentBps());
    }

    @Test
    void withNoLinkedPaymentTheGrossIsTheCeiling() {
        Breakdown b = calc.calculate(250_000, decision(true, 10_000, 0), null);
        assertEquals(250_000, b.netPaise());
        assertEquals(250_000, b.refundableBalancePaise());
    }

    @Test
    void outOfRangePercentagesAreClamped() {
        assertEquals(10_000, calc.calculate(100_000, decision(true, 25_000, 0), null).percentBps());
        assertEquals(0, calc.calculate(100_000, decision(true, -500, 0), null).netPaise());
    }

    @Test
    void aZeroAmountBookingRefundsZero() {
        assertEquals(0, calc.calculate(0, decision(true, 10_000, 0), null).netPaise());
    }

    @Test
    void percentageMathIsExactIntegerHalfUp() {
        assertEquals(501, RefundCalculationService.applyBps(1_001, 5_000)); // 500.5 -> 501
        assertEquals(1, RefundCalculationService.applyBps(1, 5_000));       // 0.5 -> 1
        assertEquals(0, RefundCalculationService.applyBps(1, 4_999));       // 0.4999 -> 0
        assertEquals(0, RefundCalculationService.applyBps(0, 5_000));
        assertEquals(0, RefundCalculationService.applyBps(1_000, 0));
        assertEquals(7_080_000, RefundCalculationService.applyBps(7_080_000, 10_000));
    }

    @Test
    void theNetIsNeverAboveTheGross() {
        for (int bps : new int[]{0, 1, 2_500, 5_000, 7_500, 9_999, 10_000}) {
            for (long gross : new long[]{0, 1, 99, 100, 101, 199_00, 295_000, 7_080_000}) {
                Breakdown b = calc.calculate(gross, decision(true, bps, 0), null);
                assertTrue(b.netPaise() >= 0 && b.netPaise() <= gross, "bps=" + bps + " gross=" + gross + " net=" + b.netPaise());
                assertEquals(gross - b.netPaise(), b.deductionsPaise());
            }
        }
    }
}
