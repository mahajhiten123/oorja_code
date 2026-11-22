package com.oorja.backend.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** Stable machine-readable code (e.g. SLOT_FULL); null means "derive from the status". */
    private final String code;

    public ApiException(String message, HttpStatus status) {
        this(message, status, null);
    }

    public ApiException(String message, HttpStatus status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(message, HttpStatus.BAD_REQUEST);
    }

    public static ApiException notFound(String message) {
        return new ApiException(message, HttpStatus.NOT_FOUND);
    }

    /**
     * "Who are you?" — missing/expired/invalid credentials. The frontend treats a 401 as
     * "session expired": it refreshes the token and, if that fails, logs the user out.
     * Never use this for "you're logged in but this isn't yours" — that is {@link #forbidden}.
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(message, HttpStatus.UNAUTHORIZED);
    }

    /** "I know who you are, and you may not do this" — e.g. someone else's booking. Must NOT log the user out. */
    public static ApiException forbidden(String message) {
        return new ApiException(message, HttpStatus.FORBIDDEN);
    }

    public static ApiException conflict(String message) {
        return new ApiException(message, HttpStatus.CONFLICT);
    }

    public static ApiException conflict(String message, String code) {
        return new ApiException(message, HttpStatus.CONFLICT, code);
    }

    /** The request is well-formed but the entity's current state doesn't allow it (e.g. completing a cancelled booking). */
    public static ApiException invalidState(String message) {
        return new ApiException(message, HttpStatus.CONFLICT, "INVALID_STATE");
    }

    public static ApiException unprocessable(String message, String code) {
        return new ApiException(message, HttpStatus.UNPROCESSABLE_ENTITY, code);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(message, HttpStatus.TOO_MANY_REQUESTS);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
