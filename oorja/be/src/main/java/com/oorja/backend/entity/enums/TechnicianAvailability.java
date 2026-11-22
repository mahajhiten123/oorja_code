package com.oorja.backend.entity.enums;

/**
 * Admin-controlled duty status for a technician. Whether a technician is
 * currently "ON_JOB" is derived at read time from their active bookings
 * (see TechnicianService) rather than stored here — this enum only tracks
 * the duty toggle an admin sets directly.
 */
public enum TechnicianAvailability {
    AVAILABLE,
    OFF_DUTY
}
