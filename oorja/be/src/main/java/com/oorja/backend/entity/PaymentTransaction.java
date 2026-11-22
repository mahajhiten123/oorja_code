package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.PaymentTransactionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for a {@link Payment} — one row per event
 * (order created, captured, failed, refund initiated/completed). Nothing
 * ever updates or deletes a row here; this is what support/ops read when a
 * customer says "I paid but my booking wasn't created".
 */
@Entity
@Table(name = "payment_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentTransactionType type;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    /** Free-text snapshot of the Payment/Razorpay status at this point, e.g. "CAPTURED", "processed". */
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    @Builder.Default
    private String provider = "razorpay";

    /** Razorpay order id, payment id, or refund id — whichever this event is about. */
    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
