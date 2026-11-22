package com.oorja.backend.service;

import com.oorja.backend.config.RefundPolicyProperties;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.PaymentTransactionType;
import com.oorja.backend.entity.enums.RefundEventType;
import com.oorja.backend.entity.enums.RefundStatus;
import com.oorja.backend.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * Stage 5: <b>the gateway refund</b>.
 *
 * Deliberately the only class that talks to the payment provider about
 * refunds, and deliberately structured as three separate transactions:
 *
 * <ol>
 *   <li>claim the refund (APPROVED → attempt++), commit;</li>
 *   <li>call the gateway with <i>no</i> transaction open;</li>
 *   <li>record the outcome, commit.</li>
 * </ol>
 *
 * That shape is what makes a crash survivable. If the process dies during
 * step 2, the refund is left APPROVED with its attempt counter incremented
 * and a backoff set; {@link RefundReconciliationJob} retries it, and the
 * idempotency key means the provider returns the original refund instead of
 * creating a second one. The failure mode is "a refund is slow", never
 * "a customer was paid twice".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundDispatchService {

    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final RazorpayService razorpayService;
    private final RefundPolicyProperties policy;

    /**
     * Self-reference through the Spring proxy. The three steps below are
     * separate transactions, and calling them directly as {@code this.claim(..)}
     * would bypass the proxy and silently run them all in one — which is
     * exactly the bug this class exists to avoid. {@code ObjectProvider}
     * resolves lazily, so there's no circular-construction problem.
     */
    private final ObjectProvider<RefundDispatchService> self;

    private RefundDispatchService tx() {
        return self.getObject();
    }

    /**
     * Runs once the approving transaction has committed. Synchronous on
     * purpose: the admin clicking Approve gets the gateway's answer in the
     * same request, and a failure here is already persisted as a retryable
     * FAILED row rather than lost.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(RefundService.RefundApprovedEvent event) {
        try {
            dispatch(event.refundId());
        } catch (Exception e) {
            // Never propagate: the refund is safely APPROVED/FAILED in the
            // database and the reconciliation job will pick it up.
            log.error("Post-approval dispatch of refund {} failed: {}", event.refundId(), e.getMessage());
        }
    }

    /** Submits one approved refund to the provider. Safe to call repeatedly. */
    public void dispatch(Long refundId) {
        Claim claim = tx().claim(refundId);
        if (claim == null) {
            return;
        }

        try {
            RazorpayService.RefundResult result = razorpayService.refund(
                    claim.paymentId(), claim.amountPaise(), claim.idempotencyKey(),
                    claim.refundNumber(), claim.speed());
            tx().recordSubmitted(refundId, result.id(), result.status());

        } catch (RazorpayService.GatewayException e) {
            tx().recordFailure(refundId, e.getMessage(), e.retryable());
        } catch (Exception e) {
            // Unknown failure: assume the request may have reached the
            // provider, so treat it as retryable and let idempotency sort it out.
            tx().recordFailure(refundId, e.getMessage(), true);
        }
    }

    public record Claim(String paymentId, long amountPaise, String idempotencyKey,
                         String refundNumber, String speed) {}

    /**
     * Takes exclusive ownership of the refund for this attempt. Returns null
     * when another worker already has it, when it's out of attempts, or when
     * it isn't due yet — all normal, none of them errors.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(Long refundId) {
        Refund refund = refundService.lock(refundId);

        if (refund.getStatus() != RefundStatus.APPROVED && refund.getStatus() != RefundStatus.FAILED) {
            return null;
        }
        if (refund.getNextAttemptAt() != null && refund.getNextAttemptAt().isAfter(LocalDateTime.now())) {
            return null;
        }
        if (refund.getAttemptCount() >= policy.getMaxGatewayAttempts()) {
            return null;
        }
        if (refund.getAmountPaise() == null || refund.getAmountPaise() <= 0) {
            return null;
        }

        refund.setAttemptCount(refund.getAttemptCount() + 1);
        refund.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes(refund.getAttemptCount())));
        refundRepository.save(refund);

        return new Claim(refund.getRazorpayPaymentId(), refund.getAmountPaise(),
                refund.getIdempotencyKey(), refund.getRefundNumber(), refund.getSpeed());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSubmitted(Long refundId, String gatewayRefundId, String gatewayStatus) {
        Refund refund = refundService.lock(refundId);
        LocalDateTime now = LocalDateTime.now();

        refund.setRazorpayRefundId(gatewayRefundId);
        refund.setGatewayStatus(gatewayStatus);
        refund.setInitiatedAt(refund.getInitiatedAt() == null ? now : refund.getInitiatedAt());
        refund.setExpectedSettlementAt(refund.getInitiatedAt().plusDays(policy.getSettlementDays()));
        refund.setLastError(null);
        refund.setNextAttemptAt(null);

        refundService.transition(refund, RefundStatus.PROCESSING, RefundEventType.GATEWAY_SUBMITTED,
                "SYSTEM", "refund dispatcher",
                String.format("Refund of ₹%.2f sent to your bank via Razorpay. Expected within %d working days.",
                        refund.getAmountPaise() / 100.0, policy.getSettlementDays()));
        refundService.recordLedger(refund, PaymentTransactionType.REFUND_INITIATED, gatewayStatus, "source=dispatcher");

        // Small refunds sometimes come back "processed" synchronously. Don't
        // wait for a webhook that will never add information.
        if ("processed".equalsIgnoreCase(gatewayStatus)) {
            refundService.markCompleted(refund, gatewayStatus, "gateway (immediate)");
        }
        refundRepository.save(refund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long refundId, String error, boolean retryable) {
        Refund refund = refundService.lock(refundId);
        if (refund.getStatus().isTerminal() || refund.getStatus() == RefundStatus.PROCESSING) {
            return;
        }

        refund.setLastError(error);
        boolean exhausted = refund.getAttemptCount() >= policy.getMaxGatewayAttempts();

        if (refund.getStatus() != RefundStatus.FAILED) {
            refundService.transition(refund, RefundStatus.FAILED, RefundEventType.GATEWAY_FAILED,
                    "SYSTEM", "refund dispatcher",
                    retryable && !exhausted
                            ? "We couldn't reach the payment provider. Retrying automatically — your refund is safe."
                            : "This refund needs manual attention from our payments team.");
        } else {
            refundService.audit(refund, RefundEventType.GATEWAY_FAILED, RefundStatus.FAILED, RefundStatus.FAILED,
                    "SYSTEM", "refund dispatcher",
                    "Retry " + refund.getAttemptCount() + " failed: " + error, refund.getAmountPaise(), null);
        }

        if (!retryable || exhausted) {
            // Stop the automatic loop; an admin has to look at it. Parking it
            // beyond the retry horizon is how the dispatcher knows to skip it.
            refund.setNextAttemptAt(LocalDateTime.now().plusYears(100));
        }
        refundService.recordLedger(refund, PaymentTransactionType.REFUND_FAILED, "failed",
                "source=dispatcher;retryable=" + retryable);
        refundRepository.save(refund);
    }

    /** 15m, 30m, 60m, 120m … capped at a day. */
    private long backoffMinutes(int attempt) {
        long base = policy.getRetryBackoffMinutes();
        long minutes = base * (long) Math.pow(2, Math.max(0, attempt - 1));
        return Math.min(minutes, 24 * 60);
    }
}
