package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.repository.RefundRepository;
import com.oorja.backend.service.RefundEligibilityService.Decision;
import com.oorja.backend.service.RefundEligibilityService.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** The cancellation policy, evaluated at a fixed instant so every boundary is deterministic. */
@ExtendWith(MockitoExtension.class)
class RefundEligibilityServiceTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Mock RefundRepository refundRepository;

    RefundPolicyProperties policy;
    RefundEligibilityService engine;

    @BeforeEach
    void setUp() {
        policy = new RefundPolicyProperties(); // defaults: 48h full, 24h partial @75%, late @50%, 2h late window, ₹199 visit fee
        engine = new RefundEligibilityService(policy, refundRepository);
    }

    private Booking booking(long hoursUntilSlot, BookingStatus status) {
        LocalDateTime slot = NOW.plusHours(hoursUntilSlot);
        return Booking.builder()
                .id(1L)
                .status(status)
                .scheduledDate(slot.toLocalDate())
                .scheduledTime(slot.toLocalTime())
                .amountPaise(295_000L)
                .paymentRef("pay_123")
                .createdAt(NOW.minusDays(1))
                .build();
    }

    private Decision evaluate(Booking b, RefundReasonCode code) {
        return engine.evaluate(b, code, NOW);
    }

    // ---------------------------------------------------------------- time tiers

    @Test
    void comfortablyAheadIsAFullAutoApprovedRefund() {
        Decision d = evaluate(booking(72, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);
        assertTrue(d.eligible());
        assertEquals(Tier.FULL_REFUND_WINDOW, d.tier());
        assertEquals(10_000, d.refundPercentBps());
        assertEquals(0, d.flatFeePaise());
        assertTrue(d.isFullRefund());
        assertTrue(d.autoApprovable());
        assertFalse(d.rebookingPreferred());
    }

    @Test
    void exactlyFortyEightHoursIsStillFull() {
        assertEquals(Tier.FULL_REFUND_WINDOW, evaluate(booking(48, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST).tier());
    }

    @Test
    void justUnderFortyEightHoursIsThePartialTier() {
        Decision d = evaluate(booking(47, BookingStatus.ASSIGNED), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.PARTIAL_REFUND_WINDOW, d.tier());
        assertEquals(7_500, d.refundPercentBps());
        assertTrue(d.autoApprovable());
        assertTrue(d.rebookingPreferred(), "moving the booking is better for the customer in this tier");
        assertFalse(d.isFullRefund());
    }

    @Test
    void exactlyTwentyFourHoursIsStillPartial() {
        assertEquals(Tier.PARTIAL_REFUND_WINDOW, evaluate(booking(24, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST).tier());
    }

    @Test
    void underTwentyFourHoursIsALateCancellationAtFiftyPercent() {
        Decision d = evaluate(booking(23, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.LATE_CANCELLATION, d.tier());
        assertEquals(5_000, d.refundPercentBps());
        assertTrue(d.autoApprovable(), "late, but not yet inside the 2-hour window");
    }

    @Test
    void insideTheTwoHourWindowARefundNeedsAHuman() {
        Decision d = evaluate(booking(1, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.LATE_CANCELLATION, d.tier());
        assertFalse(d.autoApprovable());
        assertTrue(d.rebookingPreferred());
    }

    @Test
    void exactlyTwoHoursOutIsNotYetInTheLateWindow() {
        assertTrue(evaluate(booking(2, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST).autoApprovable());
    }

    @Test
    void aSlotAlreadyInThePastIsTreatedAsLateNotAnError() {
        Decision d = evaluate(booking(-5, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.LATE_CANCELLATION, d.tier());
        assertFalse(d.autoApprovable());
    }

    @Test
    void policyThresholdsComeFromConfigurationNotCode() {
        policy.setFullRefundHours(72);
        policy.setPartialPercentBps(6_000);
        Decision d = evaluate(booking(60, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.PARTIAL_REFUND_WINDOW, d.tier());
        assertEquals(6_000, d.refundPercentBps());
    }

    // ---------------------------------------------------------------- status-driven tiers

    @Test
    void aTechnicianAlreadyOnSiteKeepsTheVisitFee() {
        Decision d = evaluate(booking(100, BookingStatus.ON_SITE), RefundReasonCode.CUSTOMER_REQUEST);
        assertEquals(Tier.TECHNICIAN_DISPATCHED, d.tier());
        assertEquals(5_000, d.refundPercentBps());
        assertEquals(19_900, d.flatFeePaise());
        assertFalse(d.autoApprovable());
    }

    @Test
    void completedWorkIsNotRefundable() {
        Decision d = evaluate(booking(-48, BookingStatus.COMPLETED), RefundReasonCode.CUSTOMER_REQUEST);
        assertFalse(d.eligible());
        assertEquals(Tier.SERVICE_ALREADY_DELIVERED, d.tier());
        assertEquals(0, d.refundPercentBps());
    }

    @Test
    void aNoShowGetsNothingByDefaultButTheRateIsConfigurable() {
        Decision none = evaluate(booking(-1, BookingStatus.ASSIGNED), RefundReasonCode.CUSTOMER_NO_SHOW);
        assertEquals(Tier.CUSTOMER_NO_SHOW, none.tier());
        assertFalse(none.eligible());

        policy.setNoShowPercentBps(2_000);
        Decision some = evaluate(booking(-1, BookingStatus.ASSIGNED), RefundReasonCode.CUSTOMER_NO_SHOW);
        assertTrue(some.eligible());
        assertEquals(2_000, some.refundPercentBps());
        assertEquals(19_900, some.flatFeePaise());
    }

    // ---------------------------------------------------------------- our fault

    @Test
    void ourFaultIsAlwaysAFullAutomaticRefundWhateverTheTimingOrStatus() {
        for (RefundReasonCode code : new RefundReasonCode[]{RefundReasonCode.PROVIDER_CANCELLED, RefundReasonCode.SERVICE_FAILURE,
                RefundReasonCode.PAYMENT_ERROR, RefundReasonCode.DUPLICATE_PAYMENT, RefundReasonCode.GOODWILL}) {
            Decision d = evaluate(booking(1, BookingStatus.COMPLETED), code);
            assertTrue(d.eligible(), code.name());
            assertEquals(Tier.PROVIDER_FAULT, d.tier(), code.name());
            assertEquals(10_000, d.refundPercentBps(), code.name());
            assertEquals(0, d.flatFeePaise(), code.name());
            assertTrue(d.autoApprovable(), code.name());
        }
    }

    @Test
    void providerFaultAutoApprovalCanBeSwitchedOff() {
        policy.setAutoApproveProviderFault(false);
        assertFalse(evaluate(booking(72, BookingStatus.PENDING), RefundReasonCode.PROVIDER_CANCELLED).autoApprovable());
    }

    // ---------------------------------------------------------------- hard gates

    @Test
    void noPaymentMeansNothingToRefund() {
        Booking unpaid = booking(72, BookingStatus.PENDING);
        unpaid.setAmountPaise(null);
        assertEquals(Tier.NOT_PAID, evaluate(unpaid, RefundReasonCode.CUSTOMER_REQUEST).tier());

        Booking zero = booking(72, BookingStatus.PENDING);
        zero.setAmountPaise(0L);
        assertEquals(Tier.NOT_PAID, evaluate(zero, RefundReasonCode.CUSTOMER_REQUEST).tier());

        Booking noRef = booking(72, BookingStatus.PENDING);
        noRef.setPaymentRef("  ");
        assertEquals(Tier.NOT_PAID, evaluate(noRef, RefundReasonCode.CUSTOMER_REQUEST).tier());
    }

    @Test
    void unpaidBeatsEvenAProviderFaultReason() {
        Booking unpaid = booking(72, BookingStatus.PENDING);
        unpaid.setPaymentRef(null);
        Decision d = evaluate(unpaid, RefundReasonCode.PROVIDER_CANCELLED);
        assertFalse(d.eligible());
        assertEquals(Tier.NOT_PAID, d.tier());
    }

    @Test
    void aSecondRefundForTheSameBookingIsRefused() {
        when(refundRepository.findActiveByBookingId(1L)).thenReturn(Optional.of(Refund.builder().id(9L).build()));

        Decision d = evaluate(booking(72, BookingStatus.PENDING), RefundReasonCode.CUSTOMER_REQUEST);

        assertFalse(d.eligible());
        assertEquals(Tier.ALREADY_REFUNDED, d.tier());
    }

    @Test
    void aPaymentOlderThanTheGatewayWindowCannotBeRefundedToSource() {
        Booking old = booking(72, BookingStatus.PENDING);
        old.setCreatedAt(NOW.minusDays(181));
        Decision d = evaluate(old, RefundReasonCode.CUSTOMER_REQUEST);
        assertFalse(d.eligible());
        assertEquals(Tier.OUTSIDE_REFUND_WINDOW, d.tier());
    }

    @Test
    void exactlyOneHundredEightyDaysOldIsStillInsideTheWindow() {
        Booking edge = booking(72, BookingStatus.PENDING);
        edge.setCreatedAt(NOW.minusDays(180));
        assertTrue(evaluate(edge, RefundReasonCode.CUSTOMER_REQUEST).eligible());
    }

    @Test
    void ourFaultIgnoresTheGatewayWindow() {
        Booking old = booking(72, BookingStatus.PENDING);
        old.setCreatedAt(NOW.minusDays(400));
        assertTrue(evaluate(old, RefundReasonCode.PROVIDER_CANCELLED).eligible());
    }

    @Test
    void everyDenialExplainsItselfInAReadableSentence() {
        Booking completed = booking(-48, BookingStatus.COMPLETED);
        Decision d = evaluate(completed, RefundReasonCode.CUSTOMER_REQUEST);
        assertTrue(d.message() != null && d.message().endsWith("."), d.message());
    }

    @Test
    void theStoredTierNameRoundTripsAndBadValuesFallBackSafely() {
        assertEquals(Tier.LATE_CANCELLATION,
                engine.tierOf(Refund.builder().policyTier("LATE_CANCELLATION").build()));
        assertEquals(Tier.FULL_REFUND_WINDOW, engine.tierOf(Refund.builder().policyTier("NOPE").build()));
        assertEquals(Tier.FULL_REFUND_WINDOW, engine.tierOf(Refund.builder().build()));
    }
}
