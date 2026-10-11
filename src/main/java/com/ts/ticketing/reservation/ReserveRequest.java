package com.ts.ticketing.reservation;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.ts.ticketing.show.SeatLabels;

/**
 * FR-03 body. There is deliberately no user_id field: identity comes only from the token
 * (BR-08), and Jackson ignores unknown fields, so a spoofed user_id is simply dropped.
 * The idempotency key may instead come in the Idempotency-Key header.
 */
public record ReserveRequest(
        @NotEmpty @Size(max = 100)
        List<@NotNull @Pattern(regexp = SeatLabels.PATTERN, message = SeatLabels.PATTERN_MESSAGE) String> seats,
        @Size(min = 1, max = 128) String idempotencyKey) {
}
