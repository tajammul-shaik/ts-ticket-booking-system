package com.ts.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ts.ticketing.api.JsonErrorWriter;
import com.ts.ticketing.security.AuthenticatedUser;
import com.ts.ticketing.security.Role;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Per-caller token buckets, with time injected: no sleeping, no flakiness. */
class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitFilter filter = new RateLimitFilter(
            new EdgeProperties.RateLimit(true, 1, 3, 1, 2, false),
            new JsonErrorWriter(new ObjectMapper()), registry, now::get);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userOverTheBurstGets429WithRetryAfterAndOthersAreUnaffected() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("burster", "/shows/s1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call("burster", "/shows/s1");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getContentAsString()).contains("\"error\":\"rate_limited\"");
        assertThat(registry.get("requests.rate_limited").counter().count()).isEqualTo(1);
        assertThat(call("calm", "/shows/s1").getStatus()).as("own bucket").isEqualTo(200);

        now.addAndGet(1_000_000_000L);                                     // one second: one token back
        assertThat(call("burster", "/shows/s1").getStatus()).isEqualTo(200);
    }

    @Test
    void anonymousCallersAreLimitedPerIp() throws Exception {
        assertThat(call(null, "/auth/token").getStatus()).isEqualTo(200);
        assertThat(call(null, "/auth/token").getStatus()).isEqualTo(200);
        assertThat(call(null, "/auth/token").getStatus()).isEqualTo(429);
    }

    @Test
    void healthAndMetricsAreNeverLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(call(null, "/health/ready").getStatus()).isEqualTo(200);
            assertThat(call(null, "/metrics").getStatus()).isEqualTo(200);
        }
    }

    private MockHttpServletResponse call(String userId, String path) throws Exception {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(userId, Role.USER), null, List.of()));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response;
    }
}
