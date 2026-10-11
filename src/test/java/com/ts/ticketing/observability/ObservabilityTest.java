package com.ts.ticketing.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;

/** NFR-13 request ids and FR-07 metrics that reconcile with the database. */
class ObservabilityTest extends IntegrationTest {

    @Test
    void echoesClientRequestIdInHeaderAndErrorBody() {
        TestApi.Response res = api.post("/shows/missing/reserve", api.userToken("u"),
                Map.of("seats", List.of("A1"), "idempotency_key", "k"), Map.of("X-Request-Id", "trace-123"));

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.header("X-Request-Id")).isEqualTo("trace-123");
        assertThat(res.text("request_id")).isEqualTo("trace-123");
    }

    @Test
    void generatesRequestIdWhenAbsentOrUnsafe() {
        TestApi.Response none = api.getShow(null, "x");                         // 401 from the security chain
        TestApi.Response unsafe = api.post("/auth/token", null, "{}", Map.of("X-Request-Id", "bad id\twith spaces"));

        assertThat(none.header("X-Request-Id")).matches("[0-9a-f-]{36}");
        assertThat(none.text("request_id")).isEqualTo(none.header("X-Request-Id"));
        assertThat(unsafe.header("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void countersAndGaugesReconcileWithTheDatabase() {
        String showId = api.createShow(List.of("A1", "A2", "A3", "A4"), 500, 4);
        double seatTakenBefore = api.metric("reservations_declined_total", "reason", "seat_taken");
        double replayBefore = api.metric("reservations_declined_total", "reason", "idempotent_replay");
        String alice = api.userToken(TestApi.uniqueUser("alice"));
        String bob = api.userToken(TestApi.uniqueUser("bob"));

        String first = api.reserve(alice, showId, List.of("A1", "A2"), "m1").text("reservation_id");
        api.reserve(alice, showId, List.of("A1", "A2"), "m1");                 // replay
        api.reserve(bob, showId, List.of("A2"));                                // seat_taken
        api.reserve(bob, showId, List.of("A3"));
        api.cancel(alice, first);
        api.cancel(alice, first);                                                // repeated cancel: not counted again

        assertThat(api.metric("reservations_confirmed_total", "show_id", showId)).isEqualTo(2);
        assertThat(api.metric("reservations_cancelled_total", "show_id", showId)).isEqualTo(1);
        assertThat(api.metric("reservations_declined_total", "reason", "seat_taken") - seatTakenBefore).isEqualTo(1);
        assertThat(api.metric("reservations_declined_total", "reason", "idempotent_replay") - replayBefore).isEqualTo(1);

        TestApi.Response show = api.getShow(bob, showId);
        assertThat(api.metric("seats_confirmed", "show_id", showId))
                .isEqualTo(show.body().path("counts").path("confirmed").asDouble()).isEqualTo(1);
        assertThat(api.metric("seats_available", "show_id", showId))
                .isEqualTo(show.body().path("counts").path("available").asDouble()).isEqualTo(3);
        assertThat(api.metric("seats_held", "show_id", showId)).isZero();
    }

    @Test
    void exposesPoolAndHttpMetrics() {
        api.getShow(api.userToken("u"), "x");

        String metrics = api.metrics();
        assertThat(metrics).contains("hikaricp_connections_pending", "http_server_requests_seconds_bucket",
                "admission_in_flight", "reservations_declined_total{reason=\"overloaded\"}");
    }
}
