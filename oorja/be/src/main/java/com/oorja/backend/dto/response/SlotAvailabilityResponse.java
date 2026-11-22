package com.oorja.backend.dto.response;

import lombok.Builder;

import java.time.LocalTime;

/**
 * One row of the server-authoritative slot grid for a given date — the
 * frontend renders exactly these entries instead of keeping its own
 * hardcoded time-slot list, so "how many slots, what capacity, what's
 * already full" only ever lives in one place.
 */
@Builder
public record SlotAvailabilityResponse(
        LocalTime time,
        /** e.g. "9 AM – 11 AM", precomputed so the frontend doesn't duplicate the formatting rule. */
        String label,
        int capacity,
        int booked,
        int remaining,
        boolean full,
        /** True if this slot is in the past, or inside the minimum lead-time window, for the requested date. */
        boolean tooSoon,
        /** Convenience for the frontend: {@code !full && !tooSoon}, computed once here rather than re-derived per-client. */
        boolean available
) {}
