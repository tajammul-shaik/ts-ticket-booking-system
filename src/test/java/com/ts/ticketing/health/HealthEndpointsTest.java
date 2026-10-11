package com.ts.ticketing.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** FR-06, FR-07 and T-11: readiness follows the database, liveness never does. */
@Testcontainers
@AutoConfigureObservability // metric exporters are off in tests by default
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthEndpointsTest {

    // Own container: the last test stops it.
    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    TestRestTemplate http;

    @Test
    @Order(1)
    void liveAndReadyWhenDatabaseIsUp() throws InterruptedException {
        assertThat(http.getForEntity("/health/live", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Like an orchestrator, wait for readiness: the pool's first connection can take
        // longer than the probe's 2 s deadline on a slow Docker host.
        assertThat(awaitReady(Duration.ofSeconds(60))).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(2)
    void metricsAreServedInPrometheusFormat() {
        ResponseEntity<String> res = http.getForEntity("/metrics", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("hikaricp_connections_pending");
    }

    private HttpStatusCode awaitReady(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpStatusCode status;
        while ((status = http.getForEntity("/health/ready", String.class).getStatusCode()) != HttpStatus.OK
                && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        return status;
    }

    @Test
    @Order(3)
    void readyFailsClosedButLiveStaysUpWhenDatabaseStops() {
        mysql.stop();

        ResponseEntity<String> ready = http.getForEntity("/health/ready", String.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ready.getBody()).contains("not_ready");
        assertThat(http.getForEntity("/health/live", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
