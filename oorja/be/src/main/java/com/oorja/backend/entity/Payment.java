package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The authoritative record of a payment attempt. Created the moment we ask
 * Razorpay for an order (before the customer has paid anything), so that
 * the amount charged is fixed server-side and can never be a number the
 * frontend made up. A {@link Booking} is only ever allowed to carry an
 * {@code amountPaise}/{@code paymentRef} that were copied from a Payment
 * row here with status {@link PaymentStatus#CAPTURED} — see
 * {@code BookingService#createForCustomer}.
 */
@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing reference, e.g. PAY-26-00231. */
    @Column(name = "payment_number", nullable = false, unique = true)
    private String paymentNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Null until a booking is actually created against this (captured) payment. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    /** Authoritative amount, computed server-side by PricingService — never trusted from the client. */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "razorpay_order_id", nullable = false, unique = true)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    /** Client-supplied Idempotency-Key for order creation; unique per customer (see V20). */
    @Column(name = "idempotency_key", length = 80, updatable = false)
    private String idempotencyKey;

    // --- What this payment was for, kept for audit/debugging (not trusted for pricing on its own) ---
    @Column(name = "service_id")
    private String serviceId;

    @Column(name = "tier_id")
    private String tierId;

    /** Set instead of serviceId/tierId when this payment is for an admin-priced Quotation (AMC/installation), not the catalog. */
    @Column(name = "quotation_id")
    private Long quotationId;

    @Column(name = "coupon_code")
    private String couponCode;

    // --- Booking draft, so the webhook can auto-create the booking if the
    // customer's browser never calls POST /bookings after a successful pay ---
    @Column(name = "draft_plant_id")
    private Long draftPlantId;

    @Column(name = "draft_scheduled_date")
    private java.time.LocalDate draftScheduledDate;

    @Column(name = "draft_scheduled_time")
    private java.time.LocalTime draftScheduledTime;

    @Column(name = "draft_service_type")
    private String draftServiceType;

    @Column(name = "draft_notes", columnDefinition = "TEXT")
    private String draftNotes;

    @Column(name = "draft_location")
    private String draftLocation;

    /**
     * Whether a seat in booking_slots is currently counted against this payment's draft slot
     * (claimed at create-order time, taken over by the booking, or given back if never paid).
     * See {@link com.oorja.backend.entity.enums.SlotHoldState}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "slot_hold_state", nullable = false)
    @Builder.Default
    private com.oorja.backend.entity.enums.SlotHoldState slotHoldState = com.oorja.backend.entity.enums.SlotHoldState.NONE;

    /** After this moment an UNPAID hold may be released by the expiry job. Never expires once the payment is captured. */
    @Column(name = "slot_hold_expires_at")
    private LocalDateTime slotHoldExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
