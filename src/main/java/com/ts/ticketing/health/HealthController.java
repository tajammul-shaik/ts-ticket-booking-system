package com.ts.ticketing.health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-06. Liveness never touches the database; readiness runs SELECT 1 and fails closed.
 *
 * <p>The probe runs on its own virtual thread with a hard 2 s deadline, so a saturated
 * connection pool (whose wait timeout is far longer) cannot stall the health check.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(2);

    private final JdbcTemplate jdbc;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    public HealthController(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout((int) READY_TIMEOUT.toSeconds());
    }

    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        Future<Integer> probe = probes.submit(() -> jdbc.queryForObject("SELECT 1", Integer.class));
        try {
            probe.get(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (TimeoutException e) {
            probe.cancel(true);
            return notReady("database did not answer within " + READY_TIMEOUT.toSeconds() + "s");
        } catch (ExecutionException e) {
            return notReady("database unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notReady("readiness probe interrupted");
        }
    }

    private static ResponseEntity<Map<String, String>> notReady(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "not_ready", "message", message));
    }

    @PreDestroy
    void shutdown() {
        probes.shutdownNow();
    }
}
