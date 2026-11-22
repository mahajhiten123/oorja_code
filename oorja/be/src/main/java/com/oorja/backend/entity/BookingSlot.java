package com.oorja.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Tracks how many active bookings have claimed a given (date, time) slot
 * against a configurable capacity — see {@link com.oorja.backend.service.SlotService}
 * for the reservation/release logic that keeps {@code bookedCount}
 * consistent under concurrent requests.
 * <p>
 * A missing row for a (date, time) pair means "untouched, full capacity
 * available" — rows are created lazily on first reservation rather than
 * pre-populated for every future date.
 */
@Entity
@Table(name = "booking_slots", uniqueConstraints = @UniqueConstraint(columnNames = {"slot_date", "slot_time"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "slot_time", nullable = false)
    private LocalTime slotTime;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "booked_count", nullable = false)
    @Builder.Default
    private Integer bookedCount = 0;

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
