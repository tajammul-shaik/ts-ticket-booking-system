package com.ts.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-10 and friends: database-wide consistency checks run after every integration test.
 * Each query lists violations, so a failure message names the offending rows.
 */
public final class Invariants {

    private Invariants() {
    }

    public static void assertHold(JdbcTemplate jdbc) {
        // BR-12: available + held + confirmed = total_seats for every show.
        assertThat(jdbc.queryForList("""
                SELECT sh.id FROM shows sh LEFT JOIN seats s ON s.show_id = sh.id
                GROUP BY sh.id, sh.total_seats
                HAVING SUM(s.status IN ('available', 'held', 'confirmed')) <> sh.total_seats""", String.class))
                .as("shows whose seat counts don't add up to total_seats").isEmpty();

        // BR-01/BR-10: a confirmed seat belongs to exactly one live reservation, of its holder.
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(s.show_id, '/', s.seat_label) FROM seats s
                LEFT JOIN reservations r ON r.id = s.reservation_id
                WHERE s.status = 'confirmed'
                  AND (r.id IS NULL OR r.status <> 'confirmed' OR r.user_id <> s.holder_user_id OR r.show_id <> s.show_id)""",
                String.class)).as("confirmed seats without a matching confirmed reservation").isEmpty();

        // An available seat carries no owner.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM seats
                WHERE status = 'available' AND (holder_user_id IS NOT NULL OR reservation_id IS NOT NULL)""",
                Long.class)).as("available seats with an owner").isZero();

        // Every confirmed reservation still owns all of its seats.
        assertThat(jdbc.queryForList("""
                SELECT r.id FROM reservations r
                LEFT JOIN seats s ON s.reservation_id = r.id AND s.status = 'confirmed'
                WHERE r.status = 'confirmed'
                GROUP BY r.id, r.seats
                HAVING COUNT(s.seat_label) <> JSON_LENGTH(r.seats)""", String.class))
                .as("confirmed reservations missing seats").isEmpty();

        // BR-03: the limit counter equals the seats actually held, and never exceeds the limit.
        assertThat(jdbc.queryForList("""
                SELECT CONCAT(c.show_id, '/', c.user_id) FROM user_show_counts c
                JOIN shows sh ON sh.id = c.show_id
                LEFT JOIN seats s ON s.show_id = c.show_id AND s.holder_user_id = c.user_id AND s.status = 'confirmed'
                GROUP BY c.show_id, c.user_id, c.held, sh.per_user_limit
                HAVING c.held <> COUNT(s.seat_label) OR c.held > sh.per_user_limit""", String.class))
                .as("user_show_counts rows out of step with seats, or over the limit").isEmpty();

        // BR-14: amount = seats × price for every reservation.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservations r JOIN shows sh ON sh.id = r.show_id
                WHERE r.amount_paise <> JSON_LENGTH(r.seats) * sh.price_paise""", Long.class))
                .as("reservations with a wrong amount").isZero();
    }
}
