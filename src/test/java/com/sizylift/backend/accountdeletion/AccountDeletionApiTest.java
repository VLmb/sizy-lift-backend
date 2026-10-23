package com.sizylift.backend.accountdeletion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.accountdeletion.config.KeycloakAdminProperties;
import com.sizylift.backend.accountdeletion.service.KeycloakAdminClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Удаление аккаунта (docs/authentication.md) против заглушки Keycloak Admin API: все данные пользователя
 * удаляются, связи разрываются по обычным правилам, при недоступном Keycloak не удаляется ничего.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountDeletionApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final String KEYCLOAK = "http://keycloak.test";
    static final String TOKEN_URL = KEYCLOAK + "/realms/sizylift/protocol/openid-connect/token";
    static final String SERVICE_TOKEN = "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\"}";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    /** Колонки, через которые строка принадлежит пользователю или ссылается на него. */
    static final List<String> USER_COLUMNS = List.of("user_id", "coach_user_id", "athlete_user_id",
            "target_owner_id", "accepted_by");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MockRestServiceServer keycloak;

    @Autowired
    MeterRegistry meters;

    String coach;
    String athlete;

    @BeforeEach
    void newUsers() {
        coach = UUID.randomUUID().toString();
        athlete = UUID.randomUUID().toString();
        keycloak.reset();
    }

    @Test
    void deletesEveryRowOfTheUserAndTheKeycloakUser() throws Exception {
        var userId = userId(athlete);
        publish(athlete, "workout", fixture("workout"));
        publish(athlete, "program", fixture("program"));
        publish(athlete, "athlete_profile", fixture("athlete_profile"));
        publish(athlete, "injury", fixture("injury"));
        call(athlete, put("/api/v2/devices/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"ios\",\"pushToken\":\"ExpoPushToken[abc]\"}")).andExpect(status().isOk());
        invite(athlete);
        assertThat(rowsOf(userId)).isPositive();
        var deleted = meters.counter("sizylift.accounts.deleted").count();
        expectKeycloakDeletion(athlete, HttpStatus.NO_CONTENT);

        call(athlete, delete("/api/v1/me")).andExpect(status().isNoContent());

        keycloak.verify();
        assertThat(rowsOf(userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.user_account WHERE id = ?", Long.class,
                userId)).isZero();
        assertThat(meters.counter("sizylift.accounts.deleted").count()).isEqualTo(deleted + 1);
    }

    @Test
    void deletingTheCoachHandsCoachProgramsToTheAthlete() throws Exception {
        var athleteId = userId(athlete);
        accept(athlete, invite(coach));
        var program = UUID.randomUUID();
        var pushed = push(coach, programItem(program, athleteId));
        assertThat(pushed.get("status").asString()).isEqualTo("accepted");
        var athleteCursor = cursor(athlete);
        expectKeycloakDeletion(coach, HttpStatus.NO_CONTENT);

        call(coach, delete("/api/v1/me")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT author_user_id FROM sizylift.published_aggregate "
                + "WHERE user_id = ? AND aggregate_id = ?", UUID.class, athleteId, program)).isNull();
        var handedOver = changes(athlete, athleteCursor);
        assertThat(handedOver).hasSize(1);
        assertThat(handedOver.get(0).get("kind").asString()).isEqualTo("upsert");
        assertThat(handedOver.get(0).get("payload").get("name").asString()).isEqualTo("От тренера");
        assertThat(body(call(athlete, get("/api/v2/coaching")).andExpect(status().isOk())).get("coach").isNull())
                .isTrue();
    }

    @Test
    void deletingTheAthleteRevokesTheCoachCopyEvenAfterTheOwnerIsGone() throws Exception {
        var athleteId = userId(athlete);
        publish(athlete, "workout", fixture("workout"));
        accept(athlete, invite(coach));
        var coachCursor = cursor(coach);
        assertThat(changes(coach, 0)).hasSize(1);
        expectKeycloakDeletion(athlete, HttpStatus.NO_CONTENT);

        call(athlete, delete("/api/v1/me")).andExpect(status().isNoContent());

        var revoked = changes(coach, coachCursor);
        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0).get("kind").asString()).isEqualTo("revoke");
        assertThat(revoked.get(0).get("ownerId").asString()).isEqualTo(athleteId.toString());
        assertThat(body(call(coach, get("/api/v2/coaching")).andExpect(status().isOk())).get("athletes").size())
                .isZero();
    }

    @Test
    void keycloakFailureDeletesNothingAndCanBeRetried() throws Exception {
        var userId = userId(athlete);
        publish(athlete, "workout", fixture("workout"));
        keycloak.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        call(athlete, delete("/api/v1/me")).andExpect(status().isServiceUnavailable());

        keycloak.verify();
        assertThat(rowsOf(userId)).isPositive();

        keycloak.reset();
        expectKeycloakDeletion(athlete, HttpStatus.NOT_FOUND);
        call(athlete, delete("/api/v1/me")).andExpect(status().isNoContent());
        keycloak.verify();
        assertThat(rowsOf(userId)).isZero();
    }

    private void expectKeycloakDeletion(String subject, HttpStatus keycloakStatus) {
        keycloak.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials",
                        "client_id", "sizylift-backend", "client_secret", "test-secret")))
                .andRespond(withSuccess(SERVICE_TOKEN, MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(KEYCLOAK + "/admin/realms/sizylift/users/" + subject))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andRespond(withStatus(keycloakStatus));
    }

    /** Строки во всех таблицах схемы, принадлежащие пользователю или ссылающиеся на него. */
    private long rowsOf(UUID userId) {
        var columns = jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'sizylift' AND column_name = ANY (?)
                """, (Object) USER_COLUMNS.toArray(String[]::new));
        assertThat(columns).hasSizeGreaterThan(USER_COLUMNS.size());
        var total = 0L;
        for (var column : columns) {
            total += jdbc.queryForObject("SELECT count(*) FROM sizylift.%s WHERE %s = ?"
                    .formatted(column.get("table_name"), column.get("column_name")), Long.class, userId);
        }
        return total;
    }

    private String invite(String subject) throws Exception {
        call(subject, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        return body(call(subject, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
    }

    private void accept(String subject, String token) throws Exception {
        call(subject, post("/api/v2/coaching/invites/" + token + "/accept")).andExpect(status().isOk());
    }

    private Map<String, Object> programItem(UUID id, UUID ownerId) throws Exception {
        var payload = fixture("program");
        payload.put("name", "От тренера");
        var item = new HashMap<String, Object>();
        item.put("type", "program");
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", 0);
        item.put("changedAt", T10.toString());
        item.put("clientId", "device-" + UUID.randomUUID());
        item.put("clientVersion", 1);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        return item;
    }

    private JsonNode push(String subject, Map<String, Object> item) throws Exception {
        return body(call(subject, post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
    }

    private void publish(String subject, String type, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", UUID.randomUUID());
        item.put("version", 1);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        var result = body(call(subject, post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("accepted");
    }

    private List<JsonNode> changes(String subject, long after) throws Exception {
        var page = body(call(subject, get("/api/v2/sync/changes").param("after", String.valueOf(after))
                .param("limit", "500")).andExpect(status().isOk()));
        var result = new ArrayList<JsonNode>();
        page.get("changes").forEach(result::add);
        return result;
    }

    private long cursor(String subject) throws Exception {
        return body(call(subject, get("/api/v2/sync/changes").param("after", "0").param("limit", "500"))
                .andExpect(status().isOk())).get("nextCursor").asLong();
    }

    private ObjectNode fixture(String name) throws Exception {
        try (var input = new ClassPathResource("publication/" + name + ".v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    private ResultActions call(String subject, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(token(subject)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID userId(String subject) throws Exception {
        call(subject, get("/api/v1/me")).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }

    @TestConfiguration
    static class StubKeycloak {

        final RestClient.Builder builder = RestClient.builder().baseUrl(KEYCLOAK);
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Bean
        MockRestServiceServer keycloakServer() {
            return server;
        }

        @Bean
        @Primary
        KeycloakAdminClient stubbedKeycloakAdminClient(KeycloakAdminProperties properties) {
            return new KeycloakAdminClient(builder.build(), properties);
        }
    }
}
