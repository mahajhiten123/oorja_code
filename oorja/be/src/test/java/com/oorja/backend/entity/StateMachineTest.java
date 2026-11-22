package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import static com.oorja.backend.entity.enums.BookingStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pins the legal state transitions - changing one is a business decision and should fail a test first. */
class StateMachineTest {

    @Test
    void bookingHappyPathIsAllowed() {
        assertTrue(PENDING.canTransitionTo(ASSIGNED));
        assertTrue(ASSIGNED.canTransitionTo(ON_SITE));
        assertTrue(ON_SITE.canTransitionTo(COMPLETED));
    }

    @Test
    void completedIsFinal() {
        for (BookingStatus next : BookingStatus.values()) {
            assertFalse(COMPLETED.canTransitionTo(next), "COMPLETED must not move to " + next);
        }
    }

    @Test
    void cancelledCanOnlyBeRevivedToPending() {
        for (BookingStatus next : BookingStatus.values()) {
            assertEquals(next == PENDING, CANCELLED.canTransitionTo(next), "CANCELLED -> " + next);
        }
    }

    @Test
    void openBookingsCanBeCancelledButNotMovedBackwards() {
        assertTrue(PENDING.canTransitionTo(CANCELLED));
        assertTrue(ON_SITE.canTransitionTo(CANCELLED));
        assertFalse(ASSIGNED.canTransitionTo(PENDING));
        assertFalse(ON_SITE.canTransitionTo(ASSIGNED));
    }

    @Test
    void capturedMoneyIsNeverDowngraded() {
        assertFalse(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.FAILED));
        assertFalse(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.CREATED));
        assertFalse(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.CANCELLED));
        assertTrue(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void failedAttemptCanStillBeCapturedLate() {
        assertTrue(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CAPTURED));
        assertTrue(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.CAPTURED));
    }

    @Test
    void terminalPaymentStatesStayTerminal() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertFalse(PaymentStatus.REFUNDED.canTransitionTo(next));
            assertFalse(PaymentStatus.CANCELLED.canTransitionTo(next));
        }
    }

    @Test
    void payableAndCapturedHelpersAgreeWithTheMachine() {
        assertTrue(PaymentStatus.CREATED.isPayable());
        assertFalse(PaymentStatus.CAPTURED.isPayable());
        assertTrue(PaymentStatus.PARTIALLY_REFUNDED.isCaptured());
        assertFalse(PaymentStatus.FAILED.isCaptured());
    }
}
