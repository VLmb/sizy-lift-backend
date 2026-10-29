package com.sizylift.backend.identity;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.testcontainers.containers.Network;
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
    static final Network AUTH_NETWORK = Network.newNetwork();
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(
            "axllent/mailpit:v1.31.6@sha256:23fe39e81bde596a918000b3135c0f85dc8bb4c9ea6be274b051143f3e300387")
            .withNetwork(AUTH_NETWORK).withNetworkAliases("mailpit")
            .withExposedPorts(8025).waitingFor(Wait.forHttp("/").forPort(8025));

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(
            "quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withNetwork(AUTH_NETWORK)
            .withCopyFileToContainer(MountableFile.forHostPath("keycloak/realm-sizylift.json"),
                    "/opt/keycloak/data/import/realm-sizylift.json")
            .withCopyFileToContainer(MountableFile.forHostPath("keycloak/themes/sizy"),
                    "/opt/keycloak/themes/sizy")
            .withCopyFileToContainer(MountableFile.forHostPath("keycloak-provider/build/libs/sizy-keycloak-provider.jar"),
                    "/opt/keycloak/providers/sizy-keycloak-provider.jar")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        MAILPIT.start();
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
        MAILPIT.stop();
        AUTH_NETWORK.close();
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

    @Test
    void servesSizyLoginAndRecoveryInBothLanguagesWithoutEnablingConsent() throws Exception {
        for (var locale : List.of("ru", "en")) {
            var browser = browser();
            var login = open(browser, authorizationUrl("auth", locale));
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("css/sizy.css", "SIZY", "id=\"kc-form-login\"")
                    .doesNotContain("id=\"termsAccepted\"");
            assertThat(login.body()).contains(locale.equals("ru") ? "С возвращением" : "Welcome back");
            var recovery = Pattern.compile("href=\"([^\"]*reset-credentials[^\"]*)\"").matcher(login.body());
            assertThat(recovery.find()).isTrue();
            assertThat(open(browser, recovery.group(1).replace("&amp;", "&")).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void rejectsMissingAndFalseConsentBeforeCreatingUserAndStoresServerVersion() throws Exception {
        var admin = adminToken();
        setLegalConfig(admin, true, "registration-v1");
        try {
            var browser = browser();
            var registration = open(browser, authorizationUrl("registrations", "ru"));
            assertThat(registration.body()).contains("id=\"termsAccepted\"", "https://legal.example.test/consent/v1")
                    .doesNotContain("checked=\"checked\"");
            var userEmail = "consent-" + UUID.randomUUID() + "@example.com";
            var data = registrationData(userEmail);

            var missing = submit(browser, registration, "kc-register-form", data);
            assertThat(missing.body()).contains("Подтвердите согласие");
            assertThat(findUsers(admin, userEmail)).isEmpty();

            data.put("termsAccepted", "false");
            var forged = submit(browser, missing, "kc-register-form", data);
            assertThat(forged.body()).contains("Подтвердите согласие");
            assertThat(findUsers(admin, userEmail)).isEmpty();

            data.put("termsAccepted", "on");
            data.put("sizyConsentVersion", "forged-version");
            var accepted = submit(browser, forged, "kc-register-form", data);
            assertThat(accepted.statusCode()).isLessThan(500);
            var users = findUsers(admin, userEmail);
            assertThat(users).hasSize(1);
            assertThat(users.getFirst().toString()).contains("sizy_account_consent", "registration-v1", "acceptedAt")
                    .doesNotContain("forged-version");
            assertThat(users.getFirst().get("requiredActions").toString()).contains("VERIFY_EMAIL", "UPDATE_PASSWORD");
        } finally {
            setLegalConfig(admin, false, "");
        }
    }

    @Test
    void asksForNewConsentIfConfigurationChangesAfterRenderingRegistration() throws Exception {
        var admin = adminToken();
        setLegalConfig(admin, true, "before-change");
        try {
            var browser = browser();
            var registration = open(browser, authorizationUrl("registrations", "ru"));
            var userEmail = "changed-" + UUID.randomUUID() + "@example.com";
            var data = registrationData(userEmail);
            data.put("termsAccepted", "on");
            setLegalConfig(admin, true, "after-change");

            var changed = submit(browser, registration, "kc-register-form", data);
            assertThat(changed.body()).contains("Документ изменился");
            assertThat(findUsers(admin, userEmail)).isEmpty();
            submit(browser, changed, "kc-register-form", data);
            assertThat(findUsers(admin, userEmail).getFirst().toString()).contains("after-change");
        } finally {
            setLegalConfig(admin, false, "");
        }
    }

    @Test
    void existingUserMustAcceptAndDoesNotRepeatAcceptedVersion() throws Exception {
        var admin = adminToken();
        var userEmail = "existing-" + UUID.randomUUID() + "@example.com";
        send(adminRequest(admin, "/users").POST(json("""
                {"username":"%s","email":"%s","emailVerified":true,"enabled":true,
                 "firstName":"Test","lastName":"Lifter",
                 "credentials":[{"type":"password","value":"%s","temporary":false}]}
                """.formatted(userEmail, userEmail, PASSWORD))), 201);
        setLegalConfig(admin, true, "existing-v1");
        try {
            var browser = browser();
            var consent = signIn(browser, userEmail);
            assertThat(consent.body()).contains("id=\"sizy-consent-form\"");
            var missing = submit(browser, consent, "sizy-consent-form", Map.of("accept", "on"));
            assertThat(missing.body()).contains("Подтвердите согласие");
            assertThat(findUsers(admin, userEmail).getFirst().toString()).doesNotContain("sizy_account_consent");

            var accepted = submit(browser, missing, "sizy-consent-form", Map.of("accept", "on", "termsAccepted", "on"));
            assertThat(accepted.headers().firstValue("Location")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("sizylift://auth/callback?"));
            var receipt = findUsers(admin, userEmail).getFirst().toString();
            assertThat(receipt).contains("existing-v1", "acceptedAt");

            var repeated = signIn(browser(), userEmail);
            assertThat(repeated.headers().firstValue("Location")).hasValueSatisfying(
                    value -> assertThat(value).startsWith("sizylift://auth/callback?"));
            assertThat(findUsers(admin, userEmail).getFirst().toString()).isEqualTo(receipt);

            setLegalConfig(admin, true, "existing-v2");
            var updatedBrowser = browser();
            var declined = submit(updatedBrowser, signIn(updatedBrowser, userEmail), "sizy-consent-form", Map.of("cancel", "on"));
            assertThat(declined.headers().firstValue("Location")).hasValueSatisfying(
                    value -> assertThat(value).contains("error=access_denied").doesNotContain("code="));
            assertThat(findUsers(admin, userEmail).getFirst().toString()).doesNotContain("existing-v2");
        } finally {
            setLegalConfig(admin, false, "");
        }
    }

    @Test
    void failsClosedWhenConsentEnabledWithoutDocumentConfiguration() throws Exception {
        var admin = adminToken();
        setLegalConfig(admin, true, "");
        try {
            var browser = browser();
            var registration = open(browser, authorizationUrl("registrations", "ru"));
            var userEmail = "unconfigured-" + UUID.randomUUID() + "@example.com";
            var data = registrationData(userEmail);
            data.put("termsAccepted", "on");
            var rejected = submit(browser, registration, "kc-register-form", data);
            assertThat(rejected.body()).contains("Согласие временно недоступно");
            assertThat(findUsers(admin, userEmail)).isEmpty();
        } finally {
            setLegalConfig(admin, false, "");
        }
    }

    @Test
    void administrativeClientRequiresOtpAndIssuesOnlyScopedAdministrativeRole() throws Exception {
        var admin = adminToken();
        var userEmail = "owner-" + UUID.randomUUID() + "@example.com";
        send(adminRequest(admin, "/users").POST(json("""
                {"username":"%s","email":"%s","emailVerified":true,"enabled":true,
                 "firstName":"Owner","lastName":"Sizy",
                 "credentials":[{"type":"password","value":"%s","temporary":false},
                 {"type":"otp","secretData":"{\\"value\\":\\"12345678901234567890\\"}",
                  "credentialData":"{\\"subType\\":\\"totp\\",\\"digits\\":6,\\"counter\\":0,\\"period\\":30,\\"algorithm\\":\\"HmacSHA1\\"}"}]}
                """.formatted(userEmail, userEmail, PASSWORD))), 201);
        var userId = findUsers(admin, userEmail).getFirst().get("id").toString();
        var role = send(adminRequest(admin, "/roles/sizy-admin").GET(), 200);
        send(adminRequest(admin, "/users/" + userId + "/role-mappings/realm").POST(json("[" + role + "]")), 204);
        var verifier = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK";
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var callback = "http://localhost:5173/auth/admin/callback";
        var browser = browser();
        var login = open(browser, realmUrl("localhost") + "/protocol/openid-connect/auth?client_id=sizy-admin"
                + "&response_type=code&scope=openid&prompt=login&redirect_uri="
                + URLEncoder.encode(callback, StandardCharsets.UTF_8) + "&code_challenge_method=S256&code_challenge=" + challenge);
        var otp = submit(browser, login, "kc-form-login", Map.of("username", userEmail, "password", PASSWORD));
        assertThat(otp.body()).contains("kc-otp-login-form");
        assertThat(otp.headers().firstValue("Location")).isEmpty();
        var accepted = submit(browser, otp, "kc-otp-login-form", Map.of("otp", otpCode()));
        var location = accepted.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(callback + "?");
        var code = java.util.Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(part -> part.startsWith("code=")).map(part -> java.net.URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
        var tokens = send(formPost(URI.create(realmUrl("localhost") + "/protocol/openid-connect/token"), Map.of(
                "grant_type", "authorization_code", "client_id", "sizy-admin", "redirect_uri", callback,
                "code", code, "code_verifier", verifier)), 200);
        var access = JsonPath.<String>read(tokens, "$.access_token");
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/admin/access"))
                .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void administrativeClientRequiresOtpEnrollmentWhenCredentialIsMissing() throws Exception {
        var browser = browser();
        var login = open(browser, realmUrl("localhost") + "/protocol/openid-connect/auth?client_id=sizy-admin"
                + "&response_type=code&scope=openid&prompt=login&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauth%2Fadmin%2Fcallback"
                + "&code_challenge_method=S256&code_challenge=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");
        var enrollment = submit(browser, login, "kc-form-login", Map.of("username", email, "password", PASSWORD));
        assertThat(enrollment.body()).contains("kc-totp-settings-form");
        assertThat(enrollment.headers().firstValue("Location")).isEmpty();
    }

    private static String otpCode() throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec("12345678901234567890".getBytes(StandardCharsets.US_ASCII), "HmacSHA1"));
        var hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(java.time.Instant.now().getEpochSecond() / 30).array());
        int offset = hash[hash.length - 1] & 15;
        int number = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
        return "%06d".formatted(number % 1_000_000);
    }

    private static HttpClient browser() {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL) {
            @Override
            public void put(URI uri, Map<String, List<String>> headers) throws java.io.IOException {
                super.put(uri, headers);
                // Браузеры считают HTTP localhost доверенным; java.net.CookieManager — нет.
                if ("localhost".equals(uri.getHost())) getCookieStore().getCookies().forEach(cookie -> cookie.setSecure(false));
            }
        };
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static String authorizationUrl(String endpoint, String locale) {
        return realmUrl("localhost") + "/protocol/openid-connect/" + endpoint
                + "?client_id=" + CLIENT_ID + "&response_type=code&scope=openid&prompt=login"
                + "&redirect_uri=sizylift%3A%2F%2Fauth%2Fcallback&code_challenge_method=S256"
                + "&code_challenge=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG&ui_locales=" + locale;
    }

    private static HttpResponse<String> open(HttpClient browser, String url) throws Exception {
        var uri = URI.create(baseUrl("localhost") + "/").resolve(url);
        return follow(browser, browser.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static HttpResponse<String> submit(HttpClient browser, HttpResponse<String> page, String formId,
                                              Map<String, String> data) throws Exception {
        var matcher = Pattern.compile("<form[^>]*id=\"" + formId + "\"[^>]*action=\"([^\"]+)\"").matcher(page.body());
        assertThat(matcher.find()).as("Expected form %s in %s", formId, page.body()).isTrue();
        var uri = URI.create(matcher.group(1).replace("&amp;", "&"));
        return follow(browser, browser.send(formPost(uri, data).build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static HttpResponse<String> follow(HttpClient browser, HttpResponse<String> response) throws Exception {
        for (int redirects = 0; redirects < 10; redirects++) {
            var location = response.headers().firstValue("Location");
            if (response.statusCode() < 300 || response.statusCode() >= 400 || location.isEmpty()) return response;
            var next = response.uri().resolve(location.get());
            if (!next.toString().startsWith(baseUrl("localhost") + "/")) return response;
            response = browser.send(HttpRequest.newBuilder(next).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        throw new IllegalStateException("Too many authentication redirects");
    }

    private static HttpResponse<String> signIn(HttpClient browser, String userEmail) throws Exception {
        return submit(browser, open(browser, authorizationUrl("auth", "ru")), "kc-form-login",
                Map.of("username", userEmail, "password", PASSWORD));
    }

    private static Map<String, String> registrationData(String userEmail) {
        return new HashMap<>(Map.of("email", userEmail, "firstName", "Test", "lastName", "Lifter",
                "password", PASSWORD, "password-confirm", PASSWORD));
    }

    private static void setLegalConfig(String admin, boolean enabled, String version) throws Exception {
        send(adminRequest(admin, "").PUT(json("""
                {"attributes":{"sizy.legal.enabled":"%s","sizy.legal.version":"%s",
                "sizy.legal.privacy-url":"https://legal.example.test/privacy/v1",
                "sizy.legal.consent-url":"https://legal.example.test/consent/v1"}}
                """.formatted(enabled, version))), 204);
    }

    private static List<Map<String, Object>> findUsers(String admin, String userEmail) throws Exception {
        return JsonPath.read(send(adminRequest(admin, "/users?exact=true&email="
                + URLEncoder.encode(userEmail, StandardCharsets.UTF_8)).GET(), 200), "$");
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
