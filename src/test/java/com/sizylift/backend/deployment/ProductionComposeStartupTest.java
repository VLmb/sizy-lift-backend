package com.sizylift.backend.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import com.sizylift.backend.identity.deletion.KeycloakAccountDeletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Реальный Compose разворачивает старый env-файл; Spring читает полученные production-настройки. */
class ProductionComposeStartupTest {
    private static final String ENABLED = "ACCOUNT_DELETION_ENABLED";
    private static final String CLIENT = "KEYCLOAK_DELETION_CLIENT_ID";
    private static final String SECRET = "KEYCLOAK_DELETION_CLIENT_SECRET";
    @TempDir Path temporary;

    @Test
    void legacyEnvironmentStartsWithAccountDeletionDisabled() throws Exception {
        verify(Map.of(), "false", false);
    }

    @Test
    void blankOptionalSettingsAlsoStartWithAccountDeletionDisabled() throws Exception {
        verify(Map.of(ENABLED, "", CLIENT, "", SECRET, ""), "false", false);
    }

    @Test
    void explicitConfigurationCanStillEnableDeletion() throws Exception {
        verify(Map.of(ENABLED, "true", CLIENT, "local-config-test", SECRET, "local-config-test-only"), "true", true);
    }

    private void verify(Map<String, String> optional, String expectedFlag, boolean available) throws Exception {
        var properties = new HashMap<String, String>();
        for (var line : Files.readAllLines(Path.of("deploy/.env.example"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var parts = line.split("=", 2);
            if (parts[0].equals(ENABLED) || parts[0].equals(CLIENT) || parts[0].equals(SECRET)) continue;
            properties.put(parts[0], parts[1].isBlank() ? "local-config-test-only" : parts[1]);
        }
        properties.putAll(optional);
        var env = temporary.resolve("legacy.env");
        Files.write(env, properties.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        var model = temporary.resolve("compose.json");
        var errors = temporary.resolve("compose-errors.txt");
        var process = new ProcessBuilder("docker", "compose", "--env-file", env.toString(),
                "-f", "deploy/compose.prod.yaml", "config", "--format", "json");
        // Настройки машины разработчика не должны подменять тестовый env-файл.
        properties.keySet().forEach(process.environment()::remove);
        process.environment().remove(ENABLED);
        process.environment().remove(CLIENT);
        process.environment().remove(SECRET);
        var running = process.redirectOutput(model.toFile()).redirectError(errors.toFile()).start();
        assertThat(running.waitFor()).as("docker compose config").isZero();
        var json = new ObjectMapper().readTree(Files.readString(model));
        var environment = json.get("services").get("backend").get("environment");
        var values = new HashMap<String, String>();
        environment.properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
        var spring = values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .withPropertyValues(spring)
                .withUserConfiguration(KeycloakAccountDeletion.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    if (available) context.getBean(KeycloakAccountDeletion.class).requireConfigured();
                    else assertThatThrownBy(context.getBean(KeycloakAccountDeletion.class)::requireConfigured)
                            .isInstanceOf(KeycloakAccountDeletion.Unavailable.class);
                });
        assertThat(values.get(ENABLED)).isEqualTo(expectedFlag);
        assertThat(Files.readString(errors)).doesNotContain(ENABLED, CLIENT, SECRET);
    }
}
