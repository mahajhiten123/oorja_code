package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.entity.enums.RefundStatus;
import com.oorja.backend.entity.enums.SubjectType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Money owed back to a customer, and the full story of how that number was
 * arrived at.
 *
 * The amounts are deliberately redundant: {@code amountPaise} is the net
 * figure actually sent to the gateway, but {@code grossAmountPaise},
 * {@code refundPercentBps}, {@code cancellationFeePaise} and
 * {@code gatewayFeePaise} are stored too, so a refund invoice can be
 * rebuilt years later without re-running a policy engine that will by then
 * have changed. Never recompute a historical refund — read these columns.
 *
 * @see com.oorja.backend.service.RefundEligibilityService  (may we refund?)
 * @see com.oorja.backend.service.RefundCalculationService  (how much?)
 * @see com.oorja.backend.service.RefundService             (the state machine)
 */
@Entity
@Table(name = "refunds")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing reference shown to the customer and on statements, e.g. RFD-26-000231. */
    @Column(name = "refund_number", nullable = false, unique = true, updatable = false)
    private String refundNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /**
     * The ledger row this refund debits. Nullable only for legacy refunds
     * raised before payments existed as first-class rows; everything new
     * goes through PaymentService and always has one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    // ---------- Why ----------

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false)
    @Builder.Default
    private RefundReasonCode reasonCode = RefundReasonCode.CUSTOMER_REQUEST;

    /** Customer's or admin's own words. Never parsed — {@link #reasonCode} is what drives policy. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by_type", nullable = false)
    @Builder.Default
    private SubjectType requestedByType = SubjectType.CUSTOMER;

    @Column(name = "requested_by_id")
    private Long requestedById;

    // ---------- Eligibility snapshot (frozen at request time) ----------

    /** Which policy band applied, e.g. FULL_REFUND_WINDOW, LATE_CANCELLATION, TECHNICIAN_DISPATCHED. */
    @Column(name = "policy_tier")
    private String policyTier;

    /** Human-readable explanation of the decision, shown verbatim to the customer. */
    @Column(name = "eligibility_note", columnDefinition = "TEXT")
    private String eligibilityNote;

    // ---------- The money ----------

    /** What the customer originally paid for this booking. */
    @Column(name = "gross_amount_paise", nullable = false)
    private Long grossAmountPaise;

    /** Policy percentage in basis points (10000 = 100%), before flat fees. */
    @Column(name = "refund_percent_bps", nullable = false)
    @Builder.Default
    private Integer refundPercentBps = 10000;

    /** Flat policy deduction (late cancellation / dispatched-technician visit fee). */
    @Column(name = "cancellation_fee_paise", nullable = false)
    @Builder.Default
    private Long cancellationFeePaise = 0L;

    /** Non-refundable gateway charge, when policy passes it on. Usually 0. */
    @Column(name = "gateway_fee_paise", nullable = false)
    @Builder.Default
    private Long gatewayFeePaise = 0L;

    /** NET payable — the exact figure sent to the gateway. Never exceeds gross. */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    // ---------- State ----------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RefundStatus status = RefundStatus.REQUESTED;

    @Column(name = "auto_approved", nullable = false)
    @Builder.Default
    private Boolean autoApproved = false;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** Admin's note when rejecting, or the last customer-safe error message. */
    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    // ---------- Gateway ----------

    /** The original captured payment being refunded (rzp payment id). */
    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    /** Set once the gateway has accepted the refund (rfnd_...). */
    @Column(name = "razorpay_refund_id")
    private String razorpayRefundId;

    /** Provider's own status string — "pending", "processed", "failed". */
    @Column(name = "gateway_status")
    private String gatewayStatus;

    @Column(nullable = false)
    @Builder.Default
    private String speed = "normal";

    /**
     * Stable per-refund key sent to the gateway so a retry after a timeout
     * can never create a second real refund. Generated once, at request time.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** When the dispatcher may next try the gateway (exponential backoff). */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    // ---------- Timeline ----------

    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime requestedAt = LocalDateTime.now();

    /** Set the moment the gateway accepted it (status -> PROCESSING). */
    @Column(name = "initiated_at")
    private LocalDateTime initiatedAt;

    /** Set once the provider confirms settlement (status -> COMPLETED). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** What we promise the customer: initiated_at + policy settlement days. */
    @Column(name = "expected_settlement_at")
    private LocalDateTime expectedSettlementAt;

    /** Last time the ledger job checked this refund against the provider and the payment ledger. */
    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Optimistic lock. Two admins clicking Approve at the same instant, or an
     * approve racing the reconciliation job, must not both dispatch to the
     * gateway; one of them loses here instead of double-paying a customer.
     */
    @Version
    @Column(name = "row_version", nullable = false)
    @Builder.Default
    private Long rowVersion = 0L;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Total withheld under policy — what the breakdown calls "deductions". */
    @Transient
    public long getTotalDeductionsPaise() {
        return Math.max(0L, (grossAmountPaise == null ? 0L : grossAmountPaise)
                - (amountPaise == null ? 0L : amountPaise));
    }
}
