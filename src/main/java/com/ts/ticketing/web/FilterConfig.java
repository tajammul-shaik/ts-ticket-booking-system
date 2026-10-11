package com.ts.ticketing.web;

import com.ts.ticketing.api.JsonErrorWriter;
import com.ts.ticketing.observability.AdmissionControlFilter;
import com.ts.ticketing.observability.AdmissionProperties;
import com.ts.ticketing.observability.BookingMetrics;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The servlet filter pipeline, cheapest rejection first:
 *
 * <ol>
 *   <li>request id: every later log line and error body carries it</li>
 *   <li>request validation: body size, path ids, Idempotency-Key header (no body read, no JWT parsed)</li>
 *   <li>Spring Security: JWT → 401 / 403 ({@code SecurityConfig})</li>
 *   <li>rate limit: per verified user, else per client IP → 429 rate_limited</li>
 *   <li>admission control: bounded reserve calls in flight → 429 overloaded</li>
 * </ol>
 */
@Configuration
public class FilterConfig {

    private static final int SECURITY = SecurityProperties.DEFAULT_FILTER_ORDER;

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return register(new RequestIdFilter(), Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public FilterRegistrationBean<RequestValidationFilter> requestValidationFilter(EdgeProperties props,
            JsonErrorWriter errors) {
        return register(new RequestValidationFilter(props, errors), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(EdgeProperties props, JsonErrorWriter errors, MeterRegistry registry) {
        return new RateLimitFilter(props.rateLimit(), errors, registry, System::nanoTime);
    }

    /** After security, so the bucket key can be the verified user id. */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        return register(filter, SECURITY + 1);
    }

    /** After security and rate limiting: unauthenticated or throttled calls never queue for a slot. */
    @Bean
    public FilterRegistrationBean<AdmissionControlFilter> admissionControlFilter(AdmissionProperties props,
            BookingMetrics metrics, JsonErrorWriter errors) {
        return register(new AdmissionControlFilter(props, metrics, errors), SECURITY + 2);
    }

    private static <F extends jakarta.servlet.Filter> FilterRegistrationBean<F> register(F filter, int order) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(order);
        return registration;
    }
}
