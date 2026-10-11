package com.ts.ticketing.security;

/**
 * The caller as proven by their token (BR-08). Controllers take it via
 * {@code @AuthenticationPrincipal}; a user id in a request body is never trusted.
 */
public record AuthenticatedUser(String userId, Role role) {
}
