package com.ts.ticketing.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** app.edge.*: the checks every request passes before it reaches a controller. */
@Validated
@ConfigurationProperties("app.edge")
public record EdgeProperties(@Valid @NotNull RateLimit rateLimit, @Valid @NotNull Validation validation) {

    /**
     * Token bucket per caller. A bucket holds up to burstCapacity tokens and refills at
     * replenishRate tokens per second; each request takes one.
     */
    public record RateLimit(
            boolean enabled,
            @Min(1) int userReplenishRate,
            @Min(1) int userBurstCapacity,
            @Min(1) int anonymousReplenishRate,
            @Min(1) int anonymousBurstCapacity,
            boolean trustForwardedFor) {
    }

    public record Validation(@Min(1) long maxBodyBytes) {
    }
}
