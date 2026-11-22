package com.oorja.backend.exception;

import com.oorja.backend.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single place that turns every exception into the {@link ApiErrorResponse} envelope.
 * <p>
 * Extending {@link ResponseEntityExceptionHandler} routes all of Spring MVC's own failures
 * (malformed JSON, missing parameter/part, wrong HTTP method, unsupported media type,
 * unknown route, type mismatch, ...) through {@link #handleExceptionInternal}, so they get the
 * same envelope instead of Spring's default ProblemDetail / HTML error page.
 * <p>
 * Rules this class enforces:
 * <ul>
 *   <li>4xx messages are written for the caller; 5xx messages are always generic — the real
 *       exception goes to the log, never to the client (it can contain SQL or schema detail).</li>
 *   <li>Only a unique/foreign-key violation is a 409 ("someone else got there first"). NOT NULL /
 *       CHECK violations mean the application tried to write something the schema forbids — a bug
 *       — and are logged loudly and returned as 500.</li>
 *   <li>Lock timeouts, deadlock victims, serialization failures and stale-version writes are
 *       transient: 409 + {@code Retry-After}, because retrying the request is the correct response.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------
    // Application errors
    // ------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus().value(), ex.getCode(), ex.getMessage(), null, request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** {@code @Valid @RequestBody} failed: one entry per offending field, plus class-level (cross-field) rules. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
            errors.putIfAbsent(oe.getObjectName(), oe.getDefaultMessage());
        }
        return asObject(build(400, "VALIDATION_FAILED", "Validation failed", errors, pathOf(request), null));
    }

    /** {@code @Validated} on a controller/service parameter (path variable, request param, ...). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                      HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            // "method.arg0" -> "arg0": the method name is noise to a client.
            int dot = path.lastIndexOf('.');
            errors.putIfAbsent(dot >= 0 ? path.substring(dot + 1) : path, v.getMessage());
        }
        return build(400, "VALIDATION_FAILED", "Validation failed", errors, request.getRequestURI(), null);
    }

    /** Body is not JSON, has the wrong shape, or an enum/date/number can't be parsed. Never echoes parser detail. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return asObject(build(400, "MALFORMED_REQUEST",
                "The request body is missing or malformed. Check the JSON and the field formats (dates are yyyy-MM-dd, times HH:mm).",
                null, pathOf(request), null));
    }

    /**
     * Upload exceeded the multipart limit. Overrides the {@link ResponseEntityExceptionHandler} hook instead of
     * declaring a second {@code @ExceptionHandler}: the parent already maps this exception, so a separate
     * annotated method makes the mapping ambiguous and the context fails to initialise.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return asObject(build(413, "PAYLOAD_TOO_LARGE", "These files are too large to upload.", null, pathOf(request), null));
    }

    // ------------------------------------------------------------------
    // Security (reached only when thrown from inside a controller/service;
    // filter-level rejections are handled by RestAuthHandlers)
    // ------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(403, "FORBIDDEN", "You don't have permission to do that.", null, request.getRequestURI(), null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(401, "UNAUTHENTICATED", "Please log in to continue.", null, request.getRequestURI(), null);
    }

    // ------------------------------------------------------------------
    // Database / concurrency
    // ------------------------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                                         HttpServletRequest request) {
        org.hibernate.exception.ConstraintViolationException cve = findConstraintViolation(ex);
        String sqlState = cve != null ? cve.getSQLState() : null;
        String constraint = cve != null ? cve.getConstraintName() : null;

        if ("23505".equals(sqlState)) {
            log.warn("Unique violation on '{}' (likely a concurrent-request race)", constraint);
            return build(409, "DUPLICATE_REQUEST", messageForUniqueViolation(constraint), null, request.getRequestURI(), null);
        }
        if ("23503".equals(sqlState)) {
            log.warn("Foreign-key violation on '{}'", constraint);
            return build(409, "CONFLICT",
                    "This action conflicts with related records. Please refresh and try again.", null, request.getRequestURI(), null);
        }
        log.error("Database integrity violation that is NOT a benign race (sqlState={}, constraint={}) - this is a bug",
                sqlState, constraint, ex);
        return internalError(request.getRequestURI());
    }

    /**
     * Optimistic-lock conflicts, lock timeouts, deadlock victims and serialization failures all mean
     * "your request lost a race and nothing was committed" — safe for the client to simply retry.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleConcurrencyFailure(ConcurrencyFailureException ex,
                                                                     HttpServletRequest request) {
        log.warn("Concurrency failure ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        return build(409, "CONCURRENT_UPDATE",
                "Another request changed this at the same moment. Please try again.", null, request.getRequestURI(), 1);
    }

    /** Pool exhausted / DB unreachable / statement timed out: our side, temporary, retryable. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ApiErrorResponse> handleTransientDataAccess(Exception ex, HttpServletRequest request) {
        log.error("Transient database failure ({})", ex.getClass().getSimpleName(), ex);
        return build(503, "SERVICE_UNAVAILABLE",
                "We're a bit busy right now. Please try again in a moment.", null, request.getRequestURI(), 2);
    }

    // ------------------------------------------------------------------
    // Everything else
    // ------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        // Never echo ex.getMessage() to the client here - it can leak DB schema/internal details.
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return internalError(request.getRequestURI());
    }

    /** Funnel for Spring MVC's own exceptions (405, 415, 404, missing parameter, type mismatch, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        String message;
        if (status >= 500) {
            log.error("Framework error {} on {}", status, pathOf(request), ex);
            message = "Something went wrong. Please try again or contact support.";
        } else if (body instanceof ProblemDetail pd && pd.getDetail() != null) {
            message = pd.getDetail();
        } else {
            message = "The request could not be processed.";
        }
        ApiErrorResponse envelope = ApiErrorResponse.of(status, null, message, null, pathOf(request));
        return ResponseEntity.status(statusCode).headers(headers).<Object>body(envelope);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ResponseEntity<ApiErrorResponse> build(int status, String code, String message,
                                                          Map<String, String> errors, String path,
                                                          Integer retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return builder.body(ApiErrorResponse.of(status, code, message, errors, path));
    }

    private static ResponseEntity<ApiErrorResponse> internalError(String path) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                "Something went wrong. Please try again or contact support.", null, path, null);
    }

    private static ResponseEntity<Object> asObject(ResponseEntity<ApiErrorResponse> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).<Object>body(response.getBody());
    }

    private static String pathOf(WebRequest request) {
        String description = request.getDescription(false); // "uri=/api/..."
        return description != null && description.startsWith("uri=") ? description.substring(4) : description;
    }

    private static org.hibernate.exception.ConstraintViolationException findConstraintViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                return cve;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    /** Friendlier text for the unique rules a user can actually hit; never echoes DB internals. */
    private static String messageForUniqueViolation(String constraint) {
        if (constraint != null) {
            String c = constraint.toLowerCase();
            if (c.contains("email")) {
                return "An account already exists for this email. Please log in instead.";
            }
            if (c.contains("phone")) {
                return "An account already exists for this phone number.";
            }
            if (c.contains("quotation_open")) {
                return "A payment for this quotation is already in progress.";
            }
        }
        return "This action conflicts with another request that was processed just now. Please refresh and try again.";
    }
}
