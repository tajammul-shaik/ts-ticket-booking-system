package com.ts.ticketing.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ts.ticketing.api.JsonErrorWriter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** NFR-04: a reserve call that can't get a slot in time is answered 429 without running. */
class AdmissionControlFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AdmissionControlFilter filter = new AdmissionControlFilter(
            new AdmissionProperties(1, 10, Duration.ofMillis(100)), new BookingMetrics(registry),
            new JsonErrorWriter(new ObjectMapper()));

    @Test
    void shedsWith429WhenNoSlotFreesUpInTime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                filter.doFilter(reserve(), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get("admission.in_flight").gauge().value()).isEqualTo(1);

        MockHttpServletResponse shed = new MockHttpServletResponse();
        MockFilterChain neverReached = new MockFilterChain();
        filter.doFilter(reserve(), shed, neverReached);

        assertThat(shed.getStatus()).isEqualTo(429);
        assertThat(shed.getContentAsString()).contains("\"error\":\"overloaded\"");
        assertThat(neverReached.getRequest()).as("request must not reach the controller").isNull();
        assertThat(registry.get("reservations.declined").tag("reason", "overloaded").counter().count()).isEqualTo(1);

        release.countDown();
        holder.join();
        MockHttpServletResponse admitted = new MockHttpServletResponse();
        filter.doFilter(reserve(), admitted, new MockFilterChain());
        assertThat(admitted.getStatus()).as("slot is released after the first call").isEqualTo(200);
    }

    @Test
    void fullQueueShedsImmediatelyWithoutWaiting() throws Exception {
        AdmissionControlFilter noQueue = new AdmissionControlFilter(
                new AdmissionProperties(1, 0, Duration.ofSeconds(5)), new BookingMetrics(new SimpleMeterRegistry()),
                new JsonErrorWriter(new ObjectMapper()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                noQueue.doFilter(reserve(), new MockHttpServletResponse(), (req, res) -> {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        MockHttpServletResponse shed = new MockHttpServletResponse();
        noQueue.doFilter(reserve(), shed, new MockFilterChain());

        assertThat(shed.getStatus()).isEqualTo(429);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no 5 s wait").isLessThan(Duration.ofSeconds(1));
        release.countDown();
        holder.join();
    }

    @Test
    void otherEndpointsAreNotLimited() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/shows/s1");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest reserve() {
        return new MockHttpServletRequest("POST", "/shows/s1/reserve");
    }
}
