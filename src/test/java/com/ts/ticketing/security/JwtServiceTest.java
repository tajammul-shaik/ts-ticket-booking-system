package com.ts.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-at-least-32-bytes!!";
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final JwtProperties PROPS = new JwtProperties(SECRET, Duration.ofHours(24));

    private final JwtService jwt = new JwtService(PROPS, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedTokenVerifiesToSameUserAndRole() {
        JwtService.IssuedToken issued = jwt.issue("u123", Role.ADMIN);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(jwt.verify(issued.token())).isEqualTo(new AuthenticatedUser("u123", Role.ADMIN));
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwt.issue("u123", Role.USER).token();
        JwtService later = new JwtService(PROPS, Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC));

        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = Jwts.builder()
                .subject("u123").claim("role", "admin")
                .expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor("some-other-secret-also-32-bytes-long".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.verify(forged)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = jwt.issue("u123", Role.USER).token().split("\\.");
        String adminPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"u123\",\"role\":\"admin\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> jwt.verify(parts[0] + "." + adminPayload + "." + parts[2]))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void unsignedTokenIsRejected() {
        String unsigned = Jwts.builder().subject("u123").claim("role", "admin").compact();

        assertThatThrownBy(() -> jwt.verify(unsigned)).isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    void unknownRoleIsRejected() {
        String token = Jwts.builder()
                .subject("u123").claim("role", "superuser")
                .expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(JwtService.InvalidTokenException.class);
    }
}
