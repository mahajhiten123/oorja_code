package com.oorja.backend.service;

import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.exception.ApiException;
import com.razorpay.Refund;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RazorpayService {

    @Value("${razorpay.key.id}")
    private String keyId;

    @Value("${razorpay.key.secret}")
    private String keySecret;

    /** The public (non-secret) key id the frontend passes to Checkout. */
    public String getPublicKeyId() {
        return keyId;
    }

    @Value("${razorpay.webhook.secret}")
    private String webhookSecret;

    /**
     * Lazily-created, shared Razorpay client. The SDK creates an OkHttp client
     * on construction, so instantiating one per payment call wastes a TCP
     * connection setup on every request. Access via {@link #client()}.
     */
    private volatile RazorpayClient sharedClient;

    private RazorpayClient client() throws RazorpayException {
        if (sharedClient == null) {
            synchronized (this) {
                if (sharedClient == null) {
                    sharedClient = new RazorpayClient(keyId, keySecret);
                }
            }
        }
        return sharedClient;
    }

    /**
     * Creates a Razorpay order server-side so the amount can't be tampered with
     * by the client. The frontend opens Razorpay Checkout using the returned orderId.
     * The amount here must already be the authoritative, server-computed paise
     * value (see PricingService) — this method does not do any pricing itself.
     *
     * Retries a transient failure (timeout, 5xx, rate limit — see
     * {@link #isRetryable}) up to 3 times with a short backoff before giving
     * up, so a momentary Razorpay blip doesn't fail checkout outright. A
     * non-retryable failure (bad config, a validation error) skips the
     * retry loop entirely — see the catch block below, which only escalates
     * to {@link RetryableGatewayException} (and therefore only retries) for
     * the transient case.
     */
    @Retryable(
            retryFor = RetryableGatewayException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public CreateOrderResponse createOrder(long amountInPaise, String receipt) {
        if (keySecret == null || keySecret.isBlank()) {
            throw ApiException.badRequest(
                    "Payment gateway is not configured on the server. Set RAZORPAY_KEY_SECRET.");
        }

        try {
            RazorpayClient client = client();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", receipt);
            orderRequest.put("payment_capture", 1);

            com.razorpay.Order order = client.orders.create(orderRequest);

            return CreateOrderResponse.builder()
                    .orderId(order.get("id"))
                    .amount(amountInPaise)
                    .currency("INR")
                    .keyId(keyId)
                    .build();

        } catch (RazorpayException e) {
            if (isRetryable(e)) {
                log.warn("Transient failure creating Razorpay order, will retry: {}", e.getMessage());
                throw new RetryableGatewayException(e);
            }
            log.error("Failed to create Razorpay order (non-retryable): {}", e.getMessage());
            throw new ApiException("Unable to start payment right now. Please try again.",
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Runs once every retry attempt in {@link #createOrder} has been exhausted. */
    @Recover
    public CreateOrderResponse recoverCreateOrder(RetryableGatewayException e, long amountInPaise, String receipt) {
        log.error("Failed to create Razorpay order after retries: {}", e.getCause().getMessage());
        // The gateway is the failing party, not the caller: 503 tells the client this is temporary and retryable.
        throw new ApiException("Unable to start payment right now. Please try again.",
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** Unchecked signal used only to trigger the {@code @Retryable} above; never escapes this class. */
    static class RetryableGatewayException extends RuntimeException {
        RetryableGatewayException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Verifies the HMAC-SHA256 signature Razorpay returns after a successful
     * checkout, proving the payment wasn't spoofed client-side.
     */
    public boolean verifySignature(VerifyPaymentRequest request) {
        if (keySecret == null || keySecret.isBlank()) {
            return false;
        }
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", request.razorpay_order_id());
            options.put("razorpay_payment_id", request.razorpay_payment_id());
            options.put("razorpay_signature", request.razorpay_signature());

            return Utils.verifyPaymentSignature(options, keySecret);
        } catch (RazorpayException e) {
            log.warn("Razorpay signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifies the X-Razorpay-Signature header on an inbound webhook — a
     * completely different signature from {@link #verifySignature}: this one
     * is keyed with the webhook secret (set in Razorpay Dashboard > Webhooks),
     * computed over the *raw* request body, not order/payment ids. Signing
     * the wrong thing with the wrong secret is the single most common
     * Razorpay webhook mistake, so the two are kept deliberately separate.
     */
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Rejecting Razorpay webhook: RAZORPAY_WEBHOOK_SECRET is not configured.");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            return Utils.verifyWebhookSignature(rawBody, signatureHeader, webhookSecret);
        } catch (RazorpayException e) {
            log.warn("Razorpay webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Issues a refund against a previously captured payment.
     *
     * Two things make this safe to call from a retrying dispatcher:
     * <ul>
     *   <li>the {@code X-Razorpay-Idempotency-Key} header — Razorpay returns
     *       the <i>original</i> refund for a repeated key rather than
     *       creating a second one, which is the only real protection against
     *       double-paying a customer after a socket timeout;</li>
     *   <li>{@code receipt} + notes, so a refund is traceable back to our
     *       refund number from the Razorpay dashboard without a spreadsheet.</li>
     * </ul>
     *
     * Failures are classified rather than flattened: a
     * {@link GatewayException#retryable()} error means "try again later"
     * (timeout, 5xx, rate limit), while a non-retryable one means the refund
     * will never succeed as specified and needs a human.
     */
    public RefundResult refund(String paymentId, long amountPaise, String idempotencyKey,
                               String refundNumber, String speed) {
        if (keySecret == null || keySecret.isBlank()) {
            throw new GatewayException("Payment gateway is not configured on the server.", "CONFIG", false);
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new GatewayException("No gateway payment reference to refund against.", "NO_PAYMENT_REF", false);
        }
        if (amountPaise <= 0) {
            throw new GatewayException("Refund amount must be greater than zero.", "ZERO_AMOUNT", false);
        }

        try {
            RazorpayClient client = client();
            client.addHeaders(java.util.Map.of("X-Razorpay-Idempotency-Key", idempotencyKey));

            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountPaise);
            refundRequest.put("speed", speed == null || speed.isBlank() ? "normal" : speed);
            refundRequest.put("receipt", refundNumber);
            refundRequest.put("notes", new JSONObject()
                    .put("refund_number", refundNumber)
                    .put("source", "oorja-refund-engine"));

            Refund refund = client.payments.refund(paymentId, refundRequest);
            return new RefundResult(refund.get("id"), refund.get("status"));

        } catch (RazorpayException e) {
            boolean retryable = isRetryable(e);
            log.error("Razorpay refund failed for payment {} (retryable={}): {}", paymentId, retryable, e.getMessage());
            throw new GatewayException(e.getMessage(), "GATEWAY_ERROR", retryable);
        }
    }

    /**
     * Reads a refund's current state straight from the provider. This is the
     * backstop for the whole pipeline: if a webhook is lost, misrouted or
     * never configured, the reconciliation job asks here instead of leaving
     * a customer's money in limbo.
     */
    public RefundResult fetchRefund(String paymentId, String refundId) {
        if (keySecret == null || keySecret.isBlank()) {
            throw new GatewayException("Payment gateway is not configured on the server.", "CONFIG", false);
        }
        try {
            RazorpayClient client = client();
            Refund refund = client.payments.fetchRefund(paymentId, refundId);
            return new RefundResult(refund.get("id"), refund.get("status"));
        } catch (RazorpayException e) {
            throw new GatewayException(e.getMessage(), "FETCH_FAILED", isRetryable(e));
        }
    }

    /**
     * Anything that looks like a transport or capacity problem is worth
     * retrying; a validation error ("amount exceeds captured amount") never
     * is, and retrying it just burns attempts and delays the human fix.
     */
    private boolean isRetryable(RazorpayException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("timeout") || m.contains("timed out") || m.contains("connection")
                || m.contains("unavailable") || m.contains("rate limit") || m.contains("too many")
                || m.contains("gateway") || m.contains("500") || m.contains("502")
                || m.contains("503") || m.contains("504");
    }

    /** A gateway failure that knows whether trying again could ever help. */
    public static class GatewayException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public GatewayException(String message, String code, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }

    /** id: the Razorpay refund id (rfnd_...); status: "processed", "pending", etc. */
    public record RefundResult(String id, String status) {}
}
