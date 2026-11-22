package com.oorja.backend.dto.response;

import java.time.Instant;
import java.util.Map;

/**
 * The one shape every failed request is answered with — business errors,
 * validation errors, framework errors (bad JSON, wrong method, 404), security
 * rejections and unexpected 500s all look the same, so a client needs exactly
 * one error parser.
 *
 * <pre>
 * {
 *   "success": false,
 *   "status": 409,
 *   "code": "SLOT_FULL",
 *   "message": "That slot just filled up. Please choose another date or time.",
 *   "errors": { "scheduledTime": "must not be null" },   // validation only
 *   "path": "/api/customer/bookings",
 *   "timestamp": "2026-11-09T06:30:00Z"
 * }
 * </pre>
 *
 * {@code success} and {@code message} are deliberately the same fields
 * {@link ApiMessageResponse} uses, and {@code errors} is the same field-map the
 * old validation response used, so existing frontend code keeps working.
 * {@code code} is the stable, machine-readable part: branch on it, never on
 * the human message.
 */
public record ApiErrorResponse(
        boolean success,
        int status,
        String code,
        String message,
        Map<String, String> errors,
        String path,
        Instant timestamp
) {
    public static ApiErrorResponse of(int status, String code, String message, Map<String, String> errors, String path) {
        return new ApiErrorResponse(false, status, code != null ? code : defaultCode(status), message,
                errors == null || errors.isEmpty() ? null : errors, path, Instant.now());
    }

    /** Stable fallback code derived from the HTTP status when the thrower didn't pick a more specific one. */
    public static String defaultCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "UNPROCESSABLE";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "ERROR";
        };
    }
}
