package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.dto.response.RefundResponse;
import com.oorja.backend.entity.*;
import com.oorja.backend.entity.enums.*;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.*;
import com.oorja.backend.service.RefundCalculationService.Breakdown;
import com.oorja.backend.service.RefundEligibilityService.Decision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The refund state machine — stages 1, 4 and 7 of the pipeline (request,
 * approval, completion), with the gateway call itself delegated to
 * {@link RefundDispatchService} so no HTTP round-trip ever happens inside a
 * database transaction.
 *
 * <pre>
 *  request ─▶ eligibility ─▶ calculation ─▶ approval ─┬▶ dispatch ─▶ gateway
 *                                                     │                │
 *                                                     └▶ rejected      ▼
 *                                                                  webhook
 *                                                                      │
 *                                                        completed ◀───┘
 *                                                            │
 *                                                            ▼
 *                                                   ledger reconciliation
 * </pre>
 *
 * Invariants worth protecting when editing this class:
 * <ul>
 *   <li>Every status change goes through {@link #transition} — it validates
 *       the move against {@link RefundStatus#canTransitionTo} and writes the
 *       audit row. Never set {@code status} directly.</li>
 *   <li>Every read that precedes a write uses {@code findByIdForUpdate}.</li>
 *   <li>The gateway is never called from inside a {@code @Transactional}
 *       method here; approval publishes an event and the dispatcher picks it
 *       up after commit.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refundRepository;
    private final RefundEventRepository refundEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final BookingRepository bookingRepository;
    private final RefundEligibilityService eligibilityService;
    private final RefundCalculationService calculationService;
    private final CodeGeneratorService codeGenerator;
    private final RefundPolicyProperties policy;
    private final ApplicationEventPublisher events;
    private final InvoiceService invoiceService;

    /** Published after commit so the gateway call happens outside the transaction. */
    public record RefundApprovedEvent(Long refundId) {}

    // =====================================================================
    // Stage 1 — request
    // =====================================================================

    /**
     * Quote a refund without creating one. Same eligibility and calculation
     * code the real request uses, so the "you'll get ₹X back" the customer
     * sees before cancelling is the number they actually get.
     */
    @Transactional(readOnly = true)
    public Quote preview(Booking booking, RefundReasonCode reasonCode) {
        Decision decision = eligibilityService.evaluate(booking, reasonCode, LocalDateTime.now());
        long gross = booking.getAmountPaise() == null ? 0L : booking.getAmountPaise();
        Payment payment = resolvePayment(booking);
        Breakdown breakdown = calculationService.calculate(gross, decision, payment);
        return new Quote(decision, breakdown);
    }

    public record Quote(Decision decision, Breakdown breakdown) {}

    /**
     * Creates the refund for a booking that has just been cancelled.
     * Called by {@link BookingService} inside the cancellation transaction —
     * if the cancellation rolls back, so does the refund.
     *
     * @return the new refund, or null when the booking wasn't refundable
     *         (unpaid, or a refund is already in flight)
     */
    @Transactional
    public Refund requestForCancelledBooking(Booking booking, RefundReasonCode reasonCode, String reason,
                                             SubjectType actorType, Long actorId, String actorLabel) {

        Decision decision = eligibilityService.evaluate(booking, reasonCode, LocalDateTime.now());

        if (decision.tier() == RefundEligibilityService.Tier.NOT_PAID
                || decision.tier() == RefundEligibilityService.Tier.ALREADY_REFUNDED) {
            return null; // nothing owed, or already being handled
        }

        long gross = booking.getAmountPaise() == null ? 0L : booking.getAmountPaise();
        Payment payment = resolvePayment(booking);
        Breakdown breakdown = calculationService.calculate(gross, decision, payment);

        Refund refund = refundRepository.save(Refund.builder()
                .refundNumber(codeGenerator.nextRefundNumber())
                .booking(booking)
                .customer(booking.getCustomer())
                .payment(payment)
                .reasonCode(reasonCode)
                .reason(reason)
                .requestedByType(actorType)
                .requestedById(actorId)
                .policyTier(decision.tier().name())
                .eligibilityNote(decision.message())
                .grossAmountPaise(gross)
                .refundPercentBps(breakdown.percentBps())
                .cancellationFeePaise(breakdown.cancellationFeePaise())
                .gatewayFeePaise(breakdown.gatewayFeePaise())
                .amountPaise(breakdown.netPaise())
                .status(RefundStatus.REQUESTED)
                .razorpayPaymentId(booking.getPaymentRef())
                .idempotencyKey("rfd-" + booking.getId() + "-" + UUID.randomUUID())
                .speed("normal")
                .build());

        audit(refund, RefundEventType.REQUESTED, null, RefundStatus.REQUESTED, actorType.name(), actorLabel,
                "Refund requested for booking " + booking.getToken() + ".", breakdown.netPaise(), null);
        audit(refund, RefundEventType.ELIGIBILITY_EVALUATED, null, null, "SYSTEM", "policy engine",
                decision.message(), null, null);
        audit(refund, RefundEventType.AMOUNT_CALCULATED, null, null, "SYSTEM", "policy engine",
                describe(breakdown), breakdown.netPaise(), null);

        if (breakdown.clamped()) {
            audit(refund, RefundEventType.LEDGER_MISMATCH, null, null, "SYSTEM", "policy engine",
                    "Refund was capped at the remaining refundable balance on the original payment.",
                    breakdown.refundableBalancePaise(), null);
        }

        // Zero-value refunds (fully consumed by policy) are recorded, not paid.
        if (breakdown.netPaise() <= 0) {
            transition(refund, RefundStatus.REJECTED, RefundEventType.REJECTED, "SYSTEM", "policy engine",
                    "Under our cancellation policy no amount is refundable for this booking.");
            refund.setRejectedAt(LocalDateTime.now());
            refund.setAdminNote(decision.message());
            return refundRepository.save(refund);
        }

        if (decision.autoApprovable() && shouldAutoApprove(decision, breakdown)) {
            approveInternal(refund, true, "SYSTEM", "auto-approval policy");
        }

        return refundRepository.save(refund);
    }

    private boolean shouldAutoApprove(Decision decision, Breakdown breakdown) {
        if (decision.tier() == RefundEligibilityService.Tier.PROVIDER_FAULT) {
            return policy.isAutoApproveProviderFault();
        }
        return decision.isFullRefund() && breakdown.netPaise() <= policy.getAutoApproveMaxPaise();
    }

    /**
     * Admin-raised refund against a booking the customer never cancelled —
     * a duplicate charge, a job done badly, or plain goodwill. The policy
     * engine still runs (so the audit trail records what the rules would
     * have said), but an admin reason code carries its own entitlement and
     * the admin may name the amount outright.
     */
    @Transactional
    public RefundResponse createByAdmin(com.oorja.backend.dto.request.AdminRefundRequest request, String adminLabel) {
        Booking booking = bookingRepository.findById(request.bookingId())
                .orElseThrow(() -> ApiException.notFound("Booking not found: " + request.bookingId()));

        if (refundRepository.findActiveByBookingId(booking.getId()).isPresent()) {
            throw ApiException.conflict("There's already a refund in progress for this booking.");
        }
        if (booking.getPaymentRef() == null || booking.getAmountPaise() == null || booking.getAmountPaise() <= 0) {
            throw ApiException.badRequest("This booking has no captured payment to refund.");
        }

        Refund refund = requestForCancelledBooking(booking, request.reasonCode(),
                request.reason() == null || request.reason().isBlank()
                        ? "Raised by " + adminLabel : request.reason(),
                SubjectType.ADMIN, null, adminLabel);

        if (refund == null) {
            throw ApiException.badRequest("This booking isn't refundable.");
        }

        Long override = request.amountInr() == null ? null : Math.round(request.amountInr() * 100);
        if (override != null && refund.getStatus() == RefundStatus.REQUESTED) {
            return approve(refund.getId(), override, "Amount set by " + adminLabel, adminLabel);
        }
        return respond(refund);
    }

    @Transactional(readOnly = true)
    public RefundResponse findOne(Long id) {
        return respond(refundRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Refund not found: " + id)));
    }

    // =====================================================================
    // Stage 4 — approval
    // =====================================================================

    /** Marks the refund as being looked at, so two admins don't both act on it. */
    @Transactional
    public RefundResponse startReview(Long id, String adminLabel) {
        Refund refund = lock(id);
        transition(refund, RefundStatus.UNDER_REVIEW, RefundEventType.REVIEW_STARTED, "ADMIN", adminLabel,
                adminLabel + " is reviewing this refund.");
        return respond(refundRepository.save(refund));
    }

    /**
     * Approves a refund for payout, optionally overriding the amount (support
     * goodwill, partial settlements). The override is still bounded by the
     * gross and by what's left refundable on the payment.
     */
    @Transactional
    public RefundResponse approve(Long id, Long overrideAmountPaise, String note, String adminLabel) {
        Refund refund = lock(id);

        if (overrideAmountPaise != null && !overrideAmountPaise.equals(refund.getAmountPaise())) {
            long max = refund.getGrossAmountPaise();
            if (refund.getPayment() != null) {
                long committed = refundRepository.sumCommittedForPayment(refund.getPayment().getId());
                max = Math.min(max, refund.getPayment().getAmountPaise() - committed);
            }
            if (overrideAmountPaise < 0 || overrideAmountPaise > max) {
                throw ApiException.badRequest("Refund amount must be between ₹0 and ₹" + (max / 100.0) + ".");
            }
            long previous = refund.getAmountPaise();
            refund.setAmountPaise(overrideAmountPaise);
            refund.setCancellationFeePaise(Math.max(0L, refund.getGrossAmountPaise() - overrideAmountPaise
                    - refund.getGatewayFeePaise()));
            audit(refund, RefundEventType.AMOUNT_CALCULATED, null, null, "ADMIN", adminLabel,
                    String.format("Amount overridden from ₹%.2f to ₹%.2f.%s",
                            previous / 100.0, overrideAmountPaise / 100.0,
                            note == null || note.isBlank() ? "" : " " + note),
                    overrideAmountPaise, null);
        }

        if (note != null && !note.isBlank()) {
            refund.setAdminNote(note);
        }
        approveInternal(refund, false, "ADMIN", adminLabel);
        return respond(refundRepository.save(refund));
    }

    private void approveInternal(Refund refund, boolean auto, String actorType, String actorLabel) {
        transition(refund,
                RefundStatus.APPROVED,
                auto ? RefundEventType.AUTO_APPROVED : RefundEventType.APPROVED,
                actorType, actorLabel,
                auto ? "Automatically approved — full refund within the self-service limit."
                     : "Approved for payout by " + actorLabel + ".");
        refund.setAutoApproved(auto);
        refund.setApprovedBy(actorLabel);
        refund.setApprovedAt(LocalDateTime.now());
        refund.setNextAttemptAt(LocalDateTime.now());
        refund.setLastError(null);

        // Fire only after the surrounding transaction commits: the gateway
        // must never see a refund the database later rolls back.
        events.publishEvent(new RefundApprovedEvent(refund.getId()));
    }

    @Transactional
    public RefundResponse reject(Long id, String reason, String adminLabel) {
        Refund refund = lock(id);
        transition(refund, RefundStatus.REJECTED, RefundEventType.REJECTED, "ADMIN", adminLabel,
                reason == null || reason.isBlank()
                        ? "This refund request was not approved."
                        : reason);
        refund.setRejectedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            refund.setAdminNote(reason);
        }
        return respond(refundRepository.save(refund));
    }

    // =====================================================================
    // Rebooking — the "keep the customer, not the cancellation" path
    // =====================================================================

    /**
     * Withdraws a refund the customer no longer wants because they've chosen
     * to rebook. The booking keeps its original payment, so the customer
     * retains 100% of what they paid — which is exactly why the app offers
     * this before it offers a refund.
     *
     * Only legal while the money hasn't moved: once a refund is PROCESSING
     * there's a real gateway transaction to unwind and rebooking has to be a
     * fresh, separately-paid booking instead.
     */
    @Transactional
    public Refund withdrawForRebooking(Long refundId, Long customerId, String note) {
        Refund refund = lock(refundId);
        if (!refund.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You can't change someone else's refund.");
        }
        if (refund.getStatus() != RefundStatus.REQUESTED && refund.getStatus() != RefundStatus.UNDER_REVIEW) {
            throw ApiException.badRequest(
                    "This refund is already being paid out, so it can't be turned back into a booking. "
                            + "Book the new visit and we'll complete the refund as normal.");
        }
        transition(refund, RefundStatus.CANCELLED, RefundEventType.WITHDRAWN_FOR_REBOOKING, "CUSTOMER",
                "customer", note == null || note.isBlank()
                        ? "Withdrawn — the customer rebooked the service instead of taking a refund."
                        : note);
        return refundRepository.save(refund);
    }

    // =====================================================================
    // Stages 6 & 7 — provider webhook and completion
    // =====================================================================

    /** Called by the webhook handler for {@code refund.processed}. Idempotent. */
    @Transactional
    public void onGatewayRefundProcessed(String razorpayRefundId, Long amountPaise, String providerStatus) {
        Refund refund = refundRepository.findByRazorpayRefundId(razorpayRefundId).orElse(null);
        if (refund == null) {
            log.warn("Refund webhook for unknown gateway refund {} — nothing to reconcile against.", razorpayRefundId);
            return;
        }
        refund = lock(refund.getId());

        audit(refund, RefundEventType.WEBHOOK_RECEIVED, refund.getStatus(), null, "PROVIDER", "Razorpay webhook",
                "Provider reported this refund as " + providerStatus + ".", amountPaise, razorpayRefundId);

        if (refund.getStatus() == RefundStatus.COMPLETED) {
            return; // duplicate delivery
        }
        if (amountPaise != null && !amountPaise.equals(refund.getAmountPaise())) {
            // The provider settled a different figure than we recorded. Do not
            // silently agree: flag it for the ledger job and for ops.
            audit(refund, RefundEventType.LEDGER_MISMATCH, refund.getStatus(), null, "SYSTEM", "webhook",
                    String.format("Provider settled ₹%.2f but our record says ₹%.2f.",
                            amountPaise / 100.0, refund.getAmountPaise() / 100.0),
                    amountPaise, razorpayRefundId);
        }

        markCompleted(refund, providerStatus, "Razorpay webhook");
        refundRepository.save(refund);
    }

    /** Called by the webhook handler for {@code refund.failed}. */
    @Transactional
    public void onGatewayRefundFailed(String razorpayRefundId, String providerStatus, String errorDescription) {
        Refund refund = refundRepository.findByRazorpayRefundId(razorpayRefundId).orElse(null);
        if (refund == null) return;
        refund = lock(refund.getId());

        if (refund.getStatus().isTerminal()) return;

        transition(refund, RefundStatus.FAILED, RefundEventType.GATEWAY_FAILED, "PROVIDER", "Razorpay webhook",
                "The bank couldn't complete this refund. We'll retry it automatically.");
        refund.setGatewayStatus(providerStatus);
        refund.setLastError(errorDescription);
        refund.setNextAttemptAt(LocalDateTime.now().plusMinutes(policy.getRetryBackoffMinutes()));
        recordLedger(refund, PaymentTransactionType.REFUND_FAILED, providerStatus, "source=webhook");
        refundRepository.save(refund);
    }

    /** Shared completion path — used by both the webhook and the reconciliation poll. */
    void markCompleted(Refund refund, String providerStatus, String actorLabel) {
        LocalDateTime now = LocalDateTime.now();
        transition(refund, RefundStatus.COMPLETED, RefundEventType.COMPLETED, "PROVIDER", actorLabel,
                "Refund completed. Depending on your bank it can take a few working days to appear on your statement.");
        refund.setCompletedAt(now);
        refund.setGatewayStatus(providerStatus);
        refund.setLastError(null);
        recordLedger(refund, PaymentTransactionType.REFUND_COMPLETED, providerStatus, "source=" + actorLabel);

        // A completed refund is a credit note, not an edit to the original
        // invoice. Best-effort: the money has already moved, so a document
        // failure must not roll that back — the invoice sweep will catch it.
        try {
            Invoice creditNote = invoiceService.issueCreditNote(refund);
            if (creditNote != null) {
                audit(refund, RefundEventType.NOTE, null, null,
                        "SYSTEM", "billing",
                        "Credit note " + creditNote.getInvoiceNumber() + " issued. You can download it from Invoices.",
                        refund.getAmountPaise(), creditNote.getInvoiceNumber());
            }
        } catch (Exception e) {
            log.error("Could not issue a credit note for refund {}: {}", refund.getRefundNumber(), e.getMessage());
        }
    }

    // =====================================================================
    // Reads
    // =====================================================================

    /** Paginated for the admin console list — an earlier unpaginated version loaded the whole table into memory. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RefundResponse> findAll(org.springframework.data.domain.Pageable pageable) {
        return refundRepository.findAllByOrderByRequestedAtDesc(pageable).map(this::respond);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> findQueue() {
        return refundRepository.findByStatusInOrderByRequestedAtDesc(
                List.of(RefundStatus.REQUESTED, RefundStatus.UNDER_REVIEW, RefundStatus.FAILED))
                .stream().map(this::respond).toList();
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> findByCustomer(Long customerId) {
        return refundRepository.findByCustomerIdOrderByRequestedAtDesc(customerId).stream()
                .map(this::respond).toList();
    }

    @Transactional(readOnly = true)
    public RefundResponse findOneForCustomer(Long id, Long customerId) {
        Refund refund = refundRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Refund not found."));
        if (!refund.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You can't view someone else's refund.");
        }
        return respond(refund);
    }

    // =====================================================================
    // Internals
    // =====================================================================

    Refund lock(Long id) {
        return refundRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Refund not found: " + id));
    }

    /**
     * The only legal way to change a refund's status. Rejects illegal moves
     * loudly rather than letting a bug quietly put a refund in a state the
     * rest of the pipeline can't handle.
     */
    void transition(Refund refund, RefundStatus next, RefundEventType eventType,
                    String actorType, String actorLabel, String message) {
        RefundStatus current = refund.getStatus();
        if (current == next) {
            return;
        }
        if (!current.canTransitionTo(next)) {
            throw ApiException.conflict(String.format(
                    "A refund that is %s can't be moved to %s.", current.label().toLowerCase(), next.label().toLowerCase()));
        }
        refund.setStatus(next);
        audit(refund, eventType, current, next, actorType, actorLabel, message, refund.getAmountPaise(),
                refund.getRazorpayRefundId());
    }

    void audit(Refund refund, RefundEventType type, RefundStatus from, RefundStatus to,
               String actorType, String actorLabel, String message, Long amountPaise, String providerRef) {
        refundEventRepository.save(RefundEvent.builder()
                .refund(refund).type(type).fromStatus(from).toStatus(to)
                .actorType(actorType == null ? "SYSTEM" : actorType)
                .actorLabel(actorLabel)
                .message(message)
                .amountPaise(amountPaise)
                .providerRef(providerRef)
                .build());
    }

    /**
     * Stage 8's raw material: an append-only line in the payment ledger for
     * every movement of money, so a payment's captured total minus its refund
     * lines always equals what we actually still hold.
     */
    void recordLedger(Refund refund, PaymentTransactionType type, String status, String metadata) {
        if (refund.getPayment() == null) {
            return; // legacy refund with no ledger row to attach to
        }
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .payment(refund.getPayment())
                .type(type)
                .amountPaise(refund.getAmountPaise())
                .status(status == null ? refund.getStatus().name() : status)
                .providerTransactionId(refund.getRazorpayRefundId())
                .metadata(metadata + ";refund=" + refund.getRefundNumber())
                .build());
    }

    /** Finds the ledger row that funded a booking, so a refund debits the right payment. */
    private Payment resolvePayment(Booking booking) {
        List<Payment> byBooking = paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId());
        for (Payment p : byBooking) {
            if (p.getStatus() == PaymentStatus.CAPTURED) return p;
        }
        return byBooking.isEmpty() ? null : byBooking.get(0);
    }

    private String describe(Breakdown b) {
        StringBuilder sb = new StringBuilder(String.format(
                "Paid ₹%.2f · policy %d%% = ₹%.2f", b.grossPaise() / 100.0, b.percentBps() / 100, b.policyAmountPaise() / 100.0));
        if (b.cancellationFeePaise() > 0) sb.append(String.format(" · cancellation fee −₹%.2f", b.cancellationFeePaise() / 100.0));
        if (b.gatewayFeePaise() > 0) sb.append(String.format(" · gateway fee −₹%.2f", b.gatewayFeePaise() / 100.0));
        sb.append(String.format(" · refund ₹%.2f", b.netPaise() / 100.0));
        return sb.toString();
    }

    RefundResponse respond(Refund refund) {
        return RefundResponse.from(refund,
                refundEventRepository.findByRefundIdOrderByCreatedAtAsc(refund.getId()),
                policy.getSettlementDays());
    }

    /** Used by BookingService to decide whether a cancelled booking can be revived. */
    @Transactional(readOnly = true)
    public boolean hasActiveRefund(Long bookingId) {
        return refundRepository.findActiveByBookingId(bookingId).isPresent();
    }

    BookingRepository bookings() {
        return bookingRepository;
    }
}
