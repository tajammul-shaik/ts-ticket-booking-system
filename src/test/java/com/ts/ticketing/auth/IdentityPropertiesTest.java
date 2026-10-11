package com.ts.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** A missing ADMIN_SECRET must stop startup rather than leave a guessable admin secret. */
class IdentityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void refusesToStartWithoutAdminSecret() {
        runner.withPropertyValues("app.identity.admin-secret=")
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("env ADMIN_SECRET"));
    }

    @Test
    void bindsAdminSecret() {
        runner.withPropertyValues("app.identity.admin-secret=admin")
                .run(ctx -> assertThat(ctx.getBean(IdentityProperties.class).adminSecret()).isEqualTo("admin"));
    }

    @Configuration
    @EnableConfigurationProperties(IdentityProperties.class)
    static class Config {
    }
}
