package com.ts.ticketing.observability;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * seats_available / seats_held / seats_confirmed {show_id}, read from the database when
 * Prometheus scrapes, never from in-memory counters, so they always agree with GET /shows/{id}.
 *
 * <p>One grouped query serves all gauges of a scrape: the first gauge read refreshes the
 * snapshot and the others reuse it while it is younger than {@code app.metrics.seat-gauge-max-age}.
 * If the database is unreachable the gauges report NaN rather than a stale number.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final String[] STATUSES = {"available", "held", "confirmed"};

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final long maxAgeNanos;
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();

    private Map<String, Map<String, Long>> snapshot = Map.of();
    private long snapshotAt;
    private boolean snapshotOk;

    public SeatGauges(DataSource dataSource, MeterRegistry registry,
            @Value("${app.metrics.seat-gauge-max-age:500ms}") Duration maxAge) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(2);
        this.registry = registry;
        this.maxAgeNanos = maxAge.toNanos();
        this.snapshotAt = System.nanoTime() - maxAgeNanos - 1;
    }

    /** Starts exporting the three seat gauges for a show (idempotent). */
    public void track(String showId) {
        if (!tracked.add(showId)) {
            return;
        }
        for (String status : STATUSES) {
            Gauge.builder("seats." + status, this, g -> g.count(showId, status))
                    .description("Seats of the show in status " + status + ", read from the database")
                    .tag("show_id", showId)
                    .register(registry);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void trackExistingShows() {
        try {
            jdbc.queryForList("SELECT id FROM shows", String.class).forEach(this::track);
        } catch (DataAccessException e) {
            log.warn("could not load shows for seat gauges: {}", e.getMessage());
        }
    }

    private synchronized double count(String showId, String status) {
        if (System.nanoTime() - snapshotAt > maxAgeNanos) {
            refresh();
        }
        if (!snapshotOk) {
            return Double.NaN;
        }
        return snapshot.getOrDefault(showId, Map.of()).getOrDefault(status, 0L);
    }

    private void refresh() {
        Map<String, Map<String, Long>> counts = new HashMap<>();
        try {
            jdbc.query("SELECT show_id, status, COUNT(*) AS n FROM seats GROUP BY show_id, status",
                    rs -> {
                        counts.computeIfAbsent(rs.getString("show_id"), k -> new HashMap<>())
                                .put(rs.getString("status"), rs.getLong("n"));
                    });
            snapshot = counts;
            snapshotOk = true;
        } catch (DataAccessException e) {
            log.warn("seat gauges unavailable: {}", e.getMessage());
            snapshotOk = false;
        }
        snapshotAt = System.nanoTime();
    }
}
