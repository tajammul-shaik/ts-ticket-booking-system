package com.ts.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import java.util.List;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.security.AuthenticatedUser;
import com.ts.ticketing.security.JwtService;
import com.ts.ticketing.security.Role;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** FR-01 token issuance, through the public endpoint, and what an issued token can do. */
class AuthApiTest extends IntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    @Autowired
    JwtService jwt;

    @Test
    void issuesUserTokenForPlainRequest() {
        ResponseEntity<Map<String, Object>> res = requestToken("{\"user_id\":\"u123\"}", null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("user_id", "u123").containsEntry("role", "user")
                .containsKey("expires_at");
        assertThat(jwt.verify((String) res.getBody().get("token")))
                .isEqualTo(new AuthenticatedUser("u123", Role.USER));
    }

    @Test
    void issuesAdminTokenWithCorrectAdminSecret() {
        ResponseEntity<Map<String, Object>> res = requestToken("{\"user_id\":\"ops\"}", "test-admin-secret");

        assertThat(res.getBody()).containsEntry("role", "admin");
        assertThat(jwt.verify((String) res.getBody().get("token")).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void wrongAdminSecretYieldsUserTokenNeverAdmin() {
        ResponseEntity<Map<String, Object>> res = requestToken("{\"user_id\":\"ops\"}", "guess");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("role", "user");
    }

    @Test
    void rejectsInvalidTokenRequests() {
        for (String body : new String[] {"{}", "{\"user_id\":\"\"}", "{\"user_id\":\"bad id\"}",
                "{\"user_id\":\"" + "x".repeat(65) + "\"}", "{not json"}) {
            ResponseEntity<Map<String, Object>> res = requestToken(body, null);

            assertThat(res.getStatusCode()).as(body).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody()).as(body).containsEntry("error", "invalid_request").containsKey("request_id");
        }
    }

    @Test
    void echoesClientRequestId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", "gw-123");
        ResponseEntity<Map<String, Object>> res =
                http.exchange("/auth/token", HttpMethod.POST, new HttpEntity<>("{}", headers), JSON);

        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo("gw-123");
        assertThat(res.getBody()).containsEntry("request_id", "gw-123");
    }

    /** The token from the endpoint (not one minted in-process) drives the booking API. */
    @Test
    void issuedTokensWorkAgainstTheBookingApi() {
        String admin = (String) requestToken("{\"user_id\":\"ops\"}", "test-admin-secret").getBody().get("token");
        String user = (String) requestToken("{\"user_id\":\"" + TestApi.uniqueUser("flow") + "\"}", null).getBody().get("token");

        TestApi.Response created = api.post("/shows", admin,
                Map.of("name", "token-flow", "seats", List.of("A1", "A2"), "price_paise", 25000), Map.of());
        assertThat(created.status()).isEqualTo(201);
        assertThat(api.post("/shows", user, Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1), Map.of())
                .status()).isEqualTo(403);

        TestApi.Response reserved = api.reserve(user, created.text("id"), List.of("A1"));
        assertThat(reserved.status()).isEqualTo(201);
        assertThat(reserved.body().path("amount_paise").asLong()).isEqualTo(25000);
    }

    private ResponseEntity<Map<String, Object>> requestToken(String body, String adminSecret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (adminSecret != null) {
            headers.set("X-Admin-Secret", adminSecret);
        }
        return http.exchange("/auth/token", HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }
}
