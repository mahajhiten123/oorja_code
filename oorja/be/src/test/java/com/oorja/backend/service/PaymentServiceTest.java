package com.oorja.backend.service;

import com.oorja.backend.dto.request.CreateOrderForQuotationRequest;
import com.oorja.backend.dto.request.CreateOrderRequest;
import com.oorja.backend.dto.request.PriceQuoteRequest;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.dto.response.VerifyPaymentResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.PaymentTransaction;
import com.oorja.backend.entity.Quotation;
import com.oorja.backend.entity.enums.PaymentStatus;
import com.oorja.backend.entity.enums.PaymentTransactionType;
import com.oorja.backend.entity.enums.QuotationStatus;
import com.oorja.backend.entity.enums.SlotHoldState;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PaymentRepository;
import com.oorja.backend.repository.PaymentTransactionRepository;
import com.oorja.backend.repository.QuotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock RazorpayService razorpay;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository ledger;
    @Mock CustomerRepository customerRepository;
    @Mock QuotationRepository quotationRepository;
    @Mock CodeGeneratorService codes;
    @Mock SlotService slots;
    @Mock PlatformTransactionManager txManager;

    PaymentService service;
    Customer customer;

    final LocalDate day = LocalDate.now().plusDays(10);
    final LocalTime nine = LocalTime.of(9, 0);

    @BeforeEach
    void setUp() {
        service = new PaymentService(new PricingService(), razorpay, paymentRepository, ledger, customerRepository,
                quotationRepository, codes, slots, txManager);
        ReflectionTestUtils.setField(service, "slotHoldMinutes", 30);

        customer = Customer.builder().id(5L).name("Asha").email("asha@test.io").build();

        lenient().when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(customerRepository.findById(5L)).thenReturn(Optional.of(customer));
        lenient().when(codes.nextPaymentNumber()).thenReturn("PAY-TEST-1");
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(razorpay.getPublicKeyId()).thenReturn("rzp_test_key");
        lenient().when(razorpay.createOrder(anyLong(), any())).thenAnswer(inv -> {
            Number amount = inv.getArgument(0);
            return CreateOrderResponse.builder().orderId("order_new").amount(amount.longValue()).currency("INR").keyId("rzp_test_key").build();
        });
    }

    // ---------------------------------------------------------------- fixtures

    private CreateOrderRequest order(String serviceId, String tier, String coupon, LocalDate d, LocalTime t) {
        return new CreateOrderRequest(serviceId, tier, coupon, "rcpt_1", 77L, d, t, "Cleaning", "notes", "Pune");
    }

    private Payment payment(PaymentStatus status) {
        return Payment.builder().id(40L).customer(customer).amountPaise(295_000L).status(status)
                .razorpayOrderId("order_1").build();
    }

    private Payment savedPayment() {
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- price quote

    @Test
    void quoteReturnsRupeesFromTheServerSidePriceList() {
        var q = service.quote(new PriceQuoteRequest("cleaning", "deep", null, false));
        assertEquals(4130.0, q.total());
        assertEquals(3500.0, q.subtotal());
    }

    // ---------------------------------------------------------------- create order

    @Test
    void theGatewayOrderAndThePaymentRowUseTheServerComputedAmount() {
        CreateOrderResponse response = service.createOrder(5L, order("cleaning", "regular", null, null, null));

        verify(razorpay).createOrder(295_000L, "rcpt_1");
        assertEquals("order_new", response.getOrderId());
        Payment saved = savedPayment();
        assertEquals(295_000L, saved.getAmountPaise());
        assertEquals(PaymentStatus.CREATED, saved.getStatus());
        assertEquals("order_new", saved.getRazorpayOrderId());
        assertEquals(SlotHoldState.NONE, saved.getSlotHoldState());
        assertNull(saved.getSlotHoldExpiresAt());
        assertEquals(customer, saved.getCustomer());
        verify(slots, never()).reserve(any(), any());
    }

    @Test
    void theOpeningLedgerRowIsWritten() {
        service.createOrder(5L, order("cleaning", "regular", null, null, null));

        ArgumentCaptor<PaymentTransaction> tx = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(ledger).save(tx.capture());
        assertEquals(PaymentTransactionType.ORDER_CREATED, tx.getValue().getType());
        assertEquals(295_000L, tx.getValue().getAmountPaise());
        assertEquals("order_new", tx.getValue().getProviderTransactionId());
    }

    @Test
    void aValidCouponIsAppliedAndRemembered() {
        service.createOrder(5L, order("cleaning", "regular", "OOM20", null, null));

        verify(razorpay).createOrder(236_000L, "rcpt_1");
        assertEquals("OOM20", savedPayment().getCouponCode());
    }

    @Test
    void anInvalidCouponIsNotRemembered() {
        service.createOrder(5L, order("cleaning", "regular", "BOGUS", null, null));

        verify(razorpay).createOrder(295_000L, "rcpt_1");
        assertNull(savedPayment().getCouponCode());
    }

    @Test
    void choosingASlotClaimsTheSeatAndStartsTheHoldClock() {
        service.createOrder(5L, order("cleaning", "regular", null, day, nine));

        verify(slots).assertBookable(day, nine);
        verify(slots).reserve(day, nine);
        Payment saved = savedPayment();
        assertEquals(SlotHoldState.HELD, saved.getSlotHoldState());
        assertEquals(day, saved.getDraftScheduledDate());
        assertEquals(nine, saved.getDraftScheduledTime());
        assertTrue(saved.getSlotHoldExpiresAt().isAfter(LocalDateTime.now().plusMinutes(29)));
        assertTrue(saved.getSlotHoldExpiresAt().isBefore(LocalDateTime.now().plusMinutes(31)));
    }

    @Test
    void aFullSlotIsRefusedBeforeAnyGatewayOrderIsOpened() {
        doThrow(ApiException.conflict("That slot just filled up.", "SLOT_FULL")).when(slots).assertBookable(day, nine);

        ApiException e = assertThrows(ApiException.class, () -> service.createOrder(5L, order("cleaning", null, null, day, nine)));

        assertEquals("SLOT_FULL", e.getCode());
        verify(razorpay, never()).createOrder(anyLong(), any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void losingTheRaceForTheLastSeatLeavesNoPaymentBehind() {
        doThrow(ApiException.conflict("That slot just filled up.", "SLOT_FULL")).when(slots).reserve(day, nine);

        ApiException e = assertThrows(ApiException.class, () -> service.createOrder(5L, order("cleaning", null, null, day, nine)));

        assertEquals("SLOT_FULL", e.getCode());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(ledger, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void aDateWithoutATimeIsABadRequest() {
        ApiException e = assertThrows(ApiException.class, () -> service.createOrder(5L, order("cleaning", null, null, day, null)));
        assertEquals(400, e.getStatus().value());
        verify(razorpay, never()).createOrder(anyLong(), any());
    }

    @Test
    void anUnknownServiceNeverReachesTheGateway() {
        ApiException e = assertThrows(ApiException.class, () -> service.createOrder(5L, order("teleportation", null, null, null, null)));
        assertEquals(400, e.getStatus().value());
        verify(razorpay, never()).createOrder(anyLong(), any());
    }

    @Test
    void anUnknownCustomerIsA404() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.createOrder(99L, order("cleaning", null, null, null, null))).getStatus().value());
    }

    // ---------------------------------------------------------------- idempotent create order

    @Test
    void aRetryWithTheSameKeyGetsTheOriginalOrderAndOpensNoNewOne() {
        Payment existing = payment(PaymentStatus.CREATED);
        when(paymentRepository.findByCustomerIdAndIdempotencyKey(5L, "key-12345")).thenReturn(Optional.of(existing));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(existing));

        CreateOrderResponse replay = service.createOrder(5L, order("cleaning", null, null, null, null), "key-12345");

        assertEquals("order_1", replay.getOrderId());
        assertEquals(295_000L, replay.getAmount());
        assertEquals("rzp_test_key", replay.getKeyId());
        verify(razorpay, never()).createOrder(anyLong(), any());
    }

    @Test
    void replayingAnAlreadyPaidOrderTellsTheClientItIsPaid() {
        Payment paid = payment(PaymentStatus.CAPTURED);
        when(paymentRepository.findByCustomerIdAndIdempotencyKey(5L, "key-12345")).thenReturn(Optional.of(paid));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(paid));

        ApiException e = assertThrows(ApiException.class, () -> service.createOrder(5L, order("cleaning", null, null, null, null), "key-12345"));

        assertEquals(409, e.getStatus().value());
        assertEquals("ALREADY_PAID", e.getCode());
    }

    @Test
    void replayingExtendsALiveSeatHold() {
        Payment held = payment(PaymentStatus.CREATED);
        held.setDraftScheduledDate(day);
        held.setDraftScheduledTime(nine);
        held.setSlotHoldState(SlotHoldState.HELD);
        held.setSlotHoldExpiresAt(LocalDateTime.now().plusMinutes(1));
        when(paymentRepository.findByCustomerIdAndIdempotencyKey(5L, "key-12345")).thenReturn(Optional.of(held));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(held));

        service.createOrder(5L, order("cleaning", null, null, day, nine), "key-12345");

        assertTrue(held.getSlotHoldExpiresAt().isAfter(LocalDateTime.now().plusMinutes(25)));
        verify(slots, never()).reserve(any(), any());
    }

    @Test
    void replayingALapsedHoldReclaimsTheSeatOrFailsWithSlotFull() {
        Payment lapsed = payment(PaymentStatus.CREATED);
        lapsed.setDraftScheduledDate(day);
        lapsed.setDraftScheduledTime(nine);
        lapsed.setSlotHoldState(SlotHoldState.RELEASED);
        when(paymentRepository.findByCustomerIdAndIdempotencyKey(5L, "key-12345")).thenReturn(Optional.of(lapsed));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(lapsed));

        service.createOrder(5L, order("cleaning", null, null, day, nine), "key-12345");

        verify(slots).reserve(day, nine);
        assertEquals(SlotHoldState.HELD, lapsed.getSlotHoldState());

        lapsed.setSlotHoldState(SlotHoldState.RELEASED);
        doThrow(ApiException.conflict("gone", "SLOT_FULL")).when(slots).reserve(day, nine);
        assertEquals("SLOT_FULL", assertThrows(ApiException.class,
                () -> service.createOrder(5L, order("cleaning", null, null, day, nine), "key-12345")).getCode());
    }

    @Test
    void findOrderByIdempotencyKeyIsEmptyWhenThereIsNone() {
        assertTrue(service.findOrderByIdempotencyKey(5L, null).isEmpty());
        when(paymentRepository.findByCustomerIdAndIdempotencyKey(5L, "nope-12345")).thenReturn(Optional.empty());
        assertTrue(service.findOrderByIdempotencyKey(5L, "nope-12345").isEmpty());
    }

    // ---------------------------------------------------------------- verify

    private VerifyPaymentRequest verifyRequest(String paymentId) {
        return new VerifyPaymentRequest("order_1", paymentId, "sig");
    }

    @Test
    void aGoodSignatureCapturesThePaymentAndWritesOneLedgerRow() {
        Payment p = payment(PaymentStatus.CREATED);
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        VerifyPaymentResponse r = service.verify(5L, verifyRequest("pay_1"));

        assertTrue(r.verified());
        assertEquals(PaymentStatus.CAPTURED, p.getStatus());
        assertEquals("pay_1", p.getRazorpayPaymentId());
        assertNotNull(p.getCapturedAt());
        ArgumentCaptor<PaymentTransaction> tx = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(ledger, times(1)).save(tx.capture());
        assertEquals(PaymentTransactionType.PAYMENT_CAPTURED, tx.getValue().getType());
    }

    @Test
    void verifyingTwiceIsHarmlessAndWritesNothingTheSecondTime() {
        Payment p = payment(PaymentStatus.CAPTURED);
        p.setRazorpayPaymentId("pay_1");
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        assertTrue(service.verify(5L, verifyRequest("pay_1")).verified());

        verify(ledger, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void anOrderAlreadyPaidWithADifferentPaymentIsNotVerified() {
        Payment p = payment(PaymentStatus.CAPTURED);
        p.setRazorpayPaymentId("pay_1");
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        VerifyPaymentResponse r = service.verify(5L, verifyRequest("pay_OTHER"));

        assertFalse(r.verified());
        assertEquals("pay_1", p.getRazorpayPaymentId());
    }

    @Test
    void aForgedSignatureDoesNotCaptureAndDoesNotPoisonTheOrder() {
        Payment p = payment(PaymentStatus.CREATED);
        when(razorpay.verifySignature(any())).thenReturn(false);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        VerifyPaymentResponse r = service.verify(5L, verifyRequest("pay_1"));

        assertFalse(r.verified());
        assertEquals(PaymentStatus.CREATED, p.getStatus(), "a bad signature is not evidence the payment failed");
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void someoneElsesOrderIsNeverCaptured() {
        Payment p = payment(PaymentStatus.CREATED);
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        ApiException ex = assertThrows(ApiException.class, () -> service.verify(6L, verifyRequest("pay_1")));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals(PaymentStatus.CREATED, p.getStatus());
    }

    @Test
    void anUnknownOrderIsNotVerified() {
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.verify(5L, verifyRequest("pay_1")));
    }

    // ---------------------------------------------------------------- applyCapture

    @Test
    void aLateCaptureOnAFailedOrderIsStillRecordedBecauseTheMoneyIsReal() {
        Payment p = payment(PaymentStatus.FAILED);

        service.applyCapture(p, "pay_late", "webhook");

        assertEquals(PaymentStatus.CAPTURED, p.getStatus());
        assertEquals("pay_late", p.getRazorpayPaymentId());
    }

    @Test
    void capturingAnAlreadyCapturedPaymentIsANoOp() {
        Payment p = payment(PaymentStatus.CAPTURED);
        p.setRazorpayPaymentId("pay_1");

        service.applyCapture(p, "pay_2", "webhook");

        assertEquals("pay_1", p.getRazorpayPaymentId());
        verify(ledger, never()).save(any(PaymentTransaction.class));
    }

    // ---------------------------------------------------------------- consume captured payment

    @Test
    void aCapturedPaymentOfTheRightCustomerCanBeSpent() {
        Payment p = payment(PaymentStatus.CAPTURED);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        assertEquals(p, service.consumeCapturedPayment(5L, "order_1"));
    }

    @Test
    void spendingRequiresAKnownCapturedOwnBookingPayment() {
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_x")).thenReturn(Optional.empty());
        assertEquals(400, assertThrows(ApiException.class, () -> service.consumeCapturedPayment(5L, "order_x")).getStatus().value());

        Payment unpaid = payment(PaymentStatus.CREATED);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(unpaid));
        assertEquals(400, assertThrows(ApiException.class, () -> service.consumeCapturedPayment(5L, "order_1")).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.consumeCapturedPayment(6L, "order_1")).getStatus().value());

        Payment quotation = payment(PaymentStatus.CAPTURED);
        quotation.setQuotationId(9L);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_q")).thenReturn(Optional.of(quotation));
        assertEquals(400, assertThrows(ApiException.class, () -> service.consumeCapturedPayment(5L, "order_q")).getStatus().value());
    }

    @Test
    void aQuotationPaymentMustBelongToThatQuotation() {
        Payment p = payment(PaymentStatus.CAPTURED);
        p.setQuotationId(9L);
        when(paymentRepository.findByRazorpayOrderIdForUpdate("order_1")).thenReturn(Optional.of(p));

        assertEquals(p, service.consumeCapturedQuotationPayment(5L, 9L, "order_1"));
        assertEquals(400, assertThrows(ApiException.class, () -> service.consumeCapturedQuotationPayment(5L, 10L, "order_1")).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.consumeCapturedQuotationPayment(6L, 9L, "order_1")).getStatus().value());
    }

    // ---------------------------------------------------------------- slot hold hand-over and expiry

    private Payment heldPayment() {
        Payment p = payment(PaymentStatus.CREATED);
        p.setDraftScheduledDate(day);
        p.setDraftScheduledTime(nine);
        p.setSlotHoldState(SlotHoldState.HELD);
        p.setSlotHoldExpiresAt(LocalDateTime.now().plusMinutes(20));
        return p;
    }

    @Test
    void theBookingTakesOverTheSeatHeldAtCheckoutWithoutClaimingASecond() {
        Payment p = heldPayment();

        assertTrue(service.takeOverSlotHold(p, day, nine));

        assertEquals(SlotHoldState.CONSUMED, p.getSlotHoldState());
        assertNull(p.getSlotHoldExpiresAt());
        verify(slots, never()).reserve(any(), any());
        verify(slots, never()).release(any(), any());
    }

    @Test
    void pickingADifferentSlotClaimsTheNewSeatThenGivesBackTheOldOne() {
        Payment p = heldPayment();
        LocalTime eleven = LocalTime.of(11, 0);

        assertTrue(service.takeOverSlotHold(p, day, eleven));

        verify(slots).reserve(day, eleven);
        verify(slots).release(day, nine);
        assertEquals(SlotHoldState.CONSUMED, p.getSlotHoldState());
    }

    @Test
    void ifTheNewSlotIsFullTheOriginalHoldIsLeftUntouched() {
        Payment p = heldPayment();
        LocalTime eleven = LocalTime.of(11, 0);
        doThrow(ApiException.conflict("full", "SLOT_FULL")).when(slots).reserve(day, eleven);

        assertThrows(ApiException.class, () -> service.takeOverSlotHold(p, day, eleven));

        assertEquals(SlotHoldState.HELD, p.getSlotHoldState());
        verify(slots, never()).release(any(), any());
    }

    @Test
    void aPaymentWithoutAHoldLeavesTheSeatClaimToTheCaller() {
        Payment p = payment(PaymentStatus.CAPTURED);
        assertFalse(service.takeOverSlotHold(p, day, nine));
        assertEquals(SlotHoldState.NONE, p.getSlotHoldState());
    }

    @Test
    void anExpiredUnpaidHoldIsReleased() {
        Payment p = heldPayment();
        p.setSlotHoldExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(p));

        assertTrue(service.releaseHoldIfExpired(40L));

        verify(slots).release(day, nine);
        assertEquals(SlotHoldState.RELEASED, p.getSlotHoldState());
    }

    @Test
    void aLiveHoldANonHeldPaymentAndAPaidPaymentAreNeverReleased() {
        Payment live = heldPayment();
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(live));
        assertFalse(service.releaseHoldIfExpired(40L));

        Payment paid = heldPayment();
        paid.setStatus(PaymentStatus.CAPTURED);
        paid.setSlotHoldExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(paymentRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(paid));
        assertFalse(service.releaseHoldIfExpired(41L), "a captured payment never loses its seat");

        Payment none = payment(PaymentStatus.CREATED);
        when(paymentRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(none));
        assertFalse(service.releaseHoldIfExpired(42L));

        when(paymentRepository.findByIdForUpdate(43L)).thenReturn(Optional.empty());
        assertFalse(service.releaseHoldIfExpired(43L));

        verify(slots, never()).release(any(), any());
    }

    // ---------------------------------------------------------------- quotation orders

    private Quotation quotation(QuotationStatus status, String total) {
        return Quotation.builder().id(9L).customer(customer).status(status)
                .totalAmount(total == null ? null : new BigDecimal(total)).build();
    }

    private CreateOrderForQuotationRequest quotationOrder() {
        return new CreateOrderForQuotationRequest(9L, "rcpt_q");
    }

    @Test
    void aQuotationOrderChargesTheAdminPricedAmountInPaise() {
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "50000.00")));
        when(paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(eq(9L), any())).thenReturn(List.of());

        service.createOrderForQuotation(5L, quotationOrder());

        verify(razorpay).createOrder(5_000_000L, "rcpt_q");
        Payment saved = savedPayment();
        assertEquals(5_000_000L, saved.getAmountPaise());
        assertEquals(9L, saved.getQuotationId());
    }

    @Test
    void aQuotationRoundsPartPaiseHalfUp() {
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "1234.565")));
        when(paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(eq(9L), any())).thenReturn(List.of());

        service.createOrderForQuotation(5L, quotationOrder());

        verify(razorpay).createOrder(123_457L, "rcpt_q");
    }

    @Test
    void quotationOrdersAreRefusedUnlessAcceptedUnpaidPricedAndOwned() {
        when(quotationRepository.findById(404L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.createOrderForQuotation(5L, new CreateOrderForQuotationRequest(404L, "r"))).getStatus().value());

        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "100.00")));
        assertEquals(403, assertThrows(ApiException.class, () -> service.createOrderForQuotation(6L, quotationOrder())).getStatus().value());

        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.SENT, "100.00")));
        assertEquals(400, assertThrows(ApiException.class, () -> service.createOrderForQuotation(5L, quotationOrder())).getStatus().value());

        Quotation paid = quotation(QuotationStatus.ACCEPTED, "100.00");
        paid.setPaymentRef("pay_done");
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(paid));
        ApiException already = assertThrows(ApiException.class, () -> service.createOrderForQuotation(5L, quotationOrder()));
        assertEquals("ALREADY_PAID", already.getCode());

        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, null)));
        assertEquals(400, assertThrows(ApiException.class, () -> service.createOrderForQuotation(5L, quotationOrder())).getStatus().value());

        verify(razorpay, never()).createOrder(anyLong(), any());
    }

    @Test
    void aSecondClickReusesTheOpenOrderForTheSameAmount() {
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "2950.00")));
        Payment open = payment(PaymentStatus.CREATED);
        open.setQuotationId(9L);
        when(paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(eq(9L), any())).thenReturn(List.of(open));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(open));

        CreateOrderResponse r = service.createOrderForQuotation(5L, quotationOrder());

        assertEquals("order_1", r.getOrderId());
        verify(razorpay, never()).createOrder(anyLong(), any());
    }

    @Test
    void aRevisedPriceRetiresTheStaleOpenOrderAndOpensAFreshOne() {
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "60000.00")));
        Payment stale = payment(PaymentStatus.CREATED);
        stale.setAmountPaise(5_000_000L);
        stale.setQuotationId(9L);
        when(paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(eq(9L), any())).thenReturn(List.of(stale));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(stale));

        CreateOrderResponse r = service.createOrderForQuotation(5L, quotationOrder());

        assertEquals(PaymentStatus.CANCELLED, stale.getStatus(), "the customer can never pay an out-of-date price");
        assertEquals(6_000_000L, r.getAmount());
        verify(razorpay).createOrder(6_000_000L, "rcpt_q");
    }

    @Test
    void losingTheRaceToOpenTheQuotationOrderHandsBackTheWinnersOrder() {
        when(quotationRepository.findById(9L)).thenReturn(Optional.of(quotation(QuotationStatus.ACCEPTED, "2950.00")));
        Payment winner = payment(PaymentStatus.CREATED);
        winner.setQuotationId(9L);
        when(paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(eq(9L), any()))
                .thenReturn(List.of(), List.of(winner));
        when(paymentRepository.save(any(Payment.class))).thenThrow(new DataIntegrityViolationException("uq_payments_quotation_open"));
        when(paymentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(winner));

        CreateOrderResponse r = service.createOrderForQuotation(5L, quotationOrder());

        assertEquals("order_1", r.getOrderId());
    }
}
