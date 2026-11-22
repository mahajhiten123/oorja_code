package com.oorja.backend.service;

import com.oorja.backend.dto.request.CreateOrderForQuotationRequest;
import com.oorja.backend.dto.request.CreateOrderRequest;
import com.oorja.backend.dto.request.PriceQuoteRequest;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.AdminPaymentResponse;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.dto.response.PaymentTransactionResponse;
import com.oorja.backend.dto.response.PriceQuoteResponse;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * The one place that's allowed to decide "how much does this customer owe,
 * and did they actually pay it". Everything downstream (Booking, refunds,
 * admin reporting) should read the answer from here rather than re-deriving
 * it — see {@code BookingService#createForCustomer}, which links a Booking
 * to a CAPTURED Payment row instead of accepting an amount from the client.
 *
 * <h3>Transaction boundaries (why the create-order methods are NOT {@code @Transactional})</h3>
 * Opening a Razorpay order is a network call that can take seconds (and is retried with backoff).
 * Inside a {@code @Transactional} method that call would pin a database connection — and, with a seat
 * claimed, a row lock — for its whole duration, so a handful of slow gateway calls could exhaust the
 * connection pool. So create-order works in three steps: (1) read-only validation/pricing, (2) the
 * gateway call with no transaction open, (3) one short transaction that claims the seat and writes the
 * Payment. If step 3 loses (slot just filled, duplicate key) the Razorpay order is simply abandoned —
 * an unpaid order costs nothing.
 *
 * <h3>Idempotency</h3>
 * <ul>
 *   <li>{@code Idempotency-Key}: the same key returns the same order (unique index per customer).</li>
 *   <li>{@code verify}: any number of calls for one order write exactly one PAYMENT_CAPTURED row
 *       (row lock + status check).</li>
 *   <li>A quotation can have only one open order at a time (unique partial index), so a double-click
 *       cannot create two things to pay.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PricingService pricingService;
    private final RazorpayService razorpayService;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CustomerRepository customerRepository;
    private final QuotationRepository quotationRepository;
    private final CodeGeneratorService codeGeneratorService;
    private final SlotService slotService;
    private final PlatformTransactionManager transactionManager;

    /**
     * How long an UNPAID order keeps its seat. Long enough to finish a UPI/netbanking flow, short enough
     * that an abandoned checkout doesn't block a slot for the day. A paid order never loses its seat.
     */
    @Value("${booking.slots.hold-minutes:30}")
    private int slotHoldMinutes;

    @Transactional(readOnly = true)
    public PriceQuoteResponse quote(PriceQuoteRequest request) {
        var q = pricingService.quote(request.serviceId(), request.tierId(), request.couponCode(),
                Boolean.TRUE.equals(request.quoteRequest()));
        return PriceQuoteResponse.from(q);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ===================================================================
    // Step 1 of paying: create the order
    // ===================================================================

    /**
     * Prices the request server-side, opens a Razorpay order for that exact amount, claims the seat for the
     * requested slot, and records a CREATED Payment row so the amount is locked in before the customer
     * sees a Checkout screen.
     */
    public CreateOrderResponse createOrder(Long customerId, CreateOrderRequest request) {
        return createOrder(customerId, request, null);
    }

    /**
     * Idempotent variant: a retry carrying the same Idempotency-Key gets the original Razorpay order back
     * instead of opening a second one. (If two identical requests race, both may call Razorpay, but only one
     * Payment row survives the unique index; the extra, never-paid Razorpay order is harmless.)
     */
    public CreateOrderResponse createOrder(Long customerId, CreateOrderRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Payment> existing = paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return replayOrder(existing.get());
            }
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found"));

        final LocalDate slotDate = request.draftScheduledDate();
        final LocalTime slotTime = request.draftScheduledTime();
        if ((slotDate == null) != (slotTime == null)) {
            throw ApiException.badRequest("Send both draftScheduledDate and draftScheduledTime, or neither.");
        }
        final boolean holdSeat = slotDate != null;
        if (holdSeat) {
            // Fail before opening a gateway order if the slot is invalid or already full.
            slotService.assertBookable(slotDate, slotTime);
        }

        var priced = pricingService.quote(request.serviceId(), request.tierId(), request.couponCode(), false);

        // Gateway call: deliberately outside any DB transaction (see class javadoc).
        CreateOrderResponse orderResponse = razorpayService.createOrder(priced.totalPaise(), request.receipt());

        return tx().execute(status -> {
            LocalDateTime holdExpiresAt = null;
            SlotHoldState holdState = SlotHoldState.NONE;
            if (holdSeat) {
                // Atomic claim (UPDATE ... WHERE booked_count < capacity). If another customer took the last
                // seat in the meantime this throws SLOT_FULL and the whole transaction - including this
                // payment row - rolls back.
                slotService.reserve(slotDate, slotTime);
                holdState = SlotHoldState.HELD;
                holdExpiresAt = LocalDateTime.now().plusMinutes(slotHoldMinutes);
            }

            Payment payment = Payment.builder()
                    .paymentNumber(codeGeneratorService.nextPaymentNumber())
                    .customer(customer)
                    .amountPaise(priced.totalPaise())
                    .currency("INR")
                    .status(PaymentStatus.CREATED)
                    .razorpayOrderId(orderResponse.getOrderId())
                    .serviceId(request.serviceId())
                    .tierId(request.tierId())
                    .couponCode(priced.couponApplied() ? request.couponCode() : null)
                    .draftPlantId(request.draftPlantId())
                    .draftScheduledDate(slotDate)
                    .draftScheduledTime(slotTime)
                    .draftServiceType(request.draftServiceType())
                    .draftNotes(request.draftNotes())
                    .draftLocation(request.draftLocation())
                    .idempotencyKey(idempotencyKey)
                    .slotHoldState(holdState)
                    .slotHoldExpiresAt(holdExpiresAt)
                    .build();
            payment = paymentRepository.save(payment);

            paymentTransactionRepository.save(PaymentTransaction.builder()
                    .payment(payment)
                    .type(PaymentTransactionType.ORDER_CREATED)
                    .amountPaise(priced.totalPaise())
                    .status(PaymentStatus.CREATED.name())
                    .providerTransactionId(orderResponse.getOrderId())
                    .metadata("serviceId=" + request.serviceId() + ", tierId=" + request.tierId())
                    .build());

            return orderResponse;
        });
    }

    /**
     * Same idea as {@link #createOrder}, but for paying an already-priced
     * Quotation (AMC / installation) instead of the service catalog — the
     * amount comes from {@code quotation.getTotalAmount()} (set by an
     * admin), never from the client. Closes the same class of gap: the old
     * flow opened a Razorpay order for whatever amount the frontend sent,
     * so a tampered client could pay ₹1 towards a ₹50,000 quotation and
     * still have {@code recordPayment} mark it paid in full.
     */
    public CreateOrderResponse createOrderForQuotation(Long customerId, CreateOrderForQuotationRequest request) {
        return createOrderForQuotation(customerId, request, null);
    }

    public CreateOrderResponse createOrderForQuotation(Long customerId, CreateOrderForQuotationRequest request,
                                                       String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Payment> existing = paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return replayOrder(existing.get());
            }
        }
        Quotation quotation = quotationRepository.findById(request.quotationId())
                .orElseThrow(() -> ApiException.notFound("Quotation not found"));
        if (!quotation.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This quotation does not belong to your account.");
        }
        if (quotation.getStatus() != QuotationStatus.ACCEPTED) {
            throw ApiException.badRequest("This quotation must be accepted before it can be paid for.");
        }
        if (quotation.getPaymentRef() != null) {
            throw ApiException.conflict("This quotation has already been paid for.", "ALREADY_PAID");
        }
        if (quotation.getTotalAmount() == null) {
            throw ApiException.badRequest("This quotation doesn't have a price yet.");
        }

        final long amountPaise = quotation.getTotalAmount()
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        final Long quotationId = quotation.getId();

        // A second tab / double-click must not open a second thing to pay: reuse the open order for this
        // quotation. If the admin has since revised the price, that order is stale - retire it and open a
        // fresh one at the current amount, so the customer can never pay an out-of-date price.
        Optional<Payment> open = findOpenQuotationOrder(quotationId);
        if (open.isPresent()) {
            if (open.get().getAmountPaise().equals(amountPaise)) {
                return replayOrder(open.get());
            }
            retireStaleOrder(open.get().getId());
        }

        CreateOrderResponse orderResponse = razorpayService.createOrder(amountPaise, request.receipt());

        try {
            return tx().execute(status -> {
                Payment payment = Payment.builder()
                        .paymentNumber(codeGeneratorService.nextPaymentNumber())
                        .customer(quotation.getCustomer())
                        .amountPaise(amountPaise)
                        .currency("INR")
                        .status(PaymentStatus.CREATED)
                        .razorpayOrderId(orderResponse.getOrderId())
                        .quotationId(quotationId)
                        .idempotencyKey(idempotencyKey)
                        .build();
                payment = paymentRepository.save(payment);

                paymentTransactionRepository.save(PaymentTransaction.builder()
                        .payment(payment)
                        .type(PaymentTransactionType.ORDER_CREATED)
                        .amountPaise(amountPaise)
                        .status(PaymentStatus.CREATED.name())
                        .providerTransactionId(orderResponse.getOrderId())
                        .metadata("quotationId=" + quotationId)
                        .build());

                return orderResponse;
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a race on uq_payments_quotation_open / the idempotency index: another request just opened the
            // order. Hand back the winner's order instead of failing; anything else is a real error.
            Optional<Payment> winner = findOpenQuotationOrder(quotationId);
            if (winner.isPresent() && winner.get().getAmountPaise().equals(amountPaise)) {
                return replayOrder(winner.get());
            }
            throw e;
        }
    }

    private Optional<Payment> findOpenQuotationOrder(Long quotationId) {
        return paymentRepository.findByQuotationIdAndStatusInOrderByCreatedAtDesc(
                quotationId, List.of(PaymentStatus.CREATED, PaymentStatus.PENDING)).stream().findFirst();
    }

    private void retireStaleOrder(Long paymentId) {
        tx().executeWithoutResult(status -> paymentRepository.findByIdForUpdate(paymentId).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.CREATED || p.getStatus() == PaymentStatus.PENDING) {
                p.setStatus(PaymentStatus.CANCELLED);
                paymentRepository.save(p);
                paymentTransactionRepository.save(PaymentTransaction.builder()
                        .payment(p)
                        .type(PaymentTransactionType.PAYMENT_FAILED)
                        .amountPaise(p.getAmountPaise())
                        .status(PaymentStatus.CANCELLED.name())
                        .metadata("Superseded: the quotation price changed before this order was paid")
                        .build());
            }
        }));
    }

    /** Resolves the loser of a concurrent duplicate create-order (see PaymentController). */
    public Optional<CreateOrderResponse> findOrderByIdempotencyKey(Long customerId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .map(this::replayOrder);
    }

    /**
     * Hands back an existing order for a retry. Under a row lock it re-checks that the order can still be paid
     * (a captured/refunded one would open a Checkout that can never succeed, so the client is told what really
     * happened) and refreshes the seat hold, so a customer who comes back after a pause is not sent to pay for
     * a seat that has since been given away.
     */
    private CreateOrderResponse replayOrder(Payment existing) {
        return tx().execute(status -> {
            Payment payment = paymentRepository.findByIdForUpdate(existing.getId())
                    .orElseThrow(() -> ApiException.notFound("Payment not found"));
            if (!payment.getStatus().isPayable()) {
                throw ApiException.conflict("This purchase has already been paid for. Please check your bookings.",
                        "ALREADY_PAID");
            }
            refreshSlotHold(payment);
            return CreateOrderResponse.builder()
                    .orderId(payment.getRazorpayOrderId())
                    .amount(payment.getAmountPaise())
                    .currency(payment.getCurrency())
                    .keyId(razorpayService.getPublicKeyId())
                    .build();
        });
    }

    /** Extends a live hold, or re-claims a lapsed one (failing with SLOT_FULL if the seat is gone). Caller holds the row lock. */
    private void refreshSlotHold(Payment payment) {
        if (payment.getDraftScheduledDate() == null || payment.getDraftScheduledTime() == null) {
            return;
        }
        if (payment.getSlotHoldState() == SlotHoldState.HELD) {
            payment.setSlotHoldExpiresAt(LocalDateTime.now().plusMinutes(slotHoldMinutes));
            paymentRepository.save(payment);
        } else if (payment.getSlotHoldState() == SlotHoldState.RELEASED) {
            slotService.reserve(payment.getDraftScheduledDate(), payment.getDraftScheduledTime());
            payment.setSlotHoldState(SlotHoldState.HELD);
            payment.setSlotHoldExpiresAt(LocalDateTime.now().plusMinutes(slotHoldMinutes));
            paymentRepository.save(payment);
        }
    }

    // ===================================================================
    // Step 2: verify
    // ===================================================================

    /**
     * Verifies Razorpay's signature and, if it checks out, marks the
     * matching Payment CAPTURED. Together with the webhook ({@link #applyCapture}) this is the only place a
     * Payment is allowed to become CAPTURED — a booking can only be created against a Payment in this state
     * (see BookingService).
     * <p>
     * Safe to call any number of times, concurrently: the payment row is locked, so the first call captures and
     * writes the ledger row, and every other call just reports the existing outcome.
     */
    @Transactional
    public VerifyPaymentResponse verify(Long customerId, VerifyPaymentRequest request) {
        boolean signatureOk = razorpayService.verifySignature(request);

        // Locked read: without this, N concurrent client retries of verify() for the same order can all read a
        // not-yet-CAPTURED payment and all write a PAYMENT_CAPTURED transaction row.
        Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(request.razorpay_order_id()).orElse(null);
        if (payment == null || !payment.getCustomer().getId().equals(customerId)) {
            // Signature might still check out, but we have no matching order for this customer to attach it to —
            // never mark a stray payment captured based on client-supplied ids alone. Answer with a 404 (not 200)
            // so a caller probing someone else's order id gets an error, and learns nothing about whether it exists.
            throw ApiException.notFound(
                    "We couldn't match this payment to your order. Please contact support before assuming it succeeded.");
        }

        if (!signatureOk) {
            // A bad signature is evidence of a bad request, NOT of a failed payment. The old code flipped the
            // payment to FAILED here, so one garbled or forged call could poison an order the customer then
            // genuinely paid. Leave the state alone; Razorpay's webhook is the authority on failures.
            log.warn("Rejected payment verification with an invalid signature for order {}", payment.getRazorpayOrderId());
            return new VerifyPaymentResponse(false,
                    "Payment could not be verified. Please contact support before assuming it succeeded.");
        }

        if (payment.getStatus().isCaptured()) {
            // Retry / second tab / webhook got here first: report the existing outcome, write nothing.
            boolean samePayment = request.razorpay_payment_id().equals(payment.getRazorpayPaymentId());
            return samePayment
                    ? new VerifyPaymentResponse(true, "Payment verified successfully.")
                    : new VerifyPaymentResponse(false,
                        "This order was already paid with a different payment. Please contact support.");
        }

        applyCapture(payment, request.razorpay_payment_id(), "verify");
        return new VerifyPaymentResponse(true, "Payment verified successfully.");
    }

    /**
     * Records that the gateway has captured this payment. The caller must hold the payment row lock and must
     * already have established that the capture is genuine (signature or webhook signature). Gateway truth wins:
     * even from FAILED or CANCELLED (a late capture after a failed attempt, or on an order we had retired) the
     * money is real, so it is recorded rather than refused — an out-of-the-ordinary origin is only logged.
     */
    @Transactional
    public void applyCapture(Payment payment, String razorpayPaymentId, String source) {
        if (payment.getStatus().isCaptured()) {
            return;
        }
        if (!payment.getStatus().canTransitionTo(PaymentStatus.CAPTURED)) {
            log.warn("Payment {} captured by the gateway while {} - recording the capture anyway", payment.getPaymentNumber(),
                    payment.getStatus());
        }
        payment.setRazorpayPaymentId(razorpayPaymentId);
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        paymentTransactionRepository.save(PaymentTransaction.builder()
                .payment(payment)
                .type(PaymentTransactionType.PAYMENT_CAPTURED)
                .amountPaise(payment.getAmountPaise())
                .status(PaymentStatus.CAPTURED.name())
                .providerTransactionId(razorpayPaymentId)
                .metadata("source=" + source)
                .build());
    }

    // ===================================================================
    // Step 3: spend the captured payment (booking / quotation)
    // ===================================================================

    /**
     * Looks up and row-locks a CAPTURED payment for this customer — called by BookingService when linking a
     * new booking to what was actually paid. The caller must check {@link Payment#getBooking()}: a payment
     * that already funds a booking means "this is a retry", which is answered with that booking, not an error.
     */
    @Transactional
    public Payment consumeCapturedPayment(Long customerId, String razorpayOrderId) {
        Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(razorpayOrderId)
                .orElseThrow(() -> ApiException.badRequest("We couldn't find a payment for that order. Please contact support before retrying."));
        if (!payment.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This payment doesn't belong to your account.");
        }
        if (payment.getQuotationId() != null) {
            throw ApiException.badRequest("This payment is for a quotation, not a booking.");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw ApiException.badRequest("This payment hasn't been verified as captured yet. Please contact support before retrying.");
        }
        return payment;
    }

    /** Called once the Booking row exists, to complete the Payment ↔ Booking link. */
    @Transactional
    public void linkToBooking(Payment payment, com.oorja.backend.entity.Booking booking) {
        payment.setBooking(booking);
        paymentRepository.save(payment);
    }

    /**
     * Hands the seat reserved at checkout over to the booking being created for this payment.
     *
     * @return true if the booking now owns a counted seat (so the caller must NOT claim another); false if the
     *         payment held no seat (order predates holds, hold lapsed before payment, off-grid time) and the
     *         caller must claim one itself.
     */
    @Transactional
    public boolean takeOverSlotHold(Payment payment, LocalDate date, LocalTime time) {
        if (payment.getSlotHoldState() != SlotHoldState.HELD) {
            return false;
        }
        LocalDate heldDate = payment.getDraftScheduledDate();
        LocalTime heldTime = payment.getDraftScheduledTime();
        if (!date.equals(heldDate) || !time.equals(heldTime)) {
            // The customer picked a different slot than the one held at checkout. Claim the new seat first (this
            // throws SLOT_FULL and leaves the original hold untouched if it's gone), then give the old one back.
            slotService.reserve(date, time);
            slotService.release(heldDate, heldTime);
        }
        payment.setSlotHoldState(SlotHoldState.CONSUMED);
        payment.setSlotHoldExpiresAt(null);
        paymentRepository.save(payment);
        return true;
    }

    /**
     * Gives back the seat of an UNPAID order whose hold has lapsed. Runs under the payment's row lock and
     * re-checks everything, so it can never release a seat a concurrent capture/booking has just taken over.
     *
     * @return true if a seat was released
     */
    @Transactional
    public boolean releaseHoldIfExpired(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null
                || payment.getSlotHoldState() != SlotHoldState.HELD
                || payment.getStatus().isCaptured()
                || payment.getSlotHoldExpiresAt() == null
                || payment.getSlotHoldExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        slotService.release(payment.getDraftScheduledDate(), payment.getDraftScheduledTime());
        payment.setSlotHoldState(SlotHoldState.RELEASED);
        payment.setSlotHoldExpiresAt(null);
        paymentRepository.save(payment);
        log.info("Released lapsed slot hold for unpaid payment {} ({} {})", payment.getPaymentNumber(),
                payment.getDraftScheduledDate(), payment.getDraftScheduledTime());
        return true;
    }

    /**
     * Looks up a CAPTURED payment for this quotation, verifying it belongs
     * to this customer and this quotation specifically — called by
     * QuotationService when recording payment, instead of trusting the
     * client-supplied paymentRef string on its own.
     */
    @Transactional
    public Payment consumeCapturedQuotationPayment(Long customerId, Long quotationId, String razorpayOrderId) {
        Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(razorpayOrderId)
                .orElseThrow(() -> ApiException.badRequest("We couldn't find a payment for that order. Please contact support before retrying."));
        if (!payment.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This payment doesn't belong to your account.");
        }
        if (payment.getQuotationId() == null || !payment.getQuotationId().equals(quotationId)) {
            throw ApiException.badRequest("This payment isn't for this quotation.");
        }
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw ApiException.badRequest("This payment hasn't been verified as captured yet. Please contact support before retrying.");
        }
        return payment;
    }

    // -------------------------------------------------------------------
    // Admin console -> Payments. Read-only reporting over the ledger built
    // above — nothing here mutates a Payment; refunds are handled entirely
    // by RefundService/AdminRefundController.
    // -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<AdminPaymentResponse> findAllForAdmin(org.springframework.data.domain.Pageable pageable) {
        return paymentRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(p -> AdminPaymentResponse.from(p, java.util.List.of()));
    }

    @Transactional(readOnly = true)
    public AdminPaymentResponse findByIdForAdmin(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Payment not found: " + id));
        var transactions = paymentTransactionRepository.findByPaymentIdOrderByCreatedAtAsc(id).stream()
                .map(PaymentTransactionResponse::from)
                .toList();
        return AdminPaymentResponse.from(payment, transactions);
    }

    /** Most recent payment attempt for a booking — what the admin sees from a booking's detail view. */
    @Transactional(readOnly = true)
    public AdminPaymentResponse findByBookingIdForAdmin(Long bookingId) {
        Payment payment = paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("No payment found for this booking."));
        return findByIdForAdmin(payment.getId());
    }
}
