package com.oorja.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per inbound webhook delivery we've accepted (signature verified),
 * keyed on (provider, event_id) so a duplicate delivery — which Razorpay
 * explicitly says can happen — is a no-op rather than double-processing a
 * payment. See PaymentWebhookService.
 */
@Entity
@Table(name = "webhook_events", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "event_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Builder.Default
    private String provider = "razorpay";

    /** Razorpay's x-razorpay-event-id header — stable across retries of the same event. */
    @Column(name = "event_id", nullable = false)
    private String eventId;

    /** e.g. "payment.captured", "payment.failed" */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "received_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime receivedAt = LocalDateTime.now();

    private LocalDateTime processedAt;

    /** RECEIVED -> PROCESSED, or IGNORED (event type we don't act on), or FAILED (error while handling — safe to retry). */
    @Column(nullable = false)
    @Builder.Default
    private String status = "RECEIVED";
}
