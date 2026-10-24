package com.sizylift.backend.config;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** Не даёт dev-значениям по умолчанию попасть в production-профиль. */
class ProductionProfileTest {

    static final List<String> REQUIRED_FROM_ENVIRONMENT = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            "sizylift.notifications.expo.access-token",
            "sizylift.web.allowed-origins");

    static final List<String> TELEMETRY_ENDPOINTS = List.of(
            "management.otlp.metrics.export.url",
            "management.opentelemetry.tracing.export.otlp.endpoint",
            "management.opentelemetry.logging.export.otlp.endpoint");

    static final Pattern PLACEHOLDER_WITHOUT_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+}");

    @Test
    void connectionSettingsComeOnlyFromEnvironment() throws Exception {
        var source = prodProfile();

        for (var key : REQUIRED_FROM_ENVIRONMENT) {
            assertThat(String.valueOf(source.getProperty(key)))
                    .as(key)
                    .matches(PLACEHOLDER_WITHOUT_DEFAULT);
        }
    }

    @Test
    void telemetryGoesOnlyToTheCollectorFromEnvironment() throws Exception {
        var source = prodProfile();

        for (var key : TELEMETRY_ENDPOINTS) {
            assertThat(String.valueOf(source.getProperty(key)))
                    .as(key)
                    .matches("\\$\\{OTLP_ENDPOINT}/v1/(metrics|traces|logs)");
        }
    }

    private static EnumerablePropertySource<?> prodProfile() throws Exception {
        return (EnumerablePropertySource<?>) new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yaml")).getFirst();
    }
}
