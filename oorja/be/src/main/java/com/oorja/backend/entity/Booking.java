package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing token, e.g. TKN-26-00231 */
    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plant_id", nullable = false)
    private Plant plant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private Technician technician;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "scheduled_time", nullable = false)
    private LocalTime scheduledTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Service address for this booking, as entered at checkout. */
    @Column(columnDefinition = "TEXT")
    private String location;

    /** Amount paid (in paise) for storefront bookings; null for quote requests. */
    @Column(name = "amount_paise")
    private Long amountPaise;

    /** Razorpay payment id, once paid. */
    @Column(name = "payment_ref")
    private String paymentRef;

    /** Client-supplied Idempotency-Key; unique per customer (see V20). Null when not supplied. */
    @Column(name = "idempotency_key", length = 80, updatable = false)
    private String idempotencyKey;

    /** Human-friendly label shown on the storefront, e.g. "Solar Plant Cleaning — Deep Clean". */
    @Column(name = "service_label")
    private String serviceLabel;

    /**
     * Whether this booking actually holds a claimed seat in {@code booking_slots}
     * for its current scheduledDate/scheduledTime — see SlotService. False for
     * bookings whose date/time is a placeholder rather than a real customer
     * pick (e.g. an AMC quote request), so cancelling/deleting them doesn't
     * release a seat they never claimed.
     */
    @Column(name = "slot_reserved", nullable = false)
    @Builder.Default
    private boolean slotReserved = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
