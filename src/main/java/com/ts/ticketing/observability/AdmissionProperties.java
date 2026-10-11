package com.ts.ticketing.observability;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * NFR-04 load shedding.
 *
 * @param maxInFlight    reserve calls allowed inside the service at once; the rest queue for a slot
 * @param maxQueued      calls allowed to wait for a slot; one more gets 429 at once. Every waiting
 *                       call holds heap (~110 KB), so this bounds memory under a stampede.
 * @param acquireTimeout how long a queued call waits for a slot before it gets 429 overloaded (5 s per BRD)
 */
@Validated
@ConfigurationProperties("app.admission")
public record AdmissionProperties(@Min(1) int maxInFlight, @Min(0) int maxQueued, @NotNull Duration acquireTimeout) {
}
