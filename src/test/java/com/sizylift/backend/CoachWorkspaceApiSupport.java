package com.sizylift.backend;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Общие шаги API-тестов рабочего места тренера: пользователи, связь, публикация v1 и запись sync v2. */
public abstract class CoachWorkspaceApiSupport extends PostgresIntegrationTest {

    protected static final String ISSUER = "http://keycloak.test/realms/sizylift";
    protected static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    /** Плановая тренировка из фикстуры программы. */
    protected static final UUID PLANNED_WORKOUT = UUID.fromString("30000000-0000-4000-8000-000000000004");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    /** Связь тренер → подопечный; возвращает id связи. */
    protected UUID link(String coach, String athlete) throws Exception {
        call(coach, put("/api/v2/coaching/coach-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        var token = body(call(coach, post("/api/v2/coaching/invites")).andExpect(status().isCreated()))
                .get("token").asString();
        var link = body(call(athlete, post("/api/v2/coaching/invites/" + token + "/accept"))
                .andExpect(status().isOk()));
        return UUID.fromString(link.get("id").asString());
    }

    protected Map<String, Object> item(String type, UUID id, UUID ownerId, long baseRevision, long clientVersion,
                                       JsonNode payload) {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("ownerId", ownerId);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", T10.plusSeconds(clientVersion).toString());
        item.put("clientId", "device-1");
        item.put("clientVersion", clientVersion);
        item.put("schemaVersion", 1);
        item.put("deleted", payload == null);
        item.put("payload", payload);
        return item;
    }

    /** Запись через sync v2; возвращает результат элемента. */
    protected JsonNode push(String subject, Map<String, Object> item) throws Exception {
        var result = body(call(subject, post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
        assertThat(result.get("status").asString()).as(result.toString()).isEqualTo("accepted");
        return result;
    }

    /** Публикация v1; {@code payload == null} — удаление. */
    protected void legacy(String subject, String type, UUID id, long version, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", payload == null);
        item.put("payload", payload);
        var result = body(call(subject, post("/api/v1/publication/batch").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(item))))).andExpect(status().isOk()))
                .get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("accepted");
    }

    protected ObjectNode reschedule(UUID program, String date) {
        return json.createObjectNode().put("programId", program.toString()).put("scheduledDate", date)
                .put("updatedAt", T10.toString());
    }

    /** Фикстура тренировки с новыми id блоков, упражнений и подходов: дочерние строки уникальны у владельца. */
    protected ObjectNode workout() throws Exception {
        return withFreshIds("workout");
    }

    /** Фикстура с новыми id всех вложенных строк, чтобы у одного владельца было несколько её копий. */
    protected ObjectNode withFreshIds(String name) throws Exception {
        var text = fixture(name).toString();
        var ids = new java.util.LinkedHashSet<String>();
        var matcher = java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        for (var id : ids) {
            text = text.replace(id, UUID.randomUUID().toString());
        }
        return (ObjectNode) json.readTree(text);
    }

    protected ObjectNode fixture(String name) throws Exception {
        try (var input = new ClassPathResource("publication/" + name + ".v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    protected ResultActions call(String subject, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(token(subject)));
    }

    protected ResultActions postJson(String subject, String path, Object body) throws Exception {
        return call(subject, post(path).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String text ? text : json.writeValueAsString(body)));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected UUID userId(String subject) throws Exception {
        call(subject, get("/api/v1/me")).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    protected static String newSubject() {
        return UUID.randomUUID().toString();
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
