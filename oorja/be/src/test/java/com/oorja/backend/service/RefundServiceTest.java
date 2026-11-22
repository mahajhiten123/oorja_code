package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.dto.request.AdminRefundRequest;
import com.oorja.backend.dto.response.RefundResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.PaymentTransaction;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.RefundEvent;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.PaymentStatus;
import com.oorja.backend.entity.enums.PaymentTransactionType;
import com.oorja.backend.entity.enums.RefundEventType;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.entity.enums.RefundStatus;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.PaymentRepository;
import com.oorja.backend.repository.PaymentTransactionRepository;
import com.oorja.backend.repository.RefundEventRepository;
import com.oorja.backend.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refund state machine with the real eligibility + calculation engines and mocked persistence:
 * what gets created, what gets approved automatically, and which transitions are refused.
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock RefundRepository refundRepository;
    @Mock RefundEventRepository refundEventRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository paymentTransactionRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CodeGeneratorService codeGenerator;
    @Mock ApplicationEventPublisher events;
    @Mock InvoiceService invoiceService;

    RefundPolicyProperties policy;
    RefundService service;

    Customer customer;
    Payment payment;
    Booking booking;

    @BeforeEach
    void setUp() {
        policy = new RefundPolicyProperties();
        service = new RefundService(refundRepository, refundEventRepository, paymentRepository,
                paymentTransactionRepository, bookingRepository,
                new RefundEligibilityService(policy, refundRepository),
                new RefundCalculationService(policy, refundRepository),
                codeGenerator, policy, events, invoiceService);

        customer = Customer.builder().id(5L).name("Asha").email("asha@test.io").build();
        payment = Payment.builder().id(3L).customer(customer).amountPaise(295_000L)
                .status(PaymentStatus.CAPTURED).razorpayPaymentId("pay_1").build();
        booking = paidBooking(295_000L, LocalDateTime.now().plusDays(5));

        lenient().when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeGenerator.nextRefundNumber()).thenReturn("RFD-TEST-1");
        lenient().when(paymentRepository.findByBookingIdOrderByCreatedAtDesc(11L)).thenReturn(List.of(payment));
    }

    // ---------------------------------------------------------------- fixtures

    private Booking paidBooking(long paise, LocalDateTime slot) {
        return Booking.builder().id(11L).token("TKN-1").customer(customer)
                .status(BookingStatus.PENDING)
                .scheduledDate(slot.toLocalDate()).scheduledTime(slot.toLocalTime())
                .amountPaise(paise).paymentRef("pay_1").build();
    }

    private Refund refundIn(RefundStatus status, long gross, long net) {
        return Refund.builder().id(21L).refundNumber("RFD-21").booking(booking).customer(customer).payment(payment)
                .reasonCode(RefundReasonCode.CUSTOMER_REQUEST).requestedByType(SubjectType.CUSTOMER)
                .grossAmountPaise(gross).amountPaise(net).refundPercentBps(10_000)
                .cancellationFeePaise(0L).gatewayFeePaise(0L)
                .razorpayPaymentId("pay_1").status(status).build();
    }

    private Refund lockedRefund(RefundStatus status) {
        Refund r = refundIn(status, 295_000L, 295_000L);
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(r));
        return r;
    }

    private List<RefundEventType> auditedTypes() {
        ArgumentCaptor<RefundEvent> captor = ArgumentCaptor.forClass(RefundEvent.class);
        verify(refundEventRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(RefundEvent::getType).toList();
    }

    private Refund request(RefundReasonCode code) {
        return service.requestForCancelledBooking(booking, code, "reason", SubjectType.CUSTOMER, 5L, "customer");
    }

    // ---------------------------------------------------------------- request: eligibility & approval routing

    @Test
    void anUnpaidBookingRaisesNoRefund() {
        booking.setAmountPaise(null);
        booking.setPaymentRef(null);

        assertNull(request(RefundReasonCode.CUSTOMER_REQUEST));
        verify(refundRepository, never()).save(any(Refund.class));
    }

    @Test
    void aFullWindowRefundIsApprovedAutomaticallyAndPublishedForDispatch() {
        Refund refund = request(RefundReasonCode.CUSTOMER_REQUEST);

        assertEquals(RefundStatus.APPROVED, refund.getStatus());
        assertTrue(refund.getAutoApproved());
        assertEquals(295_000L, refund.getAmountPaise());
        assertEquals(295_000L, refund.getGrossAmountPaise());
        assertEquals("FULL_REFUND_WINDOW", refund.getPolicyTier());
        assertEquals("pay_1", refund.getRazorpayPaymentId(), "the refund targets the gateway payment that funded the booking");
        assertNotNull(refund.getNextAttemptAt(), "due immediately so the dispatcher picks it up");
        assertTrue(refund.getIdempotencyKey().startsWith("rfd-11-"));
        verify(events).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void everyStepOfTheDecisionIsAudited() {
        request(RefundReasonCode.CUSTOMER_REQUEST);

        List<RefundEventType> types = auditedTypes();
        assertTrue(types.contains(RefundEventType.REQUESTED));
        assertTrue(types.contains(RefundEventType.ELIGIBILITY_EVALUATED));
        assertTrue(types.contains(RefundEventType.AMOUNT_CALCULATED));
        assertTrue(types.contains(RefundEventType.AUTO_APPROVED));
    }

    @Test
    void aLargeRefundWaitsForAHumanEvenInTheFullWindow() {
        booking = paidBooking(7_080_000L, LocalDateTime.now().plusDays(5));
        payment.setAmountPaise(7_080_000L);
        when(paymentRepository.findByBookingIdOrderByCreatedAtDesc(11L)).thenReturn(List.of(payment));

        Refund refund = request(RefundReasonCode.CUSTOMER_REQUEST);

        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        assertFalse(refund.getAutoApproved());
        assertEquals(7_080_000L, refund.getAmountPaise());
        verify(events, never()).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void aPartialWindowRefundIsSeventyFivePercentAndNeedsApproval() {
        booking = paidBooking(295_000L, LocalDateTime.now().plusHours(30));

        Refund refund = request(RefundReasonCode.CUSTOMER_REQUEST);

        assertEquals("PARTIAL_REFUND_WINDOW", refund.getPolicyTier());
        assertEquals(221_250L, refund.getAmountPaise());
        assertEquals(7_500, refund.getRefundPercentBps());
        assertEquals(RefundStatus.REQUESTED, refund.getStatus(), "only full refunds skip human approval");
    }

    @Test
    void aLateCancellationInsideTheWindowKeepsHalfAndNeedsApproval() {
        booking = paidBooking(295_000L, LocalDateTime.now().plusHours(1));

        Refund refund = request(RefundReasonCode.CUSTOMER_REQUEST);

        assertEquals("LATE_CANCELLATION", refund.getPolicyTier());
        assertEquals(147_500L, refund.getAmountPaise());
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
    }

    @Test
    void ourFaultIsAutoApprovedEvenForALargeAmount() {
        booking = paidBooking(7_080_000L, LocalDateTime.now().plusHours(1));
        payment.setAmountPaise(7_080_000L);
        when(paymentRepository.findByBookingIdOrderByCreatedAtDesc(11L)).thenReturn(List.of(payment));

        Refund refund = request(RefundReasonCode.PROVIDER_CANCELLED);

        assertEquals(RefundStatus.APPROVED, refund.getStatus());
        assertEquals(7_080_000L, refund.getAmountPaise());
        assertEquals("PROVIDER_FAULT", refund.getPolicyTier());
        verify(events).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void aPolicyThatRefundsNothingIsRecordedAsRejectedNotPaid() {
        Refund refund = request(RefundReasonCode.CUSTOMER_NO_SHOW);

        assertEquals(RefundStatus.REJECTED, refund.getStatus());
        assertEquals(0L, refund.getAmountPaise());
        assertNotNull(refund.getRejectedAt());
        verify(events, never()).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void aRefundIsCappedAtWhatThePaymentStillHoldsAndTheCapIsAudited() {
        when(refundRepository.sumCommittedForPayment(3L)).thenReturn(100_000L);

        Refund refund = request(RefundReasonCode.CUSTOMER_REQUEST);

        assertEquals(195_000L, refund.getAmountPaise());
        assertTrue(auditedTypes().contains(RefundEventType.LEDGER_MISMATCH));
    }

    @Test
    void previewQuotesTheSameNumbersWithoutCreatingAnything() {
        RefundService.Quote quote = service.preview(booking, RefundReasonCode.CUSTOMER_REQUEST);

        assertTrue(quote.decision().eligible());
        assertEquals(295_000L, quote.breakdown().netPaise());
        verify(refundRepository, never()).save(any(Refund.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    // ---------------------------------------------------------------- admin-raised

    @Test
    void anAdminCannotRaiseASecondRefundForABookingThatAlreadyHasOne() {
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking));
        when(refundRepository.findActiveByBookingId(11L)).thenReturn(Optional.of(refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L)));

        ApiException e = assertThrows(ApiException.class, () ->
                service.createByAdmin(new AdminRefundRequest(11L, RefundReasonCode.GOODWILL, "sorry", null), "ops@oorja"));

        assertEquals(409, e.getStatus().value());
    }

    @Test
    void anAdminCannotRefundABookingThatWasNeverPaid() {
        booking.setPaymentRef(null);
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking));

        ApiException e = assertThrows(ApiException.class, () ->
                service.createByAdmin(new AdminRefundRequest(11L, RefundReasonCode.GOODWILL, null, null), "ops@oorja"));

        assertEquals(400, e.getStatus().value());
    }

    @Test
    void refundingAnUnknownBookingIsA404() {
        when(bookingRepository.findById(99L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () ->
                service.createByAdmin(new AdminRefundRequest(99L, RefundReasonCode.GOODWILL, null, null), "ops@oorja"));

        assertEquals(404, e.getStatus().value());
    }

    // ---------------------------------------------------------------- approval

    @Test
    void approvingAtTheProposedAmountMovesToApprovedAndPublishes() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);

        RefundResponse response = service.approve(21L, null, "ok", "ops@oorja");

        assertEquals(RefundStatus.APPROVED, refund.getStatus());
        assertFalse(refund.getAutoApproved());
        assertEquals("ops@oorja", refund.getApprovedBy());
        assertEquals("APPROVED", response.getStatus());
        verify(events, times(1)).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void anAdminMayOverrideTheAmountWithinTheCeiling() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);
        when(refundRepository.sumCommittedForPayment(3L)).thenReturn(0L);

        service.approve(21L, 200_000L, "goodwill", "ops@oorja");

        assertEquals(200_000L, refund.getAmountPaise());
        assertEquals(95_000L, refund.getCancellationFeePaise(), "the shortfall is recorded as a deduction");
        assertEquals("goodwill", refund.getAdminNote());
        assertEquals(RefundStatus.APPROVED, refund.getStatus());
    }

    @Test
    void anOverrideAboveWhatWasPaidIsRefusedAndNothingChanges() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);
        when(refundRepository.sumCommittedForPayment(3L)).thenReturn(0L);

        ApiException e = assertThrows(ApiException.class, () -> service.approve(21L, 295_001L, null, "ops@oorja"));

        assertEquals(400, e.getStatus().value());
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        verify(events, never()).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void anOverrideCannotExceedWhatOtherRefundsHaveNotAlreadyTaken() {
        lockedRefund(RefundStatus.REQUESTED);
        when(refundRepository.sumCommittedForPayment(3L)).thenReturn(100_000L);

        ApiException e = assertThrows(ApiException.class, () -> service.approve(21L, 200_000L, null, "ops@oorja"));

        assertEquals(400, e.getStatus().value());
    }

    @Test
    void aNegativeOverrideIsRefused() {
        lockedRefund(RefundStatus.REQUESTED);
        when(refundRepository.sumCommittedForPayment(3L)).thenReturn(0L);

        assertThrows(ApiException.class, () -> service.approve(21L, -1L, null, "ops@oorja"));
    }

    @Test
    void aRejectedRefundCannotBeApproved() {
        lockedRefund(RefundStatus.REJECTED);

        ApiException e = assertThrows(ApiException.class, () -> service.approve(21L, null, null, "ops@oorja"));

        assertEquals(409, e.getStatus().value());
        verify(events, never()).publishEvent(any(RefundService.RefundApprovedEvent.class));
    }

    @Test
    void aRefundAlreadyBeingPaidOutCannotBeApprovedAgain() {
        lockedRefund(RefundStatus.PROCESSING);

        ApiException e = assertThrows(ApiException.class, () -> service.approve(21L, null, null, "ops@oorja"));

        assertEquals(409, e.getStatus().value(), "a double-click must never dispatch a second payout");
    }

    @Test
    void unknownRefundIsA404() {
        when(refundRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.approve(404L, null, null, "ops@oorja"));

        assertEquals(404, e.getStatus().value());
    }

    // ---------------------------------------------------------------- review / reject

    @Test
    void startingAReviewMarksItUnderReview() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);

        service.startReview(21L, "ops@oorja");

        assertEquals(RefundStatus.UNDER_REVIEW, refund.getStatus());
        assertTrue(auditedTypes().contains(RefundEventType.REVIEW_STARTED));
    }

    @Test
    void aFinishedRefundCannotBeReviewed() {
        lockedRefund(RefundStatus.COMPLETED);

        assertEquals(409, assertThrows(ApiException.class, () -> service.startReview(21L, "ops@oorja")).getStatus().value());
    }

    @Test
    void rejectingRecordsTheReasonAndWhen() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);

        service.reject(21L, "Work was completed", "ops@oorja");

        assertEquals(RefundStatus.REJECTED, refund.getStatus());
        assertEquals("Work was completed", refund.getAdminNote());
        assertNotNull(refund.getRejectedAt());
    }

    @Test
    void aRefundThatIsAlreadyProcessingCannotBeRejected() {
        lockedRefund(RefundStatus.PROCESSING);

        assertEquals(409, assertThrows(ApiException.class, () -> service.reject(21L, "x", "ops@oorja")).getStatus().value());
    }

    // ---------------------------------------------------------------- rebooking withdraws the refund

    @Test
    void aCustomerCannotWithdrawSomeoneElsesRefund() {
        lockedRefund(RefundStatus.REQUESTED);

        assertEquals(403, assertThrows(ApiException.class, () -> service.withdrawForRebooking(21L, 99L, null)).getStatus().value());
    }

    @Test
    void onceMoneyIsMovingTheRefundCannotBeWithdrawn() {
        lockedRefund(RefundStatus.PROCESSING);

        assertEquals(400, assertThrows(ApiException.class, () -> service.withdrawForRebooking(21L, 5L, null)).getStatus().value());
    }

    @Test
    void aPendingRefundCanBeWithdrawnForRebooking() {
        Refund refund = lockedRefund(RefundStatus.REQUESTED);

        Refund result = service.withdrawForRebooking(21L, 5L, null);

        assertEquals(RefundStatus.CANCELLED, result.getStatus());
        assertEquals(refund.getId(), result.getId());
        assertTrue(auditedTypes().contains(RefundEventType.WITHDRAWN_FOR_REBOOKING));
    }

    // ---------------------------------------------------------------- gateway webhooks

    @Test
    void aProcessedWebhookCompletesTheRefundWritesTheLedgerAndIssuesACreditNote() {
        Refund refund = refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));

        service.onGatewayRefundProcessed("rfnd_1", 295_000L, "processed");

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertNotNull(refund.getCompletedAt());
        ArgumentCaptor<PaymentTransaction> ledger = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(ledger.capture());
        assertEquals(PaymentTransactionType.REFUND_COMPLETED, ledger.getValue().getType());
        assertEquals(295_000L, ledger.getValue().getAmountPaise());
        verify(invoiceService, times(1)).issueCreditNote(refund);
    }

    @Test
    void aDuplicateProcessedWebhookChangesNothing() {
        Refund refund = refundIn(RefundStatus.COMPLETED, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));

        service.onGatewayRefundProcessed("rfnd_1", 295_000L, "processed");

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        verify(invoiceService, never()).issueCreditNote(any());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void aWebhookForARefundWeNeverIssuedIsIgnoredNotAnError() {
        when(refundRepository.findByRazorpayRefundId("rfnd_unknown")).thenReturn(Optional.empty());

        service.onGatewayRefundProcessed("rfnd_unknown", 100L, "processed");
        service.onGatewayRefundFailed("rfnd_unknown", "failed", "bank said no");

        verify(refundRepository, never()).save(any(Refund.class));
    }

    @Test
    void aDifferentSettledAmountIsFlaggedButTheRefundStillCompletes() {
        Refund refund = refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));

        service.onGatewayRefundProcessed("rfnd_1", 100_000L, "processed");

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertTrue(auditedTypes().contains(RefundEventType.LEDGER_MISMATCH));
    }

    @Test
    void aCreditNoteFailureNeverUndoesAMoneyMovement() {
        Refund refund = refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));
        when(invoiceService.issueCreditNote(any())).thenThrow(new RuntimeException("pdf renderer exploded"));

        service.onGatewayRefundProcessed("rfnd_1", 295_000L, "processed");

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
    }

    @Test
    void aFailedWebhookSchedulesARetryAndRecordsTheBankError() {
        Refund refund = refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));

        service.onGatewayRefundFailed("rfnd_1", "failed", "Account closed");

        assertEquals(RefundStatus.FAILED, refund.getStatus());
        assertEquals("Account closed", refund.getLastError());
        assertTrue(refund.getNextAttemptAt().isAfter(LocalDateTime.now()), "the retry is backed off, not immediate");
        ArgumentCaptor<PaymentTransaction> ledger = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(ledger.capture());
        assertEquals(PaymentTransactionType.REFUND_FAILED, ledger.getValue().getType());
    }

    @Test
    void aLateFailedWebhookCannotReopenACompletedRefund() {
        Refund refund = refundIn(RefundStatus.COMPLETED, 295_000L, 295_000L);
        refund.setRazorpayRefundId("rfnd_1");
        when(refundRepository.findByRazorpayRefundId("rfnd_1")).thenReturn(Optional.of(refund));
        when(refundRepository.findByIdForUpdate(21L)).thenReturn(Optional.of(refund));

        service.onGatewayRefundFailed("rfnd_1", "failed", "late and out of order");

        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        verify(paymentTransactionRepository, never()).save(any(PaymentTransaction.class));
    }

    // ---------------------------------------------------------------- reads

    @Test
    void aCustomerCanReadOnlyTheirOwnRefund() {
        Refund refund = refundIn(RefundStatus.PROCESSING, 295_000L, 295_000L);
        when(refundRepository.findById(21L)).thenReturn(Optional.of(refund));

        assertEquals("PROCESSING", service.findOneForCustomer(21L, 5L).getStatus());
        assertEquals(403, assertThrows(ApiException.class, () -> service.findOneForCustomer(21L, 6L)).getStatus().value());
    }

    @Test
    void hasActiveRefundReflectsTheRepository() {
        when(refundRepository.findActiveByBookingId(11L)).thenReturn(Optional.of(refundIn(RefundStatus.REQUESTED, 1L, 1L)));
        when(refundRepository.findActiveByBookingId(12L)).thenReturn(Optional.empty());

        assertTrue(service.hasActiveRefund(11L));
        assertFalse(service.hasActiveRefund(12L));
    }
}
