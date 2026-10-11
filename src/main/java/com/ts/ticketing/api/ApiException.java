package com.ts.ticketing.api;

import org.springframework.http.HttpStatus;

/**
 * A domain outcome that ends the request with a stable 4xx error code (BR-15). Thrown after
 * the transaction has rolled back, so nothing it describes was persisted.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false); // declines are expected: no stack trace
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException overloaded(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "overloaded", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
