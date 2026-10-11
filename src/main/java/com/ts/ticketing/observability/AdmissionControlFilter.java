package com.ts.ticketing.observability;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.ts.ticketing.api.JsonErrorWriter;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * NFR-04: a bounded number of reserve calls run at once, and a bounded number wait for a slot.
 * A call that finds the queue full, or cannot get a slot within the acquire timeout, is answered
 * 429 overloaded before it touches the database, so a stampede turns into fast, clean declines
 * instead of requests timing out on the pool or piling up in memory (R-01, R-02).
 *
 * <p>The semaphore is fair, so slots go to the longest-waiting request. Waiting parks a virtual
 * thread; it is the request state each waiter holds (~110 KB) that makes the queue bound matter
 * in a 512 MB container. Registered after Spring Security: unauthenticated calls never queue.
 */
public class AdmissionControlFilter extends OncePerRequestFilter {

    private static final Pattern RESERVE_PATH = Pattern.compile("/shows/[^/]+/reserve");

    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final int maxQueued;
    private final long acquireTimeoutMillis;
    private final BookingMetrics metrics;
    private final JsonErrorWriter errors;

    public AdmissionControlFilter(AdmissionProperties props, BookingMetrics metrics, JsonErrorWriter errors) {
        this.slots = new Semaphore(props.maxInFlight(), true);
        this.maxQueued = props.maxQueued();
        this.acquireTimeoutMillis = props.acquireTimeout().toMillis();
        this.metrics = metrics;
        this.errors = errors;
        metrics.registerAdmissionGauges(props.maxInFlight(), slots::availablePermits, waiting::get);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && RESERVE_PATH.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!admit()) {
            metrics.declined(BookingMetrics.REASON_OVERLOADED);
            errors.write(response, HttpStatus.TOO_MANY_REQUESTS, "overloaded", "server is busy, retry shortly");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    private boolean admit() {
        // tryAcquire(0, ...) rather than tryAcquire(): it respects fairness, so a newcomer
        // can't jump ahead of calls already waiting.
        if (waiting.incrementAndGet() > maxQueued + slots.availablePermits()) {
            waiting.decrementAndGet();
            return false;                                               // queue full: shed at once
        }
        try {
            return slots.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiting.decrementAndGet();
        }
    }
}
