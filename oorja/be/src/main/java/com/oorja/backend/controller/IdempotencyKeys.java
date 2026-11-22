package com.oorja.backend.controller;

import com.oorja.backend.exception.ApiException;

import java.util.regex.Pattern;

/** Validation for the optional {@code Idempotency-Key} request header. */
final class IdempotencyKeys {

    static final String HEADER = "Idempotency-Key";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{8,80}$");

    private IdempotencyKeys() {}

    /** Null/blank means "no key supplied" (legacy clients keep working); anything else must be well-formed. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (!VALID.matcher(key).matches()) {
            throw ApiException.badRequest(
                    "Idempotency-Key must be 8-80 characters: letters, digits, '-' or '_' (a UUID works).");
        }
        return key;
    }
}
