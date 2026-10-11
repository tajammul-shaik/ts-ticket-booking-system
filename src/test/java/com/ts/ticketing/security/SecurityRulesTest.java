package com.ts.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.ts.ticketing.IntegrationTest;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.Test;

/** Route rules (FR-01, FR-02, NFR-10): a missing/bad token is 401, a non-admin can't create shows. */
class SecurityRulesTest extends IntegrationTest {

    @Test
    void protectedEndpointWithoutTokenIs401() {
        TestApi.Response res = api.getShow(null, "any");

        assertThat(res.status()).isEqualTo(401);
        assertThat(res.text("error")).isEqualTo("unauthorized");
    }

    @Test
    void garbageTokenIs401() {
        assertThat(api.getShow("not-a-jwt", "any").status()).isEqualTo(401);
    }

    @Test
    void createShowWithUserTokenIs403() {
        TestApi.Response res = api.post("/shows", api.userToken("u1"),
                Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1), Map.of());

        assertThat(res.status()).isEqualTo(403);
        assertThat(res.text("error")).isEqualTo("forbidden");
    }

    @Test
    void createShowWithAdminTokenPassesSecurity() {
        // Past security, the empty body fails validation: 400, not 401/403.
        TestApi.Response res = api.post("/shows", api.adminToken(), "{}", Map.of());

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("error")).isEqualTo("invalid_request");
    }

    @Test
    void tokenIssuanceIsPublic() {
        assertThat(api.post("/auth/token", null, Map.of("user_id", "u1"), Map.of()).status()).isEqualTo(200);
    }
}
