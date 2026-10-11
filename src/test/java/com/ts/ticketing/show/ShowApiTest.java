package com.ts.ticketing.show;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;

/** FR-02 create show and FR-05 show state. */
class ShowApiTest extends IntegrationTest {

    @Test
    void adminCreatesShowWithEverySeatAvailable() {
        TestApi.Response res = api.createShow(body("friday-night", List.of("B2", "A1", "A2"), 25000));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.header("Location")).isEqualTo("/shows/" + res.text("id"));
        assertThat(res.body().path("name").asText()).isEqualTo("friday-night");
        assertThat(res.body().path("price_paise").asLong()).isEqualTo(25000);
        assertThat(res.body().path("per_user_limit").asInt()).as("default limit").isEqualTo(4);
        assertThat(res.body().path("total_seats").asInt()).isEqualTo(3);
        assertThat(res.body().path("counts").path("available").asInt()).isEqualTo(3);
        assertThat(res.body().path("counts").path("confirmed").asInt()).isZero();
        assertThat(res.body().path("invariant_ok").asBoolean()).isTrue();
        assertThat(res.body().path("seats").findValuesAsText("label")).containsExactly("A1", "A2", "B2");
        assertThat(res.body().path("seats").findValuesAsText("status")).containsOnly("available");
    }

    @Test
    void anyUserCanReadShowState() {
        String showId = api.createShow(List.of("A1", "A2"), 100, 2);

        TestApi.Response res = api.getShow(api.userToken("u1"), showId);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("per_user_limit").asInt()).isEqualTo(2);
        assertThat(res.body().path("seats").get(0).has("holder_user_id")).as("holders are not exposed").isFalse();
    }

    @Test
    void unknownShowIs404() {
        TestApi.Response res = api.getShow(api.userToken("u1"), "no-such-show");

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.text("error")).isEqualTo("show_not_found");
    }

    @Test
    void readingShowNeedsToken() {
        String showId = api.createShow(List.of("A1"), 100, null);

        assertThat(api.getShow(null, showId).status()).isEqualTo(401);
    }

    @Test
    void nonAdminCannotCreateShow() {
        TestApi.Response res = api.post("/shows", api.userToken("u1"), body("x", List.of("A1"), 1), Map.of());

        assertThat(res.status()).isEqualTo(403);
        assertThat(res.text("error")).isEqualTo("forbidden");
    }

    @Test
    void rejectsInvalidShows() {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("duplicate labels", body("x", List.of("A1", "A1"), 100));
        cases.put("empty seat list", body("x", List.of(), 100));
        cases.put("label longer than 8", body("x", List.of("ABCDEFGHI"), 100));
        cases.put("label with a space", body("x", List.of("A 1"), 100));
        cases.put("negative price", body("x", List.of("A1"), -1));
        cases.put("blank name", body(" ", List.of("A1"), 100));
        Map<String, Object> zeroLimit = body("x", List.of("A1"), 100);
        zeroLimit.put("per_user_limit", 0);
        cases.put("limit 0", zeroLimit);

        cases.forEach((name, body) -> {
            TestApi.Response res = api.createShow(body);
            assertThat(res.status()).as(name).isEqualTo(400);
            assertThat(res.text("error")).as(name).isEqualTo("invalid_request");
        });
    }

    @Test
    void moneyMustBeAnIntegerNeverAFloatOrString() {
        for (String price : new String[] {"250.5", "25000.0", "\"25000\"", "1e3"}) {
            String json = "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":" + price + "}";
            TestApi.Response res = api.post("/shows", api.adminToken(), json, Map.of());

            assertThat(res.status()).as(price).isEqualTo(400);
            assertThat(res.text("error")).as(price).isEqualTo("invalid_request");
        }
    }

    @Test
    void createsLargeShowInOneTransaction() {
        List<String> seats = new java.util.ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            seats.add("S" + i);
        }
        String showId = api.createShow(seats, 100, null);

        assertThat(api.seatStatuses(showId)).hasSize(2500);
    }

    private static Map<String, Object> body(String name, List<String> seats, long price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("seats", seats);
        body.put("price_paise", price);
        return body;
    }
}
