package com.ts.ticketing.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * NFR-13: every request gets a correlation id, taken from {@code X-Request-Id} or generated.
 * It goes into the MDC (so every log line and error body carries it) and back out in the
 * response header. Runs first, before security, so even a 401 has an id. Also writes one
 * access-log line per request with its status and latency.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    private static final Logger access = LoggerFactory.getLogger("http.access");
    // A client-supplied id is echoed into logs and headers, so only accept a safe shape.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        String requestId = presented != null && SAFE_ID.matcher(presented).matches()
                ? presented : UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            access.info("{} {} {} {}", kv("method", request.getMethod()), kv("path", request.getRequestURI()),
                    kv("status", response.getStatus()), kv("latency_ms", (System.nanoTime() - start) / 1_000_000));
            MDC.remove(MDC_KEY);
        }
    }
}
