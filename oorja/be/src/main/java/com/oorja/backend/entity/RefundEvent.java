package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.RefundEventType;
import com.oorja.backend.entity.enums.RefundStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for one {@link Refund}. Every transition, gateway
 * call, webhook and reconciliation pass writes a row here and nothing ever
 * updates or deletes one. Both the customer's refund timeline and the ops
 * console read from this table rather than inferring history from
 * timestamps on the refund row.
 */
@Entity
@Table(name = "refund_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refund_id", nullable = false)
    private Refund refund;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private RefundStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private RefundStatus toStatus;

    /** CUSTOMER, ADMIN, SYSTEM or PROVIDER. */
    @Column(name = "actor_type", nullable = false)
    @Builder.Default
    private String actorType = "SYSTEM";

    /** Who exactly — an admin email, "Razorpay webhook", "reconciliation job". */
    @Column(name = "actor_label")
    private String actorLabel;

    /** Customer-safe sentence. Assume anything written here may be shown in the app. */
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "amount_paise")
    private Long amountPaise;

    /** Gateway refund/payment id relevant to this event, if any. */
    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
