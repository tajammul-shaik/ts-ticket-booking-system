package com.ts.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;

/**
 * BRD section 14 concurrency tests against real MySQL, through real HTTP. Every request in a
 * test waits on one start gate and then fires at the same moment.
 */
class ConcurrencyTest extends IntegrationTest {

    @Test
    void t01HotSeatHasExactlyOneWinner() {
        String showId = api.createShow(seats("A", 20), 25000, 4);
        String runId = UUID.randomUUID().toString().substring(0, 6);

        List<TestApi.Response> results = TestApi.concurrently(500,
                i -> api.reserve(api.userToken("hot-" + runId + "-" + i), showId, List.of("A12")));

        Map<String, Long> outcomes = outcomes(results);
        assertThat(outcomes).containsEntry("201", 1L).containsEntry("409 seat_taken", 499L).hasSize(2);
        String winner = results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().text("user_id");
        assertThat(jdbc.queryForObject("SELECT holder_user_id FROM seats WHERE show_id = ? AND seat_label = 'A12'",
                String.class, showId)).isEqualTo(winner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId))
                .isEqualTo(1);
    }

    @Test
    void t02ParallelRequestsCannotBreachPerUserLimit() {
        String showId = api.createShow(seats("A", 20), 100, 4);
        String token = api.userToken(TestApi.uniqueUser("greedy"));
        List<String> seats = seats("A", 10);

        List<TestApi.Response> results = TestApi.concurrently(10, i -> api.reserve(token, showId, List.of(seats.get(i))));

        assertThat(outcomes(results)).containsEntry("201", 4L).containsEntry("409 per_user_limit", 6L).hasSize(2);
        assertThat(api.seatStatuses(showId).values().stream().filter("confirmed"::equals).count()).isEqualTo(4);
    }

    @Test
    void t03ParallelRetriesWithOneKeyReserveOnce() {
        String showId = api.createShow(seats("A", 5), 100, 4);
        String token = api.userToken(TestApi.uniqueUser("retrier"));

        List<TestApi.Response> results = TestApi.concurrently(20,
                i -> api.reserve(token, showId, List.of("A1", "A2"), "one-intent"));

        assertThat(outcomes(results)).containsEntry("201", 1L).containsEntry("200", 19L).hasSize(2);
        assertThat(results.stream().map(r -> r.text("reservation_id")).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() == 200).map(r -> r.header("Idempotent-Replay")))
                .containsOnly("true");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class, showId))
                .isEqualTo(1);
    }

    @Test
    void t06OppositeSeatOrdersNeverDeadlock() {
        int rounds = 200;
        List<String> labels = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            labels.add(String.format("P%03dA", round));
            labels.add(String.format("P%03dB", round));
        }
        String showId = api.createShow(labels, 100, 4);
        String runId = UUID.randomUUID().toString().substring(0, 6);
        double retriesBefore = api.metric("reservations_lock_retries_total");

        for (int round = 0; round < rounds; round++) {
            String a = String.format("P%03dA", round);
            String b = String.format("P%03dB", round);
            int r = round;
            // Four buyers per pair: two ask for [A, B], two for [B, A].
            List<TestApi.Response> results = TestApi.concurrently(4, i -> api.reserve(
                    api.userToken("dl-" + runId + "-" + r + "-" + i), showId, i % 2 == 0 ? List.of(a, b) : List.of(b, a)));

            assertThat(outcomes(results)).as("round %d", round)
                    .containsEntry("201", 1L).containsEntry("409 seat_taken", 3L).hasSize(2);
        }
        assertThat(api.metric("reservations_lock_retries_total") - retriesBefore)
                .as("deadlocks / lock wait timeouts that needed a retry").isZero();
    }

    /** A small stampede: random 1–3 seat requests weighted to hot seats, from users who also retry. */
    @Test
    void mixedStampedeStaysConsistent() {
        List<String> labels = seats("S", 50);
        String showId = api.createShow(labels, 1000, 3);
        String runId = UUID.randomUUID().toString().substring(0, 6);
        Random random = new Random(42);
        List<List<String>> requests = new ArrayList<>();
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            Set<String> wanted = new HashSet<>();
            int n = 1 + random.nextInt(3);
            while (wanted.size() < n) {
                wanted.add(random.nextInt(4) == 0 ? "S" + (1 + random.nextInt(3)) : labels.get(random.nextInt(labels.size())));
            }
            requests.add(new ArrayList<>(wanted));
            users.add("st-" + runId + "-" + random.nextInt(200));
        }

        List<TestApi.Response> results = TestApi.concurrently(requests.size(),
                i -> api.reserve(api.userToken(users.get(i)), showId, requests.get(i)));

        Map<String, Long> outcomes = outcomes(results);
        assertThat(outcomes.keySet()).as("only 201, clean 409 declines or 429 shedding: %s", outcomes)
                .allMatch(k -> k.equals("201") || k.equals("409 seat_taken") || k.equals("409 per_user_limit")
                        || k.equals("429 overloaded"));
        // Client-side view: no seat appears in two successful responses (no double-sell) ...
        List<String> sold = new ArrayList<>();
        results.stream().filter(r -> r.status() == 201).forEach(r -> r.body().path("seats").forEach(n -> sold.add(n.asText())));
        assertThat(sold).doesNotHaveDuplicates();
        // ... and it matches the server's count exactly.
        Map<String, String> statuses = api.seatStatuses(showId);
        assertThat(statuses.values().stream().filter("confirmed"::equals).count()).isEqualTo(sold.size());
    }

    private static List<String> seats(String row, int count) {
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(row + i);
        }
        return seats;
    }

    /** "201", "200", or "409 seat_taken" style keys → count. */
    private static Map<String, Long> outcomes(List<TestApi.Response> results) {
        return results.stream().collect(Collectors.groupingBy(
                r -> r.status() < 300 ? String.valueOf(r.status()) : r.status() + " " + r.text("error"),
                HashMap::new, Collectors.counting()));
    }
}
