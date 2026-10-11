package com.ts.ticketing.show;

import java.util.List;

/** FR-05 response. Holder user ids are deliberately not exposed. */
public record ShowView(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Counts counts,
        boolean invariantOk,
        List<SeatState> seats) {

    public record Counts(long available, long held, long confirmed) {
    }

    public record SeatState(String label, String status) {
    }
}
