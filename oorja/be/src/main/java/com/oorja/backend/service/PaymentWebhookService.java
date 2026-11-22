package com.oorja.backend.service;

import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.PaymentTransaction;
import com.oorja.backend.entity.WebhookEvent;
import com.oorja.backend.entity.enums.PaymentStatus;
import com.oorja.backend.entity.enums.PaymentTransactionType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.PaymentRepository;
import com.oorja.backend.repository.PaymentTransactionRepository;
import com.oorja.backend.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Handles inbound {@code POST /api/webhooks/razorpay} deliveries.
 * <p>
 * This is what makes a Payment's CAPTURED status trustworthy independent of
 * whether the customer's browser stayed open for the client-side
 * {@code /verify} call — Razorpay recommends exactly this pattern (see
 * "Capturing Late Authorised Payments" in their webhook docs). It's also
 * what lets an abandoned-but-actually-paid checkout still end up with a
 * real booking: see {@link BookingService#createFromCapturedPayment} and
 * {@link QuotationService#finalizeFromWebhook}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    /**
     * Refund events are stage 6 of the refund pipeline: the provider telling
     * us the money actually moved. Everything else about a refund is our own
     * bookkeeping — this is the only authoritative confirmation, which is why
     * it shares the same signature check, dedupe and retry semantics as
     * payment capture rather than living in its own half-trusted endpoint.
     */
    private static final Set<String> HANDLED_EVENTS = Set.of(
            "payment.captured", "payment.failed",
            "refund.created", "refund.processed", "refund.failed");

    private final RazorpayService razorpayService;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final BookingService bookingService;
    private final QuotationService quotationService;
    private final RefundService refundService;
    private final PaymentService paymentService;

    @Transactional
    public void process(String rawBody, String signatureHeader, String eventIdHeader) {
        if (!razorpayService.verifyWebhookSignature(rawBody, signatureHeader)) {
            throw ApiException.unauthorized("Invalid webhook signature.");
        }

        JSONObject json;
        try {
            json = new JSONObject(rawBody);
        } catch (JSONException e) {
            throw ApiException.badRequest("Malformed webhook payload.");
        }

        String eventType = json.optString("event", "unknown");

        // Razorpay explicitly documents that the same event can be delivered
        // more than once, and recommends deduping on this header rather than
        // anything inside the JSON body.
        if (eventIdHeader == null || eventIdHeader.isBlank()) {
            log.warn("Razorpay webhook missing X-Razorpay-Event-Id header, event={} — skipping (can't safely dedupe).", eventType);
            return;
        }

        WebhookEvent existing = webhookEventRepository.findByProviderAndEventId("razorpay", eventIdHeader).orElse(null);
        if (existing != null && ("PROCESSED".equals(existing.getStatus()) || "IGNORED".equals(existing.getStatus()))) {
            log.info("Ignoring duplicate Razorpay webhook delivery: {} ({})", eventIdHeader, eventType);
            return;
        }

        WebhookEvent event = existing != null ? existing : webhookEventRepository.save(WebhookEvent.builder()
                .provider("razorpay")
                .eventId(eventIdHeader)
                .eventType(eventType)
                .status("RECEIVED")
                .build());

        try {
            if (!HANDLED_EVENTS.contains(eventType)) {
                event.setStatus("IGNORED");
            } else if ("payment.captured".equals(eventType)) {
                handleCaptured(json);
                event.setStatus("PROCESSED");
            } else if ("payment.failed".equals(eventType)) {
                handleFailed(json);
                event.setStatus("PROCESSED");
            } else {
                handleRefundEvent(eventType, json);
                event.setStatus("PROCESSED");
            }
            event.setProcessedAt(LocalDateTime.now());
            webhookEventRepository.save(event);
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook {} ({}): {}", eventIdHeader, eventType, e.getMessage(), e);
            event.setStatus("FAILED");
            webhookEventRepository.save(event);
            // Re-throw so this webhook delivery fails (non-2xx) and Razorpay
            // retries it later — safe to retry, since the WebhookEvent row
            // above means a *successful* run will only ever happen once.
            throw e;
        }
    }

    private void handleCaptured(JSONObject json) {
        JSONObject paymentEntity = json.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
        String razorpayPaymentId = paymentEntity.getString("id");
        String orderId = paymentEntity.optString("order_id", null);
        long capturedAmountPaise = paymentEntity.getLong("amount");

        if (orderId == null) {
            log.warn("payment.captured webhook for {} has no order_id — nothing to reconcile against.", razorpayPaymentId);
            return;
        }

        Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(orderId).orElse(null);
        if (payment == null) {
            log.warn("payment.captured webhook for order {} doesn't match any Payment we created.", orderId);
            return;
        }

        // Defence in depth: the amount was already fixed server-side at
        // order-creation (PricingService), so what Razorpay says it actually
        // captured should match exactly. If it doesn't, something is
        // seriously wrong — don't silently finalize a booking against it.
        if (payment.getAmountPaise() != capturedAmountPaise) {
            log.error("Amount mismatch on order {}: we expected {} paise, Razorpay captured {} paise. Leaving for manual review.",
                    orderId, payment.getAmountPaise(), capturedAmountPaise);
            return;
        }

        // Idempotent: a payment already captured (by /verify, or an earlier delivery) is left exactly as it is.
        // Gateway truth wins over our own bookkeeping, so a capture is recorded even for an order we had marked
        // FAILED or CANCELLED - see PaymentService#applyCapture.
        paymentService.applyCapture(payment, razorpayPaymentId, "webhook");

        // If the client-driven flow (POST /bookings or /record-payment) never
        // arrived, finish the job here so a captured payment never sits
        // orphaned with no booking against it.
        if (payment.getBooking() == null) {
            if (payment.getQuotationId() != null) {
                quotationService.finalizeFromWebhook(payment);
            } else {
                bookingService.createFromCapturedPayment(payment);
            }
        }
    }

    /**
     * refund.created / refund.processed / refund.failed.
     *
     * We deliberately don't trust the event name alone — Razorpay sends the
     * refund entity with its own {@code status}, and out-of-order delivery is
     * normal, so the entity's status is what decides. A refund we don't
     * recognise is logged and ignored rather than treated as an error: it may
     * have been issued from the Razorpay dashboard by hand, and failing the
     * delivery would make Razorpay retry it forever.
     */
    private void handleRefundEvent(String eventType, JSONObject json) {
        JSONObject refundEntity = json.getJSONObject("payload").getJSONObject("refund").getJSONObject("entity");
        String refundId = refundEntity.getString("id");
        String status = refundEntity.optString("status", "unknown");
        Long amountPaise = refundEntity.has("amount") ? refundEntity.getLong("amount") : null;

        if ("processed".equalsIgnoreCase(status)) {
            refundService.onGatewayRefundProcessed(refundId, amountPaise, status);
            return;
        }
        if ("failed".equalsIgnoreCase(status)) {
            String description = refundEntity.optString("error_description",
                    refundEntity.optString("speed_processed", "The bank declined this refund."));
            refundService.onGatewayRefundFailed(refundId, status, description);
            return;
        }

        // "pending" / "created": nothing to change, the refund is already
        // PROCESSING on our side and the reconciliation job watches it.
        log.info("Refund webhook {} for {} with status {} — no action needed.", eventType, refundId, status);
    }

    private void handleFailed(JSONObject json) {
        JSONObject paymentEntity = json.getJSONObject("payload").getJSONObject("payment").getJSONObject("entity");
        String orderId = paymentEntity.optString("order_id", null);
        if (orderId == null) {
            return;
        }

        Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(orderId).orElse(null);
        if (payment == null) {
            return;
        }

        // Razorpay documents that a payment.failed can be followed by a
        // payment.captured for the same transaction (user-initiated UPI
        // retries, late authorisation). Never downgrade an already-captured
        // payment because an earlier failure arrived out of order - the state
        // machine only allows FAILED from CREATED / PENDING / FAILED.
        // (The seat held for this order is deliberately NOT released here: the
        // customer may retry the payment on the same order. The expiry job
        // releases it if they don't.)
        if (!payment.getStatus().canTransitionTo(PaymentStatus.FAILED)) {
            return;
        }

        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        paymentTransactionRepository.save(PaymentTransaction.builder()
                .payment(payment)
                .type(PaymentTransactionType.PAYMENT_FAILED)
                .amountPaise(payment.getAmountPaise())
                .status(PaymentStatus.FAILED.name())
                .providerTransactionId(paymentEntity.optString("id", null))
                .metadata("source=webhook")
                .build());
    }
}
