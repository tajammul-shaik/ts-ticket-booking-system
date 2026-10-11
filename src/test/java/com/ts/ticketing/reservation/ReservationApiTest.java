package com.ts.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** FR-03 / FR-04 behaviour one request at a time; the races are in ConcurrencyTest. */
class ReservationApiTest extends IntegrationTest {

    private static final long PRICE = 25000;

    private String showId;

    @BeforeEach
    void newShow() {
        showId = api.createShow(List.of("A1", "A2", "A3", "A4", "A5", "A6", "A12", "A13"), PRICE, 4);
    }

    @Test
    void reservesSeatsWithServerComputedAmount() {
        String alice = TestApi.uniqueUser("alice");

        TestApi.Response res = api.reserve(api.userToken(alice), showId, List.of("A2", "A1"), "k1");

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.text("reservation_id")).isNotBlank();
        assertThat(res.text("show_id")).isEqualTo(showId);
        assertThat(res.text("user_id")).isEqualTo(alice);
        assertThat(res.body().path("seats").toString()).isEqualTo("[\"A1\",\"A2\"]");
        assertThat(res.body().path("amount_paise").isIntegralNumber()).isTrue();
        assertThat(res.body().path("amount_paise").asLong()).isEqualTo(2 * PRICE);
        assertThat(res.text("status")).isEqualTo("confirmed");
        assertThat(api.seatStatuses(showId)).containsEntry("A1", "confirmed").containsEntry("A2", "confirmed")
                .containsEntry("A3", "available");
    }

    @Test
    void sameKeySameBodyReplaysOriginalReservation() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        TestApi.Response first = api.reserve(token, showId, List.of("A1"), "same-key");

        TestApi.Response replay = api.reserve(token, showId, List.of("A1"), "same-key");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.header("Idempotent-Replay")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(rowsForKey("same-key")).isEqualTo(1);
    }

    @Test
    void t04SameKeyDifferentSeatsIsConflict() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        api.reserve(token, showId, List.of("A1"), "k");

        TestApi.Response res = api.reserve(token, showId, List.of("A2"), "k");

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.text("error")).isEqualTo("idempotency_key_conflict");
        assertThat(api.seatStatuses(showId)).containsEntry("A2", "available");
    }

    @Test
    void idempotencyKeyIsScopedPerUser() {
        assertThat(api.reserve(api.userToken(TestApi.uniqueUser("a")), showId, List.of("A1"), "shared").status()).isEqualTo(201);
        assertThat(api.reserve(api.userToken(TestApi.uniqueUser("b")), showId, List.of("A2"), "shared").status()).isEqualTo(201);
    }

    @Test
    void t05MultiSeatRequestIsAllOrNothing() {
        api.reserve(api.userToken(TestApi.uniqueUser("first")), showId, List.of("A13"));
        String user = TestApi.uniqueUser("second");

        TestApi.Response res = api.reserve(api.userToken(user), showId, List.of("A12", "A13"));

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.text("error")).isEqualTo("seat_taken");
        assertThat(api.seatStatuses(showId)).containsEntry("A12", "available");
        // The rollback also returned the limit capacity: all 4 are still usable.
        assertThat(api.reserve(api.userToken(user), showId, List.of("A1", "A2", "A3", "A4")).status()).isEqualTo(201);
    }

    @Test
    void declinedKeyCanBeRetriedFresh() {
        api.reserve(api.userToken(TestApi.uniqueUser("winner")), showId, List.of("A1"));
        String token = api.userToken(TestApi.uniqueUser("loser"));
        assertThat(api.reserve(token, showId, List.of("A1"), "retry-me").status()).isEqualTo(409);

        // Nothing was stored for the declined attempt, so the same key is evaluated fresh.
        assertThat(api.reserve(token, showId, List.of("A2"), "retry-me").status()).isEqualTo(201);
    }

    @Test
    void perUserLimitIsEnforced() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        assertThat(api.reserve(token, showId, List.of("A1", "A2", "A3")).status()).isEqualTo(201);

        TestApi.Response over = api.reserve(token, showId, List.of("A4", "A5"));
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.text("error")).isEqualTo("per_user_limit");

        assertThat(api.reserve(token, showId, List.of("A4")).status()).isEqualTo(201);
        assertThat(api.reserve(token, showId, List.of("A5")).text("error")).isEqualTo("per_user_limit");
        assertThat(api.seatStatuses(showId)).containsEntry("A5", "available");
    }

    @Test
    void requestLargerThanLimitIsDeclined() {
        TestApi.Response res = api.reserve(api.userToken(TestApi.uniqueUser("u")), showId,
                List.of("A1", "A2", "A3", "A4", "A5"));

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.text("error")).isEqualTo("per_user_limit");
    }

    @Test
    void t07UserIdInBodyIsIgnored() {
        String attacker = TestApi.uniqueUser("attacker");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seats", List.of("A1"));
        body.put("idempotency_key", "spoof");
        body.put("user_id", "victim");

        TestApi.Response res = api.post("/shows/" + showId + "/reserve", api.userToken(attacker), body, Map.of());

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.text("user_id")).isEqualTo(attacker);
        assertThat(jdbc.queryForObject("SELECT holder_user_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                String.class, showId)).isEqualTo(attacker);
    }

    @Test
    void idempotencyKeyMayComeFromHeader() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        Map<String, Object> body = Map.of("seats", List.of("A1"));

        TestApi.Response first = api.post("/shows/" + showId + "/reserve", token, body, Map.of("Idempotency-Key", "hk"));
        TestApi.Response again = api.post("/shows/" + showId + "/reserve", token, body, Map.of("Idempotency-Key", "hk"));

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("reservation_id")).isEqualTo(first.text("reservation_id"));
    }

    @Test
    void bodyAndHeaderKeysMustAgree() {
        Map<String, Object> body = Map.of("seats", List.of("A1"), "idempotency_key", "body-key");

        TestApi.Response res = api.post("/shows/" + showId + "/reserve", api.userToken("u"), body,
                Map.of("Idempotency-Key", "header-key"));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("error")).isEqualTo("invalid_request");
    }

    @Test
    void rejectsMalformedReserveRequests() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        Map<String, Object> noKey = Map.of("seats", List.of("A1"));
        Map<String, Object> noSeats = Map.of("seats", List.of(), "idempotency_key", "k");
        Map<String, Object> duplicateSeats = Map.of("seats", List.of("A1", "A1"), "idempotency_key", "k");

        for (Map<String, Object> body : List.of(noKey, noSeats, duplicateSeats)) {
            TestApi.Response res = api.post("/shows/" + showId + "/reserve", token, body, Map.of());
            assertThat(res.status()).as(body.toString()).isEqualTo(400);
            assertThat(res.text("error")).as(body.toString()).isEqualTo("invalid_request");
        }
        assertThat(api.post("/shows/" + showId + "/reserve", token, "{oops", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void unknownSeatIsInvalidSeat() {
        TestApi.Response res = api.reserve(api.userToken("u"), showId, List.of("A1", "Z9"));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("error")).isEqualTo("invalid_seat");
        assertThat(api.seatStatuses(showId)).containsEntry("A1", "available");
    }

    @Test
    void unknownShowIs404() {
        TestApi.Response res = api.reserve(api.userToken("u"), "missing", List.of("A1"));

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.text("error")).isEqualTo("show_not_found");
    }

    @Test
    void reserveNeedsToken() {
        assertThat(api.reserve(null, showId, List.of("A1")).status()).isEqualTo(401);
    }

    // ---- cancel (FR-04) -----------------------------------------------------------------

    @Test
    void ownerCancelFreesSeatsAndIsIdempotent() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        String id = api.reserve(token, showId, List.of("A1", "A2")).text("reservation_id");

        TestApi.Response cancelled = api.cancel(token, id);
        TestApi.Response again = api.cancel(token, id);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.text("status")).isEqualTo("cancelled");
        assertThat(cancelled.body().path("amount_paise").asLong()).isEqualTo(2 * PRICE);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(cancelled.body());
        assertThat(api.seatStatuses(showId)).containsEntry("A1", "available").containsEntry("A2", "available");
    }

    @Test
    void cancelReturnsLimitCapacityAndSeatsAreRebookable() {
        String token = api.userToken(TestApi.uniqueUser("u"));
        String id = api.reserve(token, showId, List.of("A1", "A2", "A3", "A4")).text("reservation_id");
        assertThat(api.reserve(token, showId, List.of("A5")).text("error")).isEqualTo("per_user_limit");

        api.cancel(token, id);

        assertThat(api.reserve(token, showId, List.of("A5", "A6")).status()).isEqualTo(201);
        assertThat(api.reserve(api.userToken(TestApi.uniqueUser("other")), showId, List.of("A1")).status()).isEqualTo(201);
    }

    @Test
    void t08OtherUserCannotCancel() {
        String id = api.reserve(api.userToken(TestApi.uniqueUser("owner")), showId, List.of("A1")).text("reservation_id");

        TestApi.Response res = api.cancel(api.userToken(TestApi.uniqueUser("intruder")), id);

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.text("error")).isEqualTo("reservation_not_found");
        assertThat(api.seatStatuses(showId)).containsEntry("A1", "confirmed");
    }

    @Test
    void t09CancelNeverResurrectsAReSoldSeat() {
        String alice = api.userToken(TestApi.uniqueUser("alice"));
        String bob = api.userToken(TestApi.uniqueUser("bob"));
        String aliceReservation = api.reserve(alice, showId, List.of("A1")).text("reservation_id");
        api.cancel(alice, aliceReservation);
        String bobReservation = api.reserve(bob, showId, List.of("A1")).text("reservation_id");

        TestApi.Response replay = api.cancel(alice, aliceReservation);

        assertThat(replay.status()).isEqualTo(200);
        assertThat(api.seatStatuses(showId)).containsEntry("A1", "confirmed");
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
                String.class, showId)).isEqualTo(bobReservation);
    }

    @Test
    void unknownReservationIs404() {
        TestApi.Response res = api.cancel(api.userToken("u"), "no-such-reservation");

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.text("error")).isEqualTo("reservation_not_found");
    }

    private int rowsForKey(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE idempotency_key = ? AND show_id = ?",
                Integer.class, key, showId);
    }
}
