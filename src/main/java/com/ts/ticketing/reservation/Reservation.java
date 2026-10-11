package com.ts.ticketing.reservation;

import java.util.List;

/** FR-03 / FR-04 response body. Money is integer paise (BR-13). */
public record Reservation(
        String reservationId,
        String showId,
        String userId,
        List<String> seats,
        long amountPaise,
        String status) {
}
