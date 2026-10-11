package com.ts.ticketing.auth;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token issuance settings (app.identity in application.yml). The admin secret comes from the
 * ADMIN_SECRET env var; the app refuses to start without it (NFR-10, CLAUDE.md lesson 1).
 *
 * @param adminSecret value of X-Admin-Secret that turns a token request into an admin token
 */
@Validated
@ConfigurationProperties("app.identity")
public record IdentityProperties(@NotBlank(message = "must be set (env ADMIN_SECRET)") String adminSecret) {
}
