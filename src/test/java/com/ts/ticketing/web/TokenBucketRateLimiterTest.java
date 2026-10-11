package com.ts.ticketing.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(now::get);

    @Test
    void allowsBurstThenRefusesUntilRefilled() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("u1", 1, 5).allowed()).as("request %d", i).isTrue();
        }
        TokenBucketRateLimiter.Decision refused = limiter.tryConsume("u1", 1, 5);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);

        now.addAndGet(1_000_000_000L);                         // one second → one token
        assertThat(limiter.tryConsume("u1", 1, 5).allowed()).isTrue();
        assertThat(limiter.tryConsume("u1", 1, 5).allowed()).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("noisy", 1, 3);
        }
        assertThat(limiter.tryConsume("noisy", 1, 3).allowed()).isFalse();
        assertThat(limiter.tryConsume("quiet", 1, 3).allowed()).isTrue();
    }

    @Test
    void neverRefillsAboveCapacityAndEvictsIdleBuckets() {
        limiter.tryConsume("u1", 10, 3);
        now.addAndGet(60_000_000_000L);                        // a minute idle

        assertThat(limiter.tryConsume("u1", 10, 3).remaining()).isEqualTo(2);
        now.addAndGet(60_000_000_000L);
        assertThat(limiter.evictIdle(10, 3)).isEqualTo(1);
        assertThat(limiter.size()).isZero();
    }
}
