package com.ts.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The checks that run before a controller: request shape, content type, unknown routes, public probes. */
class EdgeChecksTest extends IntegrationTest {

    @Test
    void bodyThatIsNotJsonIs415() {
        HttpHeaders headers = bearer(api.userToken("v"));
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> res = http.exchange("/shows/s1/reserve", HttpMethod.POST, new HttpEntity<>("seats=A1", headers),
                String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(415);
        assertThat(res.getBody()).contains("\"error\":\"unsupported_media_type\"");
    }

    @Test
    void oversizedBodyIs413BeforeItIsRead() {
        HttpHeaders headers = bearer(api.adminToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"name\":\"" + "x".repeat(600 * 1024) + "\"}";

        ResponseEntity<String> res = http.exchange("/shows", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(res.getBody()).contains("\"error\":\"payload_too_large\"", "\"request_id\"");
    }

    @Test
    void overlongIdempotencyKeyHeaderIs400() {
        TestApi.Response res = api.post("/shows/s1/reserve", api.userToken("v"), Map.of("seats", List.of("A1")),
                Map.of("Idempotency-Key", "k".repeat(129)));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("error")).isEqualTo("invalid_request");
    }

    @Test
    void malformedIdInPathIs400() {
        TestApi.Response res = api.getPath("/shows/bad.id!", api.userToken("v"));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("error")).isEqualTo("invalid_request");
    }

    @Test
    void unknownRouteIsJson404() {
        TestApi.Response res = api.getPath("/nope", api.userToken("v"));

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.text("error")).isEqualTo("not_found");
    }

    @Test
    void healthAndMetricsArePublic() {
        assertThat(api.getPath("/health/live", null).status()).isEqualTo(200);
        assertThat(http.getForEntity("/metrics", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void checkedResponsesCarryTheRemainingRateLimit() {
        TestApi.Response res = api.getShow(api.userToken(TestApi.uniqueUser("rl")), "missing");

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.header("X-RateLimit-Remaining")).isEqualTo("39");    // burst 40, one taken
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
