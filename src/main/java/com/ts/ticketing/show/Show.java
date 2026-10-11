package com.ts.ticketing.show;

import java.util.Set;

/**
 * A show's immutable definition. Nothing here ever changes after creation (seat labels
 * included; only seat *status* changes), which is why it is safe to cache.
 */
public record Show(String id, String name, long pricePaise, int perUserLimit, int totalSeats, Set<String> seatLabels) {
}
