package com.ts.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lesson 1 in CLAUDE.md: application.yml binds `secret: ${JWT_SECRET:}`, so a missing env var is an
 * empty string, which must stop startup rather than leave a guessable key behind.
 */
class JwtPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("app.jwt.token-ttl=24h");

    @Test
    void refusesToStartWithoutSecret() {
        runner.withPropertyValues("app.jwt.secret=")
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("env JWT_SECRET"));
    }

    @Test
    void refusesShortSecret() {
        runner.withPropertyValues("app.jwt.secret=short")
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("at least 32 bytes"));
    }

    @Test
    void providesJwtServiceWhenConfigured() {
        runner.withPropertyValues("app.jwt.secret=a-perfectly-long-jwt-secret-of-32-bytes")
                .run(ctx -> assertThat(ctx).hasSingleBean(JwtService.class));
    }

    /** The same beans the application defines (SecurityConfig), without the rest of the app. */
    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {
        @Bean
        JwtService jwtService(JwtProperties props) {
            return new JwtService(props, Clock.systemUTC());
        }
    }
}
