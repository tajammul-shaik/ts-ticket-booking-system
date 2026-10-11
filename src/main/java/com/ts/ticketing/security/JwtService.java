package com.ts.ticketing.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;


/**
 * Issues and verifies HS256 JWTs: sub = user id, role = user|admin, exp = now + TTL (FR-01).
 * {@code AuthController} issues them; {@code JwtAuthenticationFilter} verifies one on every
 * protected request.
 */
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final JwtProperties props;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        // verifyWith(HMAC key) only accepts HMAC-signed tokens; unsigned ("alg":"none") tokens are rejected.
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public IssuedToken issue(String userId, Role role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.tokenTtl());
        String token = Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.claim())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** @throws InvalidTokenException if the token is malformed, tampered with, expired or missing claims */
    public AuthenticatedUser verify(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            String userId = claims.getSubject();
            if (userId == null || userId.isBlank()) {
                throw new InvalidTokenException("token has no subject");
            }
            return new AuthenticatedUser(userId, Role.fromClaim(claims.get(ROLE_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
