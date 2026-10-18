package com.sizylift.backend.config;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the production profile against dev fallbacks sneaking in. */
class ProductionProfileTest {

    static final List<String> REQUIRED_FROM_ENVIRONMENT = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri");

    static final Pattern PLACEHOLDER_WITHOUT_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+}");

    @Test
    void connectionSettingsComeOnlyFromEnvironment() throws Exception {
        var source = (EnumerablePropertySource<?>) new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yaml")).getFirst();

        for (var key : REQUIRED_FROM_ENVIRONMENT) {
            assertThat(String.valueOf(source.getProperty(key)))
                    .as(key)
                    .matches(PLACEHOLDER_WITHOUT_DEFAULT);
        }
    }
}
