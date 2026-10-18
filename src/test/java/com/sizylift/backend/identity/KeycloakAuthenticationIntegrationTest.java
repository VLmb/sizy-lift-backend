package com.sizylift.backend.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозная проверка с настоящим Keycloak и realm-файлом из репозитория:
 * токены мобильного клиента принимаются, всё остальное отклоняется.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeycloakAuthenticationIntegrationTest extends PostgresIntegrationTest {

    static final String REALM = "sizylift";
    static final String CLIENT_ID = "sizylift-mobile";
    static final String PASSWORD = "correct-horse-battery";

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            "quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCopyFileToContainer(MountableFile.forHostPath("keycloak/realm-sizylift.json"),
                    "/opt/keycloak/data/import/realm-sizylift.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static String email;

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realmUrl("localhost"));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realmUrl("localhost") + "/protocol/openid-connect/certs");
    }

    @BeforeAll
    static void createVerifiedUser() throws Exception {
        var admin = adminToken();
        // В realm из репозитория password grant выключен; включаем его, чтобы получить токен без браузера.
        var clientUuid = JsonPath.<String>read(
                send(adminRequest(admin, "/clients?clientId=" + CLIENT_ID).GET(), 200), "$[0].id");
        send(adminRequest(admin, "/clients/" + clientUuid)
                .PUT(json("{\"directAccessGrantsEnabled\": true}")), 204);

        email = "lifter-" + UUID.randomUUID() + "@example.com";
        send(adminRequest(admin, "/users").POST(json("""
                {"username": "%s", "email": "%s", "emailVerified": true, "enabled": true,
                 "firstName": "Test", "lastName": "Lifter",
                 "credentials": [{"type": "password", "value": "%s", "temporary": false}]}
                """.formatted(email, email, PASSWORD))), 201);
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void acceptsAccessTokenIssuedForMobileClient() throws Exception {
        var tokens = login("localhost");

        var response = callMe(JsonPath.read(tokens, "$.access_token"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.email")).isEqualTo(email);
        assertThat(JsonPath.<Boolean>read(response.body(), "$.emailVerified")).isTrue();
    }

    @Test
    void rejectsIdTokenBecauseItIsNotIssuedForTheApi() throws Exception {
        var tokens = login("localhost");

        var response = callMe(JsonPath.read(tokens, "$.id_token"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).contains("invalid_token"));
    }

    @Test
    void rejectsTokenFromAnotherIssuerEvenWithValidSignature() throws Exception {
        // Тот же realm и ключи, но через 127.0.0.1, поэтому "iss" отличается от настроенного issuer.
        var tokens = login("127.0.0.1");

        assertThat(callMe(JsonPath.read(tokens, "$.access_token")).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsTamperedToken() throws Exception {
        var parts = JsonPath.<String>read(login("localhost"), "$.access_token").split("\\.");
        var decoder = Base64.getUrlDecoder();
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var payload = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8)
                .replace(email, "attacker@example.com");
        var forged = parts[0] + "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThat(callMe(forged).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> callMe(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String login(String host) throws Exception {
        var request = formPost(URI.create(realmUrl(host) + "/protocol/openid-connect/token"), Map.of(
                        "grant_type", "password",
                        "client_id", CLIENT_ID,
                        "username", email,
                        "password", PASSWORD,
                        "scope", "openid"));
        return send(request, 200);
    }

    private static String adminToken() throws Exception {
        var request = formPost(URI.create(baseUrl("localhost") + "/realms/master/protocol/openid-connect/token"), Map.of(
                        "grant_type", "password",
                        "client_id", "admin-cli",
                        "username", "admin",
                        "password", "admin"));
        return JsonPath.read(send(request, 200), "$.access_token");
    }

    private static HttpRequest.Builder adminRequest(String adminToken, String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl("localhost") + "/admin/realms/" + REALM + path))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json");
    }

    private static String send(HttpRequest.Builder request, int expectedStatus) throws Exception {
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return response.body();
    }

    private static HttpRequest.BodyPublisher json(String body) {
        return HttpRequest.BodyPublishers.ofString(body);
    }

    private static HttpRequest.Builder formPost(URI uri, Map<String, String> fields) {
        return HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(form(fields));
    }

    private static HttpRequest.BodyPublisher form(Map<String, String> fields) {
        return HttpRequest.BodyPublishers.ofString(fields.entrySet().stream()
                .map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&")));
    }

    private static String baseUrl(String host) {
        return "http://" + host + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static String realmUrl(String host) {
        return baseUrl(host) + "/realms/" + REALM;
    }
}
