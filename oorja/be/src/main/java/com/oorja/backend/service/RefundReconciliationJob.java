package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.dto.response.RefundReconciliationResponse;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.PaymentTransaction;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.PaymentStatus;
import com.oorja.backend.entity.enums.PaymentTransactionType;
import com.oorja.backend.entity.enums.RefundEventType;
import com.oorja.backend.entity.enums.RefundStatus;
import com.oorja.backend.repository.PaymentRepository;
import com.oorja.backend.repository.PaymentTransactionRepository;
import com.oorja.backend.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 8: <b>ledger reconciliation</b> — the part that turns this from a
 * refund feature into a refund system.
 *
 * Three jobs, each closing a different way the happy path can fail:
 *
 * <ol>
 *   <li><b>Dispatch sweep.</b> Anything APPROVED or retryable-FAILED that's
 *       due gets pushed to the gateway. Covers process crashes between
 *       approval and submission, and transient provider outages.</li>
 *   <li><b>Provider poll.</b> Anything PROCESSING with no webhook for longer
 *       than the stale window is read back directly from the provider.
 *       Webhooks get lost, misconfigured and silently disabled; a customer's
 *       money must not depend on one arriving.</li>
 *   <li><b>Ledger audit.</b> For every captured payment, compare what the
 *       ledger says we refunded against what the refund rows say. Any
 *       disagreement is written into the audit trail and surfaced in the
 *       admin console rather than discovered at year-end.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundReconciliationJob {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundDispatchService dispatchService;
    private final RefundService refundService;
    private final RazorpayService razorpayService;
    private final RefundPolicyProperties policy;

    /** Push approved/retryable refunds to the gateway. */
    @Scheduled(fixedDelayString = "${refund.jobs.dispatch-interval-ms:60000}")
    public void sweepDispatchQueue() {
        List<Refund> due = refundRepository.findDueForDispatch(LocalDateTime.now(), policy.getMaxGatewayAttempts());
        if (due.isEmpty()) return;

        log.info("Refund dispatcher: {} refund(s) due for submission.", due.size());
        for (Refund refund : due) {
            try {
                dispatchService.dispatch(refund.getId());
            } catch (Exception e) {
                log.error("Dispatch of refund {} failed: {}", refund.getRefundNumber(), e.getMessage());
            }
        }
    }

    /** Ask the provider directly about refunds a webhook never confirmed. */
    @Scheduled(fixedDelayString = "${refund.jobs.poll-interval-ms:300000}")
    public void pollStaleProcessing() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(policy.getReconcileStaleAfterMinutes());
        List<Refund> stale = refundRepository.findStaleProcessing(staleBefore);
        if (stale.isEmpty()) return;

        log.info("Refund reconciliation: polling {} refund(s) with no provider confirmation.", stale.size());
        for (Refund refund : stale) {
            try {
                pollOne(refund.getId());
            } catch (Exception e) {
                log.warn("Could not poll refund {}: {}", refund.getRefundNumber(), e.getMessage());
            }
        }
    }

    @Transactional
    public void pollOne(Long refundId) {
        Refund refund = refundService.lock(refundId);
        if (refund.getStatus() != RefundStatus.PROCESSING || refund.getRazorpayRefundId() == null) {
            return;
        }

        RazorpayService.RefundResult result =
                razorpayService.fetchRefund(refund.getRazorpayPaymentId(), refund.getRazorpayRefundId());

        refund.setReconciledAt(LocalDateTime.now());
        refund.setGatewayStatus(result.status());

        if ("processed".equalsIgnoreCase(result.status())) {
            refundService.markCompleted(refund, result.status(), "reconciliation job");
        } else if ("failed".equalsIgnoreCase(result.status())) {
            refundService.transition(refund, RefundStatus.FAILED, RefundEventType.GATEWAY_FAILED,
                    "SYSTEM", "reconciliation job",
                    "The bank rejected this refund. We're re-issuing it.");
            refund.setNextAttemptAt(LocalDateTime.now());
            refundService.recordLedger(refund, PaymentTransactionType.REFUND_FAILED, result.status(), "source=reconciliation");
        } else {
            refundService.audit(refund, RefundEventType.RECONCILED, RefundStatus.PROCESSING, RefundStatus.PROCESSING,
                    "SYSTEM", "reconciliation job",
                    "Still with the bank (provider status: " + result.status() + ").",
                    refund.getAmountPaise(), refund.getRazorpayRefundId());
        }
        refundRepository.save(refund);
    }

    /**
     * Nightly books-balancing pass. Read-only with respect to money: it
     * never moves a rupee, it only tells you when two sources of truth have
     * drifted apart.
     */
    @Scheduled(cron = "${refund.jobs.audit-cron:0 30 2 * * *}")
    @Transactional
    public void auditLedger() {
        RefundReconciliationResponse report = buildReport(true);
        if (report.getMismatchCount() > 0) {
            log.error("Refund ledger audit found {} payment(s) whose refund ledger disagrees with their refund records.",
                    report.getMismatchCount());
        } else {
            log.info("Refund ledger audit clean: {} payments checked, ₹{} refunded in total.",
                    report.getPaymentsChecked(), String.format("%.2f", report.getTotalRefundedInr()));
        }
    }

    /**
     * Compares, per captured payment: the sum of COMPLETED refund rows
     * against the sum of REFUND_COMPLETED ledger lines. These are written by
     * different code paths at different times, so agreement between them is
     * a genuine check rather than a tautology.
     *
     * @param writeAuditEvents whether to record findings on the refunds
     *                         themselves (the nightly job does; the admin
     *                         console's on-demand view does not)
     */
    @Transactional
    public RefundReconciliationResponse buildReport(boolean writeAuditEvents) {
        List<Payment> payments = paymentRepository.findAllByOrderByCreatedAtDesc();
        List<RefundReconciliationResponse.Mismatch> mismatches = new ArrayList<>();

        // Both sides of the comparison are loaded once and grouped in memory:
        // the audit runs over every payment ever taken, so a per-payment query
        // would turn a nightly job into an outage.
        Map<Long, Long> refundedByPayment = new HashMap<>();
        Map<Long, Refund> anyRefundByPayment = new HashMap<>();
        for (Refund r : refundRepository.findAll()) {
            if (r.getPayment() == null) continue;
            Long pid = r.getPayment().getId();
            anyRefundByPayment.putIfAbsent(pid, r);
            if (r.getStatus() == RefundStatus.COMPLETED) {
                refundedByPayment.merge(pid, r.getAmountPaise(), Long::sum);
            }
        }

        Map<Long, Long> ledgerByPayment = new HashMap<>();
        for (PaymentTransaction t : paymentTransactionRepository.findAll()) {
            if (t.getType() != PaymentTransactionType.REFUND_COMPLETED) continue;
            ledgerByPayment.merge(t.getPayment().getId(), t.getAmountPaise(), Long::sum);
        }

        long totalCaptured = 0L;
        long totalRefunded = 0L;
        int checked = 0;

        for (Payment payment : payments) {
            if (payment.getStatus() != PaymentStatus.CAPTURED) continue;
            checked++;
            totalCaptured += payment.getAmountPaise();

            long ledgerRefunded = ledgerByPayment.getOrDefault(payment.getId(), 0L);
            long refundsCompleted = refundedByPayment.getOrDefault(payment.getId(), 0L);
            totalRefunded += refundsCompleted;

            boolean overRefunded = refundsCompleted > payment.getAmountPaise();
            if (ledgerRefunded == refundsCompleted && !overRefunded) continue;

            mismatches.add(RefundReconciliationResponse.Mismatch.builder()
                    .paymentNumber(payment.getPaymentNumber())
                    .razorpayPaymentId(payment.getRazorpayPaymentId())
                    .capturedInr(payment.getAmountPaise() / 100.0)
                    .ledgerRefundedInr(ledgerRefunded / 100.0)
                    .refundRecordsInr(refundsCompleted / 100.0)
                    .detail(overRefunded
                            ? "Refunded more than was captured — investigate immediately."
                            : "Refund ledger lines and refund records disagree.")
                    .build());

            if (writeAuditEvents) {
                Refund marker = anyRefundByPayment.get(payment.getId());
                if (marker != null) {
                    refundService.audit(marker, RefundEventType.LEDGER_MISMATCH,
                            marker.getStatus(), marker.getStatus(), "SYSTEM", "ledger audit",
                            String.format("Ledger says \u20b9%.2f refunded, refund records say \u20b9%.2f.",
                                    ledgerRefunded / 100.0, refundsCompleted / 100.0),
                            null, null);
                }
            }
        }

        return RefundReconciliationResponse.builder()
                .generatedAt(LocalDateTime.now())
                .paymentsChecked(checked)
                .totalCapturedInr(totalCaptured / 100.0)
                .totalRefundedInr(totalRefunded / 100.0)
                .netHeldInr((totalCaptured - totalRefunded) / 100.0)
                .pendingApproval(refundRepository.countByStatus(RefundStatus.REQUESTED)
                        + refundRepository.countByStatus(RefundStatus.UNDER_REVIEW))
                .inFlight(refundRepository.countByStatus(RefundStatus.APPROVED)
                        + refundRepository.countByStatus(RefundStatus.PROCESSING))
                .needingAttention(refundRepository.countByStatus(RefundStatus.FAILED))
                .mismatchCount(mismatches.size())
                .mismatches(mismatches)
                .build();
    }
}
