package com.ts.ticketing.api;

import com.ts.ticketing.web.RequestIdFilter;

import org.slf4j.MDC;


/**
 * Body of every non-2xx response, filters included (BRD section 12):
 * {error, message, request_id}.
 */
public record ErrorResponse(String error, String message, String requestId) {

    /** Builds the body for the current request; the request id comes from the MDC set by {@link RequestIdFilter}. */
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
