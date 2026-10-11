package com.ts.ticketing.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected wherever "now" matters, so tests can pin time (e.g. token expiry). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
