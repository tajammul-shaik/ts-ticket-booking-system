package com.ts.ticketing.web;

import java.io.IOException;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.ts.ticketing.api.JsonErrorWriter;
import com.ts.ticketing.security.AuthenticatedUser;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting per caller, after Spring Security so the key can be the verified user id: one
 * bucket per user (app.edge.rate-limit.user-*), one per client IP for unauthenticated calls
 * (anonymous-*). Over the limit → 429 rate_limited with Retry-After. Every response it checks
 * carries X-RateLimit-Remaining. Health and metrics are exempt (probes and scrapes).
 *
 * <p>This protects the service from one noisy caller; admission control (429 overloaded)
 * protects it from everyone at once. Buckets live in memory, which is exact for one instance;
 * several instances would each enforce their own share.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final EdgeProperties.RateLimit config;
    private final TokenBucketRateLimiter users;
    private final TokenBucketRateLimiter anonymous;
    private final JsonErrorWriter errors;
    private final Counter limited;

    public RateLimitFilter(EdgeProperties.RateLimit config, JsonErrorWriter errors, MeterRegistry registry,
            LongSupplier nanoTime) {
        this.config = config;
        this.errors = errors;
        this.users = new TokenBucketRateLimiter(nanoTime);
        this.anonymous = new TokenBucketRateLimiter(nanoTime);
        this.limited = Counter.builder("requests.rate_limited")
                .description("Requests refused with 429 rate_limited")
                .register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !config.enabled() || path.startsWith("/health/") || path.equals("/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthenticatedUser user = currentUser();
        TokenBucketRateLimiter.Decision decision = user != null
                ? users.tryConsume(user.userId(), config.userReplenishRate(), config.userBurstCapacity())
                : anonymous.tryConsume(clientIp(request), config.anonymousReplenishRate(), config.anonymousBurstCapacity());
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        if (!decision.allowed()) {
            limited.increment();
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            errors.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "too many requests from this caller, retry after " + decision.retryAfterSeconds() + " s");
            return;
        }
        chain.doFilter(request, response);
    }

    private static AuthenticatedUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private String clientIp(HttpServletRequest request) {
        if (config.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** Keeps memory bounded: idle (refilled) buckets are dropped every minute. */
    @Scheduled(fixedDelay = 60_000)
    void evictIdleBuckets() {
        users.evictIdle(config.userReplenishRate(), config.userBurstCapacity());
        anonymous.evictIdle(config.anonymousReplenishRate(), config.anonymousBurstCapacity());
    }
}
