package com.sizylift.backend.accountdeletion;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Удаление аккаунта против настоящего Keycloak с realm-файлом из репозитория и настройками как в
 * deploy/compose.prod.yaml: HTTPS обязателен для realm ({@code sslRequired: all}), Keycloak доверяет
 * {@code X-Forwarded-*}, публичный адрес задан {@code KC_HOSTNAME}. Проверяет клиент {@code sizylift-backend},
 * его роль и то, что бэкенд достаёт до Admin API по внутреннему HTTP-адресу.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeycloakAccountDeletionIntegrationTest extends PostgresIntegrationTest {

    static final String REALM = "sizylift";
    static final String PUBLIC_URL = "https://auth.sizylift.test";
    static final String ISSUER = PUBLIC_URL + "/realms/" + REALM;
    static final String BACKEND_SECRET = "backend-secret-for-tests";

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            "quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KC_HOSTNAME", PUBLIC_URL)
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("KC_PROXY_HEADERS", "xforwarded")
            .withEnv("KC_REALM_SSL_REQUIRED", "all")
            .withEnv("KEYCLOAK_BACKEND_CLIENT_SECRET", BACKEND_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath("keycloak/realm-sizylift.json"),
                    "/opt/keycloak/data/import/realm-sizylift.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("sizylift.keycloak-admin.url", KeycloakAccountDeletionIntegrationTest::internalUrl);
        registry.add("sizylift.keycloak-admin.client-secret", () -> BACKEND_SECRET);
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deletesTheKeycloakUserAndTheAccount() throws Exception {
        var admin = adminToken();
        assertThat(JsonPath.<String>read(adminGet(admin, "").body(), "$.sslRequired")).isEqualTo("all");
        var subject = createUser(admin);
        mvc.perform(get("/api/v1/me").with(token(subject))).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/me").with(token(subject))).andExpect(status().isNoContent());

        assertThat(adminGet(admin, "/users/" + subject).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.user_account WHERE subject = ?", Long.class,
                subject)).isZero();
        mvc.perform(delete("/api/v1/me").with(token(subject))).andExpect(status().isNoContent());
    }

    private static String createUser(String admin) throws Exception {
        var email = "deleted-" + UUID.randomUUID() + "@example.com";
        var response = HTTP.send(adminRequest(admin, "/users").POST(HttpRequest.BodyPublishers.ofString("""
                {"username": "%s", "email": "%s", "emailVerified": true, "enabled": true}
                """.formatted(email, email))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var location = response.headers().firstValue("Location").orElseThrow();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static HttpResponse<String> adminGet(String admin, String path) throws Exception {
        return HTTP.send(adminRequest(admin, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String adminToken() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(internalUrl()
                        + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(Map.of("grant_type", "password", "client_id", "admin-cli", "username", "admin",
                        "password", "admin")))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    private static HttpRequest.Builder adminRequest(String admin, String path) {
        return HttpRequest.newBuilder(URI.create(internalUrl() + "/admin/realms/" + REALM + path))
                .header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/json");
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }

    private static String internalUrl() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080);
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject));
    }
}
