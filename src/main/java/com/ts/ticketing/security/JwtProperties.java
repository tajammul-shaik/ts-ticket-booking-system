package com.ts.ticketing.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The JWT signing key and token lifetime (app.jwt in application.yml). The secret comes from the
 * JWT_SECRET env var; the app refuses to start without it (NFR-10).
 *
 * @param secret   HS256 signing key; at least 32 bytes, as RFC 7518 requires for HS256
 * @param tokenTtl token lifetime (24 h per FR-01)
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        @NotBlank(message = "must be set (env JWT_SECRET)") String secret,
        @NotNull Duration tokenTtl) {

    @AssertTrue(message = "must be at least 32 bytes (256 bits) for HS256")
    public boolean isSecretLongEnough() {
        // A missing secret is reported by @NotBlank alone.
        return secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}
