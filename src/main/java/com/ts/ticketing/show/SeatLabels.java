package com.ts.ticketing.show;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.ts.ticketing.api.ApiException;

/** Seat label rules shared by show creation and reservations. */
public final class SeatLabels {

    /** 1–8 characters (FR-02, seats.seat_label VARCHAR(8)); a safe charset keeps labels loggable. */
    public static final String PATTERN = "[A-Za-z0-9_-]{1,8}";
    public static final String PATTERN_MESSAGE = "must be 1-8 characters of A-Z a-z 0-9 _ -";

    private SeatLabels() {
    }

    /**
     * Returns the labels in ascending order, the order seat rows are locked in (NFR-02).
     * Duplicates are a client error, not something to silently collapse.
     */
    public static List<String> sortedUnique(List<String> labels) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (String label : labels) {
            if (!seen.add(label)) {
                duplicates.add(label);
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiException.badRequest("invalid_request", "duplicate seat labels: " + String.join(", ", duplicates));
        }
        List<String> sorted = new ArrayList<>(labels);
        sorted.sort(null); // String order = utf8mb4_bin order for this ASCII charset
        return sorted;
    }
}
