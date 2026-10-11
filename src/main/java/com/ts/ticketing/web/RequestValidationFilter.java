package com.ts.ticketing.web;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.ts.ticketing.api.JsonErrorWriter;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cheap request-shape checks before security and before any body is read, so malformed traffic
 * costs neither a JWT verification nor a database call. Body-level validation (seat lists,
 * prices, user ids) stays with the controllers, which own those rules:
 *
 * <ul>
 *   <li>Content-Length above app.edge.validation.max-body-bytes → 413 payload_too_large</li>
 *   <li>Idempotency-Key header longer than 128 chars or with control characters → 400</li>
 *   <li>a show or reservation id in the path that isn't [A-Za-z0-9-]{1,64} → 400</li>
 * </ul>
 *
 * A body with the wrong Content-Type is answered 415 by Spring MVC (GlobalExceptionHandler).
 */
public class RequestValidationFilter extends OncePerRequestFilter {

    static final int MAX_IDEMPOTENCY_KEY = 128;
    private static final Pattern ID_IN_PATH = Pattern.compile("^/(?:shows|reservations)/([^/]+)(?:/.*)?$");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final long maxBodyBytes;
    private final JsonErrorWriter errors;

    public RequestValidationFilter(EdgeProperties props, JsonErrorWriter errors) {
        this.maxBodyBytes = props.validation().maxBodyBytes();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            errors.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                    "request body exceeds " + maxBodyBytes + " bytes");
            return;
        }
        String key = request.getHeader("Idempotency-Key");
        if (key != null && (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY
                || key.chars().anyMatch(Character::isISOControl))) {
            errors.write(response, HttpStatus.BAD_REQUEST, "invalid_request",
                    "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY + " printable characters");
            return;
        }
        Matcher m = ID_IN_PATH.matcher(request.getRequestURI());
        if (m.matches() && !SAFE_ID.matcher(m.group(1)).matches()) {
            errors.write(response, HttpStatus.BAD_REQUEST, "invalid_request", "malformed id in path");
            return;
        }
        chain.doFilter(request, response);
    }
}
