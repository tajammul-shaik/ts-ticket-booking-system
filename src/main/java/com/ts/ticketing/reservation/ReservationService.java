package com.ts.ticketing.reservation;

import java.util.List;
import java.util.UUID;

import com.ts.ticketing.api.ApiException;
import com.ts.ticketing.observability.BookingMetrics;
import com.ts.ticketing.show.SeatLabels;
import com.ts.ticketing.show.Show;
import com.ts.ticketing.show.ShowService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * FR-03 reserve and FR-04 cancel. Each is ONE database transaction (READ COMMITTED) whose every
 * exit is either COMMIT (success) or ROLLBACK followed by a clean 4xx. All decisions are
 * conditional SQL in {@link ReservationRepository}; this class only reads their update counts.
 *
 * <p>Metrics are recorded after the transaction ends: confirmed/cancelled after COMMIT,
 * declines after ROLLBACK (R-05).
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowService shows;
    private final ReservationRepository repo;
    private final TransactionTemplate tx;
    private final LockRetry retry;
    private final BookingMetrics metrics;

    public ReservationService(ShowService shows, ReservationRepository repo, TransactionTemplate tx,
            LockRetry retry, BookingMetrics metrics) {
        this.shows = shows;
        this.repo = repo;
        this.tx = tx;
        this.retry = retry;
        this.metrics = metrics;
    }

    /** @param replay true when the idempotency key was already used for this exact request */
    public record ReserveResult(Reservation reservation, boolean replay) {
    }

    public ReserveResult reserve(String userId, String showId, List<String> requestedSeats, String idempotencyKey) {
        long start = System.nanoTime();
        // Validation against the show's definition. Labels never change once a show exists, so
        // this read is not a decision on mutable state (that happens in SQL below).
        Show show = shows.definition(showId);                                            // 404 show_not_found
        List<String> seats = SeatLabels.sortedUnique(requestedSeats);                    // 400 duplicates
        List<String> unknown = seats.stream().filter(s -> !show.seatLabels().contains(s)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("invalid_seat", "not seats of this show: " + String.join(", ", unknown));
        }
        String requestHash = RequestHash.of(showId, seats);
        long amountPaise = Math.multiplyExact(show.pricePaise(), seats.size());         // BR-14, server-side

        try {
            // Fast decline (most requests in a stampede): a seat already taken in the committed
            // state can only produce seat_taken, so answer without a transaction and without
            // queueing on that seat's row lock. A same-key retry of a request that already
            // succeeded also sees its seats taken, so the key is checked first: replays stay 200.
            if (repo.countUnavailable(showId, seats) > 0) {
                var stored = repo.findByUserAndKey(userId, idempotencyKey);
                if (stored.isPresent()) {
                    return replayOrConflict(stored.get(), userId, showId, seats, requestHash, start);
                }
                throw decline(new Decline(BookingMetrics.REASON_SEAT_TAKEN,
                        "one or more requested seats are not available"), userId, showId, seats, start);
            }
            Reservation reservation = retry.run(() -> tx.execute(status ->
                    claim(show, userId, idempotencyKey, requestHash, seats, amountPaise)));
            metrics.confirmed(showId);
            logOutcome(userId, showId, seats, "confirmed", null, start);
            return new ReserveResult(reservation, false);
        } catch (DuplicateKeyException e) {
            // The key was used before (or by a concurrent twin that committed first). Rolled back.
            return replayOrConflict(storedFor(userId, idempotencyKey, showId, seats, start), userId, showId, seats,
                    requestHash, start);
        } catch (Decline d) {
            throw decline(d, userId, showId, seats, start);
        } catch (DataAccessException e) {
            throw overloadedOrRethrow(e, userId, showId, seats, start);
        }
    }

    private ApiException decline(Decline d, String userId, String showId, List<String> seats, long start) {
        metrics.declined(d.reason);
        logOutcome(userId, showId, seats, "declined", d.reason, start);
        return ApiException.conflict(d.reason, d.getMessage());
    }

    /** The reserve transaction (BRD section 11). Throws to roll back. */
    private Reservation claim(Show show, String userId, String key, String requestHash, List<String> seats,
            long amountPaise) {
        String id = UUID.randomUUID().toString();
        // 1. Claim the idempotency key first: a concurrent twin with the same key blocks here
        //    on the unique index until this transaction ends.
        repo.insert(id, show.id(), userId, key, requestHash, seats, amountPaise);
        // 2. Per-user limit, before any seat is locked: once the seat update succeeds, nothing can fail.
        repo.ensureCountRow(show.id(), userId);
        if (repo.addHeld(show.id(), userId, seats.size(), show.perUserLimit()) == 0) {
            throw new Decline(BookingMetrics.REASON_PER_USER_LIMIT,
                    "would exceed the limit of " + show.perUserLimit() + " seats per user for this show");
        }
        // 3. Lock the seat rows in ascending label order, then take only those still available.
        repo.lockSeats(show.id(), seats);
        int taken = repo.takeSeats(show.id(), seats, userId, id);
        if (taken < seats.size()) {
            // All-or-nothing (BR-07): the rollback also undoes steps 1 and 2.
            throw new Decline(BookingMetrics.REASON_SEAT_TAKEN, "one or more requested seats are not available");
        }
        return new Reservation(id, show.id(), userId, seats, amountPaise, "confirmed");
    }

    /** After a 1062 the row is committed (InnoDB reports the duplicate only once the other transaction commits). */
    private ReservationRepository.Stored storedFor(String userId, String key, String showId, List<String> seats,
            long start) {
        try {
            return repo.findByUserAndKey(userId, key)
                    .orElseThrow(() -> new IllegalStateException("duplicate idempotency key but no stored reservation"));
        } catch (DataAccessException e) {
            throw overloadedOrRethrow(e, userId, showId, seats, start);
        }
    }

    /** Same key: same request → the original reservation (200 replay); different request → 409. */
    private ReserveResult replayOrConflict(ReservationRepository.Stored stored, String userId, String showId,
            List<String> seats, String requestHash, long start) {
        if (stored.requestHash().equals(requestHash)) {
            metrics.declined(BookingMetrics.REASON_IDEMPOTENT_REPLAY);
            logOutcome(userId, showId, seats, "replayed", BookingMetrics.REASON_IDEMPOTENT_REPLAY, start);
            return new ReserveResult(stored.reservation(), true);
        }
        metrics.declined(BookingMetrics.REASON_KEY_CONFLICT);
        logOutcome(userId, showId, seats, "declined", BookingMetrics.REASON_KEY_CONFLICT, start);
        throw ApiException.conflict(BookingMetrics.REASON_KEY_CONFLICT,
                "idempotency key was already used with a different request");
    }

    /** FR-04. Cancelling twice returns the same cancelled reservation (idempotent). */
    public Reservation cancel(String userId, String reservationId) {
        long start = System.nanoTime();
        try {
            CancelResult result = retry.run(() -> tx.execute(status -> cancelInTransaction(userId, reservationId)));
            Reservation r = result.reservation();
            if (result.changed()) {
                metrics.cancelled(r.showId());
            }
            logOutcome(userId, r.showId(), r.seats(), result.changed() ? "cancelled" : "cancel_replayed", null, start);
            return r;
        } catch (DataAccessException e) {
            throw overloadedOrRethrow(e, userId, null, List.of(), start);
        }
    }

    private record CancelResult(Reservation reservation, boolean changed) {
    }

    /** Lock order matches reserve: reservations row → user_show_counts row → seat rows (sorted). */
    private CancelResult cancelInTransaction(String userId, String reservationId) {
        if (repo.markCancelled(reservationId, userId) == 0) {
            // Already cancelled (replay), or not this user's / not existing (404, existence not revealed).
            return repo.findOwned(reservationId, userId)
                    .map(stored -> new CancelResult(stored.reservation(), false))
                    .orElseThrow(() -> ApiException.notFound("reservation_not_found", "no such reservation"));
        }
        Reservation r = repo.findOwned(reservationId, userId)
                .orElseThrow(() -> new IllegalStateException("cancelled row vanished"))
                .reservation();
        if (repo.releaseHeld(r.showId(), userId, r.seats().size()) != 1) {
            throw new IllegalStateException("user_show_counts out of step for reservation " + reservationId);
        }
        int freed = repo.freeSeats(r.showId(), r.seats(), reservationId);
        if (freed != r.seats().size()) {
            log.warn("cancel freed {} of {} seats {}", freed, r.seats().size(), kv("reservation_id", reservationId));
        }
        return new CancelResult(r, true);
    }

    /** Pool exhausted or lock retries used up: shed with 429, never 5xx (BR-15). Anything else is a real fault. */
    private RuntimeException overloadedOrRethrow(DataAccessException e, String userId, String showId,
            List<String> seats, long start) {
        if (e instanceof CannotGetJdbcConnectionException || LockRetry.isLockFailure(e)) {
            metrics.declined(BookingMetrics.REASON_OVERLOADED);
            logOutcome(userId, showId, seats, "declined", BookingMetrics.REASON_OVERLOADED, start);
            return ApiException.overloaded("server is busy, retry shortly");
        }
        return e;
    }

    private static void logOutcome(String userId, String showId, List<String> seats, String outcome, String reason,
            long start) {
        log.info("reservation {} {} {} {} {} {}", kv("user_id", userId), kv("show_id", showId), kv("seats", seats),
                kv("outcome", outcome), kv("reason", reason), kv("latency_ms", (System.nanoTime() - start) / 1_000_000));
    }

    /** A domain decline raised inside the transaction to force its rollback. */
    private static final class Decline extends RuntimeException {
        private final String reason;

        Decline(String reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }
}
