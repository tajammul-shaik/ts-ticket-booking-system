package com.ts.ticketing.reservation;

import jakarta.validation.Valid;

import com.ts.ticketing.api.ApiException;
import com.ts.ticketing.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** FR-03 and FR-04. The acting user is always the token's subject (BR-08). */
@RestController
public class ReservationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final int MAX_KEY_LENGTH = 128;

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<Reservation> reserve(
            @PathVariable String showId,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey,
            @AuthenticationPrincipal AuthenticatedUser user) {
        String key = idempotencyKey(request.idempotencyKey(), headerKey);
        ReservationService.ReserveResult result = reservations.reserve(user.userId(), showId, request.seats(), key);
        if (result.replay()) {
            return ResponseEntity.ok().header(REPLAY_HEADER, "true").body(result.reservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation());
    }

    @PostMapping("/reservations/{id}/cancel")
    public Reservation cancel(@PathVariable String id, @AuthenticationPrincipal AuthenticatedUser user) {
        return reservations.cancel(user.userId(), id);
    }

    /** Body or header; if both are sent they must agree (FR-03). */
    private static String idempotencyKey(String bodyKey, String headerKey) {
        if (headerKey != null && (headerKey.isEmpty() || headerKey.length() > MAX_KEY_LENGTH)) {
            throw ApiException.badRequest("invalid_request", "Idempotency-Key must be 1-128 characters");
        }
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw ApiException.badRequest("invalid_request", "idempotency_key in body and Idempotency-Key header differ");
        }
        String key = bodyKey != null ? bodyKey : headerKey;
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("invalid_request", "idempotency_key is required (body or Idempotency-Key header)");
        }
        return key;
    }
}
