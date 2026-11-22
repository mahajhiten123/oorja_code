package com.oorja.backend.storage;

import com.oorja.backend.entity.Booking;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the key prefix an attachment is stored under.
 *
 * Layout: {@code bookings/{yyyy}/{MM}/{bookingToken}} — for example
 * {@code bookings/2026/09/BOOK-10342/8af3….jpg}.
 *
 * Date partitioning keeps any single prefix from growing without bound, makes
 * lifecycle rules and cost analysis easy to scope by period, and means a
 * bucket listing is browsable by a human. The booking token (rather than the
 * numeric id) makes objects traceable back to a booking straight from the key.
 */
public final class StorageKeys {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");

    private StorageKeys() {}

    public static String bookingPrefix(Booking booking) {
        LocalDateTime created = booking.getCreatedAt() != null ? booking.getCreatedAt() : LocalDateTime.now();
        String reference = booking.getToken() != null && !booking.getToken().isBlank()
                ? sanitize(booking.getToken())
                : String.valueOf(booking.getId());

        return "bookings/" + created.format(YEAR) + "/" + created.format(MONTH) + "/" + reference;
    }

    /** Keys must not contain path separators or anything that could escape the prefix. */
    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
