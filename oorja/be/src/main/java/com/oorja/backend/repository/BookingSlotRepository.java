package com.oorja.backend.repository;

import com.oorja.backend.entity.BookingSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface BookingSlotRepository extends JpaRepository<BookingSlot, Long> {

    Optional<BookingSlot> findBySlotDateAndSlotTime(LocalDate slotDate, LocalTime slotTime);

    /** All rows touched so far for a date — used to build the availability list without one query per slot time. */
    List<BookingSlot> findBySlotDate(LocalDate slotDate);

    /**
     * Atomically claims one seat in an existing slot row, but only if it
     * isn't already full. Returns the number of rows updated (0 or 1) —
     * this is the compare-and-swap that makes concurrent reservations for
     * the last seat in a slot safe without a table-wide lock.
     */
    @Modifying
    @Query("update BookingSlot s set s.bookedCount = s.bookedCount + 1 " +
            "where s.slotDate = :slotDate and s.slotTime = :slotTime and s.bookedCount < s.capacity")
    int tryClaimSeat(@Param("slotDate") LocalDate slotDate, @Param("slotTime") LocalTime slotTime);

    /**
     * Claims a seat regardless of capacity (used only for the "money is
     * already captured, the booking must exist" webhook fallback and admin
     * edits, which must never fail on a full slot) — still keeps the
     * counter accurate for future customer-facing availability checks.
     */
    @Modifying
    @Query("update BookingSlot s set s.bookedCount = s.bookedCount + 1 " +
            "where s.slotDate = :slotDate and s.slotTime = :slotTime")
    int forceClaimSeat(@Param("slotDate") LocalDate slotDate, @Param("slotTime") LocalTime slotTime);

    @Modifying
    @Query("update BookingSlot s set s.bookedCount = case when s.bookedCount > 0 then s.bookedCount - 1 else 0 end " +
            "where s.slotDate = :slotDate and s.slotTime = :slotTime")
    int releaseSeat(@Param("slotDate") LocalDate slotDate, @Param("slotTime") LocalTime slotTime);

    /**
     * Race-safe row creation: two concurrent first-time reservations for the
     * same never-touched slot both try this; the database's unique
     * constraint on (slot_date, slot_time) lets exactly one INSERT win via
     * ON CONFLICT DO NOTHING, and the caller re-runs {@link #tryClaimSeat}
     * (or {@link #forceClaimSeat}) afterwards either way.
     */
    @Modifying
    @Query(value = "insert into booking_slots (slot_date, slot_time, capacity, booked_count, created_at, updated_at) " +
            "values (:slotDate, :slotTime, :capacity, 0, now(), now()) " +
            "on conflict (slot_date, slot_time) do nothing", nativeQuery = true)
    int createIfAbsent(@Param("slotDate") LocalDate slotDate, @Param("slotTime") LocalTime slotTime, @Param("capacity") int capacity);
}
