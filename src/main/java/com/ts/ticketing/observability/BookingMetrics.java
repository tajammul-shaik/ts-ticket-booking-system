package com.ts.ticketing.observability;

import java.util.List;
import java.util.function.IntSupplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Domain counters from BRD section 13. Callers increment them only after the transaction has
 * committed (or rolled back, for declines), so they never count work that didn't happen and
 * reconcile exactly with the database (R-05). Counters are in memory: per instance, reset on restart.
 */
@Component
public class BookingMetrics {

    public static final String REASON_SEAT_TAKEN = "seat_taken";
    public static final String REASON_PER_USER_LIMIT = "per_user_limit";
    public static final String REASON_IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String REASON_KEY_CONFLICT = "idempotency_key_conflict";
    public static final String REASON_OVERLOADED = "overloaded";

    private static final List<String> REASONS = List.of(REASON_SEAT_TAKEN, REASON_PER_USER_LIMIT,
            REASON_IDEMPOTENT_REPLAY, REASON_KEY_CONFLICT, REASON_OVERLOADED);

    private final MeterRegistry registry;
    private final Counter lockRetries;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Register every reason up front, so each series exists (at 0) before the first decline.
        REASONS.forEach(this::declinedCounter);
        this.lockRetries = Counter.builder("reservations.lock.retries")
                .description("Transactions retried after a deadlock (1213) or lock wait timeout (1205)")
                .register(registry);
    }

    /** reservations_confirmed_total{show_id}: a reservation committed. */
    public void confirmed(String showId) {
        Counter.builder("reservations.confirmed")
                .description("Reservations committed")
                .tag("show_id", showId)
                .register(registry)
                .increment();
    }

    /** reservations_cancelled_total{show_id}: a cancel committed (a repeated cancel is not counted). */
    public void cancelled(String showId) {
        Counter.builder("reservations.cancelled")
                .description("Reservations cancelled")
                .tag("show_id", showId)
                .register(registry)
                .increment();
    }

    /** reservations_declined_total{reason}. */
    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    public void lockRetry() {
        lockRetries.increment();
    }

    /** admission_in_flight / admission_waiting: reserve calls holding a slot / queued for one. */
    void registerAdmissionGauges(int maxInFlight, IntSupplier availableSlots, IntSupplier waiting) {
        Gauge.builder("admission.in_flight", () -> maxInFlight - availableSlots.getAsInt())
                .description("Reserve calls currently admitted")
                .register(registry);
        Gauge.builder("admission.waiting", waiting::getAsInt)
                .description("Reserve calls waiting for an admission slot")
                .register(registry);
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined")
                .description("Reserve requests that ended without a new reservation, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}
