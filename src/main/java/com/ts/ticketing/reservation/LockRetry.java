package com.ts.ticketing.reservation;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.ts.ticketing.observability.BookingMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * NFR-02: re-runs a whole transaction when MySQL picks it as a deadlock victim (1213) or a lock
 * wait times out (1205), up to {@link #MAX_RETRIES} times. Each attempt is a fresh transaction,
 * so nothing from the failed one survives. Sorted lock order makes deadlocks between reserve
 * calls impossible in the first place; this is the safety net.
 */
@Component
class LockRetry {

    static final int MAX_RETRIES = 2;
    private static final int ER_LOCK_DEADLOCK = 1213;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;

    private static final Logger log = LoggerFactory.getLogger(LockRetry.class);

    private final BookingMetrics metrics;

    LockRetry(BookingMetrics metrics) {
        this.metrics = metrics;
    }

    <T> T run(Supplier<T> transaction) {
        for (int retry = 0; ; retry++) {
            try {
                return transaction.get();
            } catch (DataAccessException e) {
                if (!isLockFailure(e) || retry >= MAX_RETRIES) {
                    throw e;
                }
                metrics.lockRetry();
                log.warn("retrying transaction after lock failure (attempt {}): {}", retry + 2, e.getMostSpecificCause().getMessage());
                backOff();
            }
        }
    }

    static boolean isLockFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == ER_LOCK_DEADLOCK || sql.getErrorCode() == ER_LOCK_WAIT_TIMEOUT)) {
                return true;
            }
        }
        return false;
    }

    /** A few random milliseconds, so two victims don't collide again in lockstep. */
    private static void backOff() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
