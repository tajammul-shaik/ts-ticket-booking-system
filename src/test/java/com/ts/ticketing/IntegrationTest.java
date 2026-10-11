package com.ts.ticketing;

import com.ts.ticketing.security.JwtService;
import com.ts.ticketing.support.Invariants;
import com.ts.ticketing.support.TestApi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base for tests that need the full app on a real MySQL. The container is started once and
 * shared by every subclass, and all subclasses share one Spring context (same annotations).
 * After every test the database-wide invariants are checked (T-10).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // metric exporters are off in tests by default
public abstract class IntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static {
        MYSQL.start();
    }

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private JwtService jwt;

    @LocalServerPort
    private int port;

    protected TestApi api;

    @BeforeEach
    void createApi() {
        api = new TestApi(port, jwt);
    }

    @AfterEach
    void invariantsHold() {
        Invariants.assertHold(jdbc);
    }
}
