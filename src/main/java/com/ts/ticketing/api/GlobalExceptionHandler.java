package com.ts.ticketing.api;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to an {@link ErrorResponse} (NFR-03): request-shape problems are 400,
 * domain declines carry their own 4xx, database contention is 409/429. Only a genuine bug
 * reaches the 500 catch-all, and it is logged with its stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> domain(ApiException e) {
        return ResponseEntity.status(e.status()).body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> invalidParameter(HandlerMethodValidationException e) {
        return badRequest("invalid request parameter");
    }

    /** Malformed JSON, and money sent as a float or a string (coercion is disabled in application.yml). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return badRequest("malformed JSON body or wrong field type");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noRoute(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("not_found", "no such endpoint"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("method_not_allowed", e.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> wrongMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of("unsupported_media_type", "send Content-Type: application/json"));
    }

    /** No pool connection within the wait timeout: shed load with 429, never 500 (R-02). */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<ErrorResponse> poolExhausted(CannotGetJdbcConnectionException e) {
        log.warn("no database connection available: {}", e.getMostSpecificCause().getMessage());
        return overloaded();
    }

    /** Deadlock / lock wait timeout that escaped the service's retries. */
    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> lockContention(RuntimeException e) {
        log.warn("lock contention: {}", e.getMessage());
        return overloaded();
    }

    /** A constraint fired that no service anticipated: still a conflict, not a server fault (NFR-03). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> constraint(DataIntegrityViolationException e) {
        log.warn("constraint violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("conflict", "request conflicts with current state"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        // Spring MVC's own client errors (missing header, bad path variable type, ...) keep their 4xx.
        if (e instanceof org.springframework.web.ErrorResponse mvc && mvc.getStatusCode().is4xxClientError()) {
            return ResponseEntity.status(mvc.getStatusCode()).body(ErrorResponse.of("invalid_request", e.getMessage()));
        }
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "unexpected server error"));
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("invalid_request", message));
    }

    private static ResponseEntity<ErrorResponse> overloaded() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("overloaded", "server is busy, retry shortly"));
    }
}
