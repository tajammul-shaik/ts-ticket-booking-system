package com.ts.ticketing.reservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The atomic decisions, as explicit SQL (BRD section 10, NFR-01, NFR-14). Every conditional
 * UPDATE reports its update count, because MySQL has no RETURNING; the service decides on
 * that count, never on a value it read earlier.
 */
@Repository
class ReservationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Stored> storedMapper;

    ReservationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.storedMapper = (rs, n) -> new Stored(
                new Reservation(rs.getString("id"), rs.getString("show_id"), rs.getString("user_id"),
                        readSeats(rs.getString("seats")), rs.getLong("amount_paise"), rs.getString("status")),
                rs.getString("request_hash"));
    }

    /** A committed reservation together with the hash of the request that created it. */
    record Stored(Reservation reservation, String requestHash) {
    }

    // ---- reserve ------------------------------------------------------------------------

    /**
     * Fast-path read, outside any transaction: how many of the seats are not available in the
     * latest committed state. Non-locking, so it never queues behind a hot seat's row lock. It is
     * only ever used to DECLINE early; a grant is decided by {@link #takeSeats} under row locks.
     */
    int countUnavailable(String showId, List<String> seats) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND seat_label IN (" + placeholders(seats)
                        + ") AND status <> 'available'",
                Integer.class, args(showId, seats));
    }

    /** 1. Claim the idempotency key. A second claim of (user_id, key) fails with 1062 → DuplicateKeyException. */
    void insert(String id, String showId, String userId, String key, String requestHash, List<String> seats,
            long amountPaise) {
        jdbc.update("""
                INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'confirmed')""",
                id, showId, userId, key, requestHash, writeSeats(seats), amountPaise);
    }

    /** 2a. Make sure the user's counter row exists; this also row-locks it for the rest of the transaction. */
    void ensureCountRow(String showId, String userId) {
        jdbc.update("""
                INSERT INTO user_show_counts (show_id, user_id, held) VALUES (?, ?, 0)
                ON DUPLICATE KEY UPDATE held = held""", showId, userId);
    }

    /** 2b. The per-user limit decision: 0 rows updated means over the limit. */
    int addHeld(String showId, String userId, int seats, int limit) {
        return jdbc.update("""
                UPDATE user_show_counts SET held = held + ?
                WHERE show_id = ? AND user_id = ? AND held + ? <= ?""",
                seats, showId, userId, seats, limit);
    }

    /** 3a. Lock the seat rows in ascending label order (NFR-02), so two requests never wait on each other in a cycle. */
    List<String> lockSeats(String showId, List<String> sortedSeats) {
        return jdbc.queryForList(
                "SELECT seat_label FROM seats WHERE show_id = ? AND seat_label IN (" + placeholders(sortedSeats)
                        + ") ORDER BY seat_label FOR UPDATE",
                String.class, args(showId, sortedSeats));
    }

    /** 3b. The seat decision: only rows still 'available' change. Fewer than requested means a seat was taken. */
    int takeSeats(String showId, List<String> sortedSeats, String userId, String reservationId) {
        List<Object> args = new ArrayList<>(List.of(userId, reservationId, showId));
        args.addAll(sortedSeats);
        return jdbc.update(
                "UPDATE seats SET status = 'confirmed', holder_user_id = ?, reservation_id = ? "
                        + "WHERE show_id = ? AND seat_label IN (" + placeholders(sortedSeats) + ") AND status = 'available'",
                args.toArray());
    }

    /** Replay lookup after a 1062. READ COMMITTED: sees the row the other transaction just committed. */
    Optional<Stored> findByUserAndKey(String userId, String key) {
        return jdbc.query("""
                SELECT id, show_id, user_id, request_hash, seats, amount_paise, status
                FROM reservations WHERE user_id = ? AND idempotency_key = ?""",
                storedMapper, userId, key).stream().findFirst();
    }

    // ---- cancel -------------------------------------------------------------------------

    /** The cancel decision: only the owner's still-confirmed reservation changes. Locks the row. */
    int markCancelled(String reservationId, String userId) {
        return jdbc.update("""
                UPDATE reservations SET status = 'cancelled', cancelled_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND user_id = ? AND status = 'confirmed'""", reservationId, userId);
    }

    /** Owner-scoped lookup: another user's reservation is indistinguishable from a missing one. */
    Optional<Stored> findOwned(String reservationId, String userId) {
        return jdbc.query("""
                SELECT id, show_id, user_id, request_hash, seats, amount_paise, status
                FROM reservations WHERE id = ? AND user_id = ?""",
                storedMapper, reservationId, userId).stream().findFirst();
    }

    /** Return the user's limit capacity (BR-11). The CHECK (held >= 0) makes an underflow impossible. */
    int releaseHeld(String showId, String userId, int seats) {
        return jdbc.update("""
                UPDATE user_show_counts SET held = held - ?
                WHERE show_id = ? AND user_id = ? AND held >= ?""", seats, showId, userId, seats);
    }

    /**
     * Free only seats still owned by this reservation (BR-10): a seat re-sold to someone else
     * has another reservation_id and is untouched. Rows are matched by primary key in label order.
     */
    int freeSeats(String showId, List<String> sortedSeats, String reservationId) {
        List<Object> args = new ArrayList<>(List.of(showId));
        args.addAll(sortedSeats);
        args.add(reservationId);
        return jdbc.update(
                "UPDATE seats SET status = 'available', holder_user_id = NULL, reservation_id = NULL "
                        + "WHERE show_id = ? AND seat_label IN (" + placeholders(sortedSeats) + ") AND reservation_id = ?",
                args.toArray());
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String placeholders(List<String> values) {
        return String.join(", ", Collections.nCopies(values.size(), "?"));
    }

    private static Object[] args(String first, List<String> rest) {
        List<Object> args = new ArrayList<>(rest.size() + 1);
        args.add(first);
        args.addAll(rest);
        return args.toArray();
    }

    private String writeSeats(List<String> seats) {
        try {
            return json.writeValueAsString(seats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readSeats(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt seats JSON", e);
        }
    }
}
