package com.oorja.backend.service;

import com.oorja.backend.dto.response.SlotAvailabilityResponse;
import com.oorja.backend.entity.BookingSlot;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The slot/availability system: a fixed daily grid of time slots, each with
 * a capacity, that customer bookings claim a seat in.
 * <p>
 * Before this, "Preferred date" + "Preferred time slot" on the storefront
 * was accepted as free text with no server-side check at all — nothing
 * stopped every customer for a day from landing on the same slot. This is
 * now the single place that decides what the grid looks like, how full each
 * slot is, and who is allowed to claim a seat in one.
 * <p>
 * Concurrency: seats are claimed with a single conditional {@code UPDATE ...
 * WHERE booked_count < capacity} (see {@link BookingSlotRepository#tryClaimSeat}),
 * not a read-then-write, so two requests racing for the last seat in a slot
 * can't both succeed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotService {

    private final BookingSlotRepository bookingSlotRepository;

    /** The fixed daily slot grid. Every 2-hour window from 9 AM to 7 PM. */
    private static final List<LocalTime> SLOT_GRID = List.of(
            LocalTime.of(9, 0),
            LocalTime.of(11, 0),
            LocalTime.of(13, 0),
            LocalTime.of(15, 0),
            LocalTime.of(17, 0)
    );

    @Value("${booking.slots.capacity:4}")
    private int defaultCapacity;

    @Value("${booking.slots.min-lead-hours:2}")
    private int minLeadHours;

    /** Upper bound on how far ahead a slot can be booked - also stops junk dates minting unbounded booking_slots rows. */
    @Value("${booking.slots.max-advance-days:180}")
    private int maxAdvanceDays;

    // ---------- Read: what does the grid look like for a date? ----------

    @Transactional(readOnly = true)
    public List<SlotAvailabilityResponse> listAvailability(LocalDate date) {
        Map<LocalTime, BookingSlot> touched = bookingSlotRepository.findBySlotDate(date).stream()
                .collect(Collectors.toMap(BookingSlot::getSlotTime, s -> s));

        LocalDateTime cutoff = LocalDateTime.now().plusHours(minLeadHours);

        return SLOT_GRID.stream()
                .sorted(Comparator.naturalOrder())
                .map(time -> {
                    BookingSlot row = touched.get(time);
                    int capacity = row != null ? row.getCapacity() : defaultCapacity;
                    int booked = row != null ? row.getBookedCount() : 0;
                    int remaining = Math.max(capacity - booked, 0);
                    boolean full = remaining <= 0;
                    boolean tooSoon = LocalDateTime.of(date, time).isBefore(cutoff);
                    return SlotAvailabilityResponse.builder()
                            .time(time)
                            .label(formatLabel(time))
                            .capacity(capacity)
                            .booked(booked)
                            .remaining(remaining)
                            .full(full)
                            .tooSoon(tooSoon)
                            .available(!full && !tooSoon)
                            .build();
                })
                .toList();
    }

    /** True only for a time that's actually on the published grid — customers can't book off-grid times. */
    public boolean isOnGrid(LocalTime time) {
        return SLOT_GRID.contains(time);
    }

    // ---------- Write: claim / release a seat ----------

    /**
     * Validates the slot is on the grid, in the future (beyond the minimum
     * lead time), and not full, then atomically claims a seat. Used for
     * customer-initiated create/reschedule, where a full or invalid slot
     * must block the request.
     */
    @Transactional
    public void reserve(LocalDate date, LocalTime time) {
        if (!isOnGrid(time)) {
            throw ApiException.badRequest("That's not a valid time slot. Please pick one from the list shown.");
        }
        if (LocalDateTime.of(date, time).isBefore(LocalDateTime.now().plusHours(minLeadHours))) {
            throw ApiException.badRequest("That slot is too soon — please choose a later date or time.");
        }
        if (date.isAfter(LocalDate.now().plusDays(maxAdvanceDays))) {
            throw ApiException.badRequest("Bookings can be made at most " + maxAdvanceDays + " days ahead.");
        }
        if (!claimSeat(date, time, false)) {
            throw ApiException.conflict("That slot just filled up. Please choose another date or time.", "SLOT_FULL");
        }
    }

    /**
     * Read-only pre-flight for {@link #reserve}: same validity rules, no seat claimed. Lets create-order
     * refuse a bad or already-full slot <i>before</i> opening a Razorpay order, instead of leaving an
     * orphaned gateway order behind. It is advisory only - the atomic claim in {@link #reserve}
     * stays the single source of truth, and can still lose a race right after this passes.
     */
    @Transactional(readOnly = true)
    public void assertBookable(LocalDate date, LocalTime time) {
        if (!isOnGrid(time)) {
            throw ApiException.badRequest("That's not a valid time slot. Please pick one from the list shown.");
        }
        if (LocalDateTime.of(date, time).isBefore(LocalDateTime.now().plusHours(minLeadHours))) {
            throw ApiException.badRequest("That slot is too soon — please choose a later date or time.");
        }
        if (date.isAfter(LocalDate.now().plusDays(maxAdvanceDays))) {
            throw ApiException.badRequest("Bookings can be made at most " + maxAdvanceDays + " days ahead.");
        }
        boolean full = bookingSlotRepository.findBySlotDateAndSlotTime(date, time)
                .map(s -> s.getBookedCount() >= s.getCapacity())
                .orElse(defaultCapacity <= 0);
        if (full) {
            throw ApiException.conflict("That slot just filled up. Please choose another date or time.", "SLOT_FULL");
        }
    }

    /**
     * Claims a seat unconditionally (never blocks), for paths that must
     * still succeed even if the slot is technically full: the Razorpay
     * webhook's auto-booking-creation fallback (money is already captured —
     * we cannot refuse to create the booking) and admin edits. Still keeps
     * the counter accurate for future customer-facing availability checks,
     * and logs when it pushes a slot over capacity so it's visible in ops.
     */
    @Transactional
    public void reserveBestEffort(LocalDate date, LocalTime time) {
        if (!isOnGrid(time)) {
            // Not part of the published grid (e.g. an AMC quote's arbitrary
            // placeholder date) - nothing meaningful to track.
            return;
        }
        boolean withinCapacity = claimSeat(date, time, false);
        if (!withinCapacity) {
            boolean forced = claimSeat(date, time, true);
            if (forced) {
                // Deliberate, rare exception to "never exceed capacity": a PAID booking with no seat hold (its hold
                // lapsed before payment completed) found the slot full. Ops must re-slot or refund it.
                log.error("OVER CAPACITY: slot {} {} claimed by a paid booking that held no seat - needs ops attention", date, time);
            }
        }
    }

    /** Frees a previously claimed seat. Safe to call even if the slot was never tracked (no-op). */
    @Transactional
    public void release(LocalDate date, LocalTime time) {
        if (!isOnGrid(time)) {
            return;
        }
        bookingSlotRepository.releaseSeat(date, time);
    }

    /**
     * Attempts the conditional (or forced) claim; if the row doesn't exist
     * yet, creates it race-safely and retries once. Returns whether the
     * seat was actually claimed.
     */
    private boolean claimSeat(LocalDate date, LocalTime time, boolean force) {
        int updated = force ? bookingSlotRepository.forceClaimSeat(date, time) : bookingSlotRepository.tryClaimSeat(date, time);
        if (updated > 0) {
            return true;
        }
        // Either the row doesn't exist yet, or it's genuinely full. Try to
        // create it (no-op if another request just created it first), then
        // retry the claim exactly once.
        bookingSlotRepository.createIfAbsent(date, time, defaultCapacity);
        updated = force ? bookingSlotRepository.forceClaimSeat(date, time) : bookingSlotRepository.tryClaimSeat(date, time);
        return updated > 0;
    }

    private String formatLabel(LocalTime start) {
        LocalTime end = start.plusHours(2);
        return formatHour(start) + " – " + formatHour(end);
    }

    private String formatHour(LocalTime t) {
        int hour24 = t.getHour();
        String period = hour24 >= 12 ? "PM" : "AM";
        int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
        return hour12 + " " + period;
    }
}
