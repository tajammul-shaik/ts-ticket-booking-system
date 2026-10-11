package com.ts.ticketing.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory token bucket per key. A bucket starts full (burst capacity) and refills continuously
 * at the replenish rate; a request takes one token or is refused.
 *
 * <p>In memory is enough for one instance (the brief allows one instance, and the BRD rules out
 * Redis). With several replicas each would enforce its own share; a shared store would be the
 * next step.
 */
public class TokenBucketRateLimiter {

    /** @param retryAfterSeconds whole seconds until one token is available again (0 when allowed) */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public TokenBucketRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public Decision tryConsume(String key, int replenishPerSecond, int capacity) {
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoTime.getAsLong()))
                .take(replenishPerSecond, capacity, nanoTime.getAsLong());
    }

    /** Drops buckets that have refilled completely: they behave exactly like a new one. */
    public int evictIdle(int replenishPerSecond, int capacity) {
        long now = nanoTime.getAsLong();
        int before = buckets.size();
        buckets.values().removeIf(b -> b.isFull(replenishPerSecond, capacity, now));
        return before - buckets.size();
    }

    int size() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
        }

        synchronized Decision take(int rate, int capacity, long now) {
            refill(rate, capacity, now);
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, (long) tokens, 0);
            }
            long retryAfter = (long) Math.ceil((1 - tokens) / rate);
            return new Decision(false, 0, Math.max(1, retryAfter));
        }

        synchronized boolean isFull(int rate, int capacity, long now) {
            refill(rate, capacity, now);
            return tokens >= capacity;
        }

        private void refill(int rate, int capacity, long now) {
            double elapsedSeconds = (now - lastRefill) / 1e9;
            tokens = Math.min(capacity, tokens + elapsedSeconds * rate);
            lastRefill = now;
        }
    }
}
