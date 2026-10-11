package com.ts.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import com.ts.ticketing.observability.BookingMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;

/** NFR-02: deadlocks (1213) and lock wait timeouts (1205) are retried at most twice; nothing else is. */
class LockRetryTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LockRetry retry = new LockRetry(new BookingMetrics(registry));

    @Test
    void retriesDeadlockThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.run(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw lockFailure(1213);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(registry.get("reservations.lock.retries").counter().count()).isEqualTo(2);
    }

    @Test
    void givesUpAfterTwoRetries() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw lockFailure(1205);
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw new DuplicateKeyException("dup", new SQLException("dup", "23000", 1062));
        })).isInstanceOf(DuplicateKeyException.class);
        assertThat(attempts).hasValue(1);
    }

    private static CannotAcquireLockException lockFailure(int errorCode) {
        return new CannotAcquireLockException("lock", new SQLException("lock", "40001", errorCode));
    }
}
