package com.ts.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Seat reservation at scale, as one application: token issuance, request validation, rate
 * limiting, admission control, shows and reservations. Every atomic decision is SQL on MySQL.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // idle rate-limit buckets are evicted every minute
public class TicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
